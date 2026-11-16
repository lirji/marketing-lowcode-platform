package com.acme.marketing.benefit.application;

import static com.acme.marketing.benefit.application.AwardFulfillmentErrors.*;

import static com.acme.marketing.platform.time.SqlTime.format;

import com.acme.marketing.benefit.application.AwardFulfillmentRepository.*;
import com.acme.marketing.platform.error.ConflictException;
import com.acme.marketing.platform.error.DependencyUnavailableException;
import com.acme.marketing.platform.error.NotFoundException;
import com.acme.marketing.platform.web.TenantContextHolder;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import com.acme.marketing.benefit.application.AwardFulfillmentStates.OrderStatus;
import com.acme.marketing.benefit.application.AwardFulfillmentStates.ItemStatus;
import com.acme.marketing.benefit.application.AwardFulfillmentStates.SyncStatus;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** 以耐久租约查询履约事实；投递状态和最近观察互相独立，查询失败不能伪造业务终态。 */
@Service
public class AwardFulfillmentService {
    private static final String PENDING = SyncStatus.PENDING.code();
    private static final String PAUSED = SyncStatus.PAUSED.code();
    private static final String CENTER = "CENTER";
    private static final String INVALID_IDENTITY = AWARD_CENTER_IDENTITY_MISMATCH;
    private static final String INVALID_STATUS = AWARD_CENTER_STATUS_INVALID;
    private final AwardFulfillmentRepository repository;
    private final AwardFulfillmentGateway gateway;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final TransactionTemplate transactions;
    private final MeterRegistry meters;
    private final boolean enabled;
    private final int maxFailures;
    private final int batchSize;
    private final Duration leaseDuration;
    private final Duration refreshInterval;
    private final String owner = "award-observer-" + UUID.randomUUID();

    /** 查询使用独立开关，缺少正式读权限时默认关闭，不扩大原有发奖权限。 */
    public AwardFulfillmentService(AwardFulfillmentRepository repository, AwardFulfillmentGateway gateway,
            ObjectMapper mapper, Clock clock, PlatformTransactionManager tx, MeterRegistry meters,
            @Value("${marketing.award.fulfillment-enabled:false}") boolean enabled,
            @Value("${marketing.award.fulfillment-max-failures:10}") int maxFailures,
            @Value("${marketing.award.fulfillment-batch-size:5}") int batchSize,
            @Value("${marketing.award.fulfillment-lease-ms:30000}") long leaseMs,
            @Value("${marketing.award.fulfillment-refresh-seconds:300}") long refreshSeconds,
            @Value("${marketing.benefit-center.request-timeout-ms:3000}") long timeoutMs) {
        if (maxFailures < 1 || maxFailures > 100 || batchSize < 1 || batchSize > 25
                || timeoutMs < 100 || timeoutMs > 30_000 || leaseMs <= timeoutMs * 2 || leaseMs > 300_000
                || refreshSeconds < 30 || refreshSeconds > 86_400) throw new IllegalArgumentException("履约观察的批量、重试或租约配置无效");
        this.repository = repository; this.gateway = gateway; this.mapper = mapper; this.clock = clock;
        this.transactions = new TransactionTemplate(tx); this.meters = meters; this.enabled = enabled;
        this.maxFailures = maxFailures; this.batchSize = batchSize;
        this.leaseDuration = Duration.ofMillis(leaseMs); this.refreshInterval = Duration.ofSeconds(refreshSeconds);
    }

    /** 返回本租户最近有效观察；没有查到过不能返回虚构的已到账状态。 */
    public View view(String intentId) {
        var scope = TenantContextHolder.requireCurrent(); scope.requirePermission("trace:read");
        String tenant = scope.tenantId().value(); requireIntent(tenant, intentId);
        return view(tenant, intentId);
    }

    /** 仅请求重新读取上游，重复点击合并在同一任务行，不能抢占执行中的查询。 */
    public View refresh(String intentId) {
        var scope = TenantContextHolder.requireCurrent(); scope.requirePermission("trace:read");
        String tenant = scope.tenantId().value(); requireIntent(tenant, intentId);
        if (!enabled) throw new DependencyUnavailableException(AWARD_FULFILLMENT_SYNC_DISABLED, DISABLED_MESSAGE);
        transactions.executeWithoutResult(status -> {
            String now = format(clock.instant()); repository.initialize(tenant, intentId, now);
            repository.requestRefresh(tenant, intentId, now);
        });
        return view(tenant, intentId);
    }

    /** 执行一个有界恢复批次；调度器仅是唤醒器，查询任务和租约都保存在数据库。 */
    public int runBatch() {
        if (!enabled) return 0;
        int completed = 0;
        for (Candidate candidate : repository.due(format(clock.instant()), Math.min(2, batchSize), batchSize)) {
            if (Thread.currentThread().isInterrupted()) break;
            Instant claimedAt = clock.instant();
            Lease lease = new Lease(candidate.tenantId(), candidate.intentId(), candidate.leaseVersion(), owner,
                    format(claimedAt), format(claimedAt.plus(leaseDuration)));
            if (!Boolean.TRUE.equals(transactions.execute(status -> repository.claim(lease)))) continue;
            try {
                // 远程查询明确位于事务外；输入只能从原始持久化意图重建。
                Intent intent = requireIntent(candidate.tenantId(), candidate.intentId());
                AwardFulfillmentGateway.Order order = gateway.query(intent.tenantId(), intent.sourceSystem(), intent.sourceRequestId());
                List<ItemView> items = verifiedItems(intent, order);
                Instant now = clock.instant();
                Completion result = new Completion(intent.tenantId(), intent.intentId(), candidate.leaseVersion() + 1, owner,
                        PENDING, order.orderNo(), order.status(), mapper.writeValueAsString(items), format(now), 0, "",
                        format(now.plus(refreshInterval)), format(now));
                if (Boolean.TRUE.equals(transactions.execute(status -> repository.complete(result)))) {
                    completed++; meters.counter("marketing.award.fulfillment.check", "result", "observed").increment();
                } else meters.counter("marketing.award.fulfillment.check", "result", "lease_lost").increment();
            } catch (RuntimeException failure) {
                String code = failure instanceof AwardFulfillmentGateway.Unavailable unavailable
                        ? unavailable.code() : AWARD_FULFILLMENT_CHECK_FAILED;
                recordFailure(candidate, code);
            }
        }
        return completed;
    }

