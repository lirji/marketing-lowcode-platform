package com.acme.marketing.journeyservice.application;

import static com.acme.marketing.platform.time.SqlTime.format;
import static com.acme.marketing.journeyservice.application.JourneyProbeDeliverySettings.*;

import com.acme.marketing.contracts.release.ReleaseBundleProtocolError;
import com.acme.marketing.journeyservice.application.JourneyProbeDeliveryRepository.*;
import com.acme.marketing.platform.web.TenantContextHolder;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** 显式授权入队与有代次的短事务进度；Kafka不在此服务内调用。 */
@Service
public class JourneyProbeDeliveryService {
    private static final int TRANSACTION_SECONDS = 5;
    private final JourneyProbeDeliveryRepository repository;
    private final JourneyExecutionProbeService evidence;
    private final JourneyExecutionDeployment deployment;
    private final JourneyProbeDeliverySettings settings;
    private final Clock clock;
    private final TransactionTemplate transaction;
    /** 后台各阶段独立提交，租约提交后才能等待父挑战，避免与入队形成反向锁序。 */
    public JourneyProbeDeliveryService(JourneyProbeDeliveryRepository repository, JourneyExecutionProbeService evidence,
            JourneyExecutionDeployment deployment, JourneyProbeDeliverySettings settings, Clock clock,
            PlatformTransactionManager manager) {
        this.repository = repository; this.evidence = evidence; this.deployment = deployment;
        this.settings = settings; this.clock = clock;
        transaction = new TransactionTemplate(manager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setTimeout(TRANSACTION_SECONDS);
    }
    /** 同挑战唯一派发；再次调用只返回原进度，不重置已发送、失败或过期终态。 */
    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = TRANSACTION_SECONDS)
    public View dispatch(String campaign, String probeId) {
        var scope = TenantContextHolder.requireCurrent(); scope.requirePermission(JourneyExecutionProbeService.PERMISSION);
        if (!settings.enabled()) throw ReleaseBundleProtocolError.EXECUTION_DELIVERY_DISABLED.conflict();
        var receipt = evidence.get(campaign, probeId);
        if (receipt.expired()) throw ReleaseBundleProtocolError.EXECUTION_DELIVERY_EXPIRED.conflict();
        var key = deployment.key(scope.tenantId().value(), campaign, probeId); String now = format(clock.instant());
        repository.ensure(key, new NewDelivery(settings.topic(), ROUTES_PER_SUBTASK * receipt.probe().targetParallelism(),
                now, format(receipt.probe().expiresAt()), scope.actorId(), now));
        return view(repository.find(key).orElseThrow(ReleaseBundleProtocolError.EXECUTION_DELIVERY_NOT_FOUND::notFound));
    }
    /** 即使暂停派发仍可查持久进度，但当前身份/数据范围和证据仍需有效。 */
    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = TRANSACTION_SECONDS)
    public View get(String campaign, String probeId) {
        var scope = TenantContextHolder.requireCurrent(); scope.requirePermission(JourneyExecutionProbeService.PERMISSION);
        evidence.get(campaign, probeId);
        return view(repository.find(deployment.key(scope.tenantId().value(), campaign, probeId))
                .orElseThrow(ReleaseBundleProtocolError.EXECUTION_DELIVERY_NOT_FOUND::notFound));
    }
    /** 每次只领取一批，租户与不可变输入从数据库恢复；没有请求上下文传播。 */
    public Optional<Claim> claim() {
        if (!settings.enabled()) return Optional.empty();
        return transaction.execute(status -> {
            Instant now = clock.instant(); String time = format(now);
            repository.expireDue(deployment.slot(), time, EXPIRE_BATCH_SIZE, ReleaseBundleProtocolError.EXECUTION_DELIVERY_EXPIRED.code());
            return repository.lockDue(deployment.slot(), time).map(row -> {
                var lease = new Lease(UUID.randomUUID().toString(), row.leaseEpoch() + 1);
                if (repository.claim(row.key(), row.leaseEpoch(), lease.owner(), format(now.plusSeconds(LEASE_SECONDS)), time) != 1)
                    throw ReleaseBundleProtocolError.EXECUTION_DELIVERY_INVALID.conflict();
                return new Claim(row, lease);
            });
        });
    }
    /** 领取事务已提交，此处仅父挑战行锁；校验后释放所有数据库锁再发送。 */
    public JourneyExecutionProbeService.Receipt inspect(Claim claim) {
        return transaction.execute(status -> {
            var row = repository.find(claim.delivery().key()).orElseThrow(ReleaseBundleProtocolError.EXECUTION_DELIVERY_NOT_FOUND::notFound);
            if (!claim.lease().owner().equals(row.leaseOwner()) || claim.lease().epoch() != row.leaseEpoch()
                    || row.leaseUntil() == null || row.leaseUntil().compareTo(format(clock.instant())) <= 0)
                throw ReleaseBundleProtocolError.EXECUTION_DELIVERY_INVALID.conflict();
            var receipt = evidence.inspectDelivery(row.key());
            if (!row.destinationTopic().equals(settings.topic()) || row.totalRoutes() != ROUTES_PER_SUBTASK * receipt.probe().targetParallelism()
                    || !row.expiresAt().equals(format(receipt.probe().expiresAt())) || row.nextNonce() < 0 || row.nextNonce() >= row.totalRoutes())
                throw ReleaseBundleProtocolError.EXECUTION_DELIVERY_INVALID.conflict();
            return receipt;
        });
    }
    /** 迟到执行者只能得到false，不能把新执行者的进度当作自己的成功。 */
    public boolean finish(Claim claim, Completion completion) {
        return Boolean.TRUE.equals(transaction.execute(status -> repository.finish(claim.delivery().key(), claim.lease(), completion, format(clock.instant())) == 1));
    }
    private View view(Delivery row) {
        return new View(row.campaignId(), row.probeId(), row.deliveryState(), row.nextNonce(), row.totalRoutes(), row.failureCount(),
                row.nextAttemptAt(), row.expiresAt(), row.lastErrorCode(), row.createdAt(), row.updatedAt(), clock.instant());
    }
    /** 仅在后台内存中传递一个有界批次；数据库租约才是当前执行权。 */
    public record Claim(Delivery delivery, Lease lease) { }
    /** API不返回Kafka目的地、租约令牌或内部异常；状态不代表APPLIED。 */
    public record View(String campaignId, String probeId, String state, int nextNonce, int totalRoutes, int failureCount,
            String nextAttemptAt, String expiresAt, String lastErrorCode, String createdAt, String updatedAt, Instant observedAt) { }
}