    private void recordFailure(Candidate candidate, String code) {
        int failures = candidate.failures() + 1; Instant now = clock.instant();
        long backoff = Math.min(300, 1L << Math.min(8, failures));
        long jitter = ThreadLocalRandom.current().nextLong(Math.max(1, backoff / 4));
        Completion result = new Completion(candidate.tenantId(), candidate.intentId(), candidate.leaseVersion() + 1, owner,
                failures >= maxFailures ? PAUSED : PENDING, null, null, null, null, failures, code,
                format(now.plusSeconds(backoff + jitter)), format(now));
        boolean saved = Boolean.TRUE.equals(transactions.execute(status -> repository.complete(result)));
        meters.counter("marketing.award.fulfillment.check", "result", saved ? "unavailable" : "lease_lost").increment();
    }

    private Intent requireIntent(String tenant, String id) {
        Intent intent = repository.findIntent(tenant, id)
                .orElseThrow(() -> new NotFoundException(AWARD_INTENT_NOT_FOUND, NOT_FOUND_MESSAGE));
        if (!CENTER.equals(intent.deliveryMode())) throw new ConflictException(AWARD_FULFILLMENT_MODE_UNSUPPORTED, MODE_MESSAGE);
        return intent;
    }

    private List<ItemView> verifiedItems(Intent intent, AwardFulfillmentGateway.Order order) {
        var expected = mapper.readValue(intent.payload(), AwardIntentAssembler.AwardIntent.class);
        if (!Objects.equals(intent.sourceSystem(), order.sourceSystem()) || !Objects.equals(intent.sourceRequestId(), order.sourceRequestId())
                || !Objects.equals(expected.sourceSystem(), order.sourceSystem()) || !Objects.equals(expected.sourceRequestId(), order.sourceRequestId())
                || !Objects.equals(expected.recipientRef(), order.recipientRef()) || order.orderNo() == null
                || order.orderNo().isBlank() || order.orderNo().length() > 64
                || (intent.benefitOrderNo() != null && !intent.benefitOrderNo().equals(order.orderNo()))) throw unavailable(INVALID_IDENTITY);
        if (!OrderStatus.accepts(order.status()) || order.items().size() != expected.items().size()
                || order.items().isEmpty() || order.items().size() > 20) throw unavailable(INVALID_STATUS);
        var actual = new HashMap<String, AwardFulfillmentGateway.Item>();
        for (var item : order.items()) {
            if (item.clientItemId() == null || actual.put(item.clientItemId(), item) != null) throw unavailable(INVALID_IDENTITY);
        }
        return expected.items().stream().map(item -> {
            var found = actual.get(item.clientItemId());
            if (found == null || !Objects.equals(item.benefitSkuId(), found.skuId())
                    || !item.benefitType().name().equals(found.benefitType()) || item.quantity() != found.quantity()
                    || !Objects.equals(item.amountMinor(), found.amountMinor()) || !Objects.equals(item.currency(), found.currency())
                    || (item.expectedSkuVersion() != null && !item.expectedSkuVersion().equals(found.skuVersion()))) throw unavailable(INVALID_IDENTITY);
            if (!ItemStatus.accepts(found.status())) throw unavailable(INVALID_STATUS);
            return new ItemView(item.clientItemId(), found.skuId(), found.status(), found.quantity(), found.skuVersion());
        }).toList();
    }

    private View view(String tenant, String id) {
        return repository.find(tenant, id).map(row -> new View(id, row.state(), row.orderNo(), row.centerStatus(),
                row.checkedAt() == null ? null : Instant.parse(row.checkedAt()), row.errorCode(), row.failures(), enabled,
                row.itemsJson() == null ? List.of() : mapper.readValue(row.itemsJson(), new TypeReference<List<ItemView>>() { })))
                .orElseGet(() -> new View(id, SyncStatus.UNOBSERVED.code(), null, null, null, "", 0, enabled, List.of()));
    }
    private static AwardFulfillmentGateway.Unavailable unavailable(String code) { return new AwardFulfillmentGateway.Unavailable(code); }
    /** 对运营返回脱敏后的项结果；不返回原始主体、令牌和完整远程响应。 */
    public record ItemView(String clientItemId, String skuId, String status, long quantity, Long skuVersion) { }
    /** checkedAt 是查询时间；已观察的业务结果与同步任务的状态分别展示。 */
    public record View(String intentId, String synchronizationState, String benefitOrderNo, String centerStatus,
            Instant checkedAt, String errorCode, int consecutiveFailures, boolean refreshEnabled, List<ItemView> items) { }
}
