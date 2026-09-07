package com.acme.marketing.benefit.application;

import static com.acme.marketing.platform.time.SqlTime.format;

import com.acme.marketing.benefit.application.AwardDispatchModeRouter.DeliveryMode;
import com.acme.marketing.benefit.application.AwardIntentAssembler.AssembleCommand;
import com.acme.marketing.benefit.application.AwardIntentAssembler.AssembledIntent;
import com.acme.marketing.benefit.application.AwardIntentAssembler.AwardItemIntent;
import com.acme.marketing.benefit.application.AwardIntentAssembler.BenefitType;
import com.acme.marketing.benefit.application.RiskEvaluationGateway.RiskDecision;
import com.acme.marketing.benefit.application.RiskEvaluationGateway.RiskEvaluationRequest;
import com.acme.marketing.benefit.application.RiskEvaluationGateway.RiskEvaluationUnavailableException;
import com.acme.marketing.platform.crypto.Digests;
import com.acme.marketing.platform.error.ConflictException;
import com.acme.marketing.platform.error.DependencyUnavailableException;
import com.acme.marketing.platform.web.TenantContextHolder;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * 组装、风控并持久化营销发放意图，同时提供 outbox 与风险拦截的统一只读模型。
 * 外部风控调用在数据库事务外完成，最终仅用短事务原子写入 outbox 或 block 其中之一。
 */
@Service
public class AwardIntentService {
    static final String EXPECTED_TOPIC = "marketing.award-expected.v1";
    private static final String DEGRADED_HIT = "DEGRADED_FEATURE_UNAVAILABLE";
    private static final String RISK_UNAVAILABLE = "RISK_UNAVAILABLE";

    private final AwardIntentRepository repository;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final AwardIntentAssembler assembler;
    private final AwardDispatchModeRouter modeRouter;
    private final RiskEvaluationGateway riskGateway;
    private final TransactionTemplate transactions;
    private final Duration evaluationLease;
    private final Duration concurrentWait;
    private final String evaluationOwner;

    /** 构造使用外部风控端口和本地短事务保存首次结果的应用服务。 */
    public AwardIntentService(AwardIntentRepository repository, ObjectMapper mapper, Clock clock,
            AwardIntentAssembler assembler, AwardDispatchModeRouter modeRouter,
            RiskEvaluationGateway riskGateway, PlatformTransactionManager transactionManager,
            @Value("${marketing.award.evaluation-lease-ms:30000}") long evaluationLeaseMillis,
            @Value("${marketing.award.concurrent-wait-ms:5000}") long concurrentWaitMillis) {
        if (concurrentWaitMillis < 10 || concurrentWaitMillis > 10_000
                || evaluationLeaseMillis <= concurrentWaitMillis || evaluationLeaseMillis > 300_000) {
            throw new IllegalArgumentException("award evaluation lease policy is invalid");
        }
        this.repository = repository;
        this.mapper = mapper;
        this.clock = clock;
        this.assembler = assembler;
        this.modeRouter = modeRouter;
        this.riskGateway = riskGateway;
        this.evaluationLease = Duration.ofMillis(evaluationLeaseMillis);
        this.concurrentWait = Duration.ofMillis(concurrentWaitMillis);
        this.evaluationOwner = "award:" + UUID.randomUUID();
        this.transactions = new TransactionTemplate(transactionManager);
        this.transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    /**
     * 创建或重放发放意图。所有模式都先做风控；业务拦截返回 202 读模型，不可用则持久化后返回 503。
     */
    public AwardIntentView create(String idempotencyKey, AssembleCommand command) {
        var scope = TenantContextHolder.requireCurrent();
        scope.requirePermission("benefit:award-write");
        if (!command.sourceRequestId().equals(idempotencyKey)) {
            throw new ConflictException("AWARD_IDEMPOTENCY_KEY_MISMATCH",
                    "Idempotency-Key must equal sourceRequestId");
        }
        String tenantId = scope.tenantId().value();
        String requestHash = Digests.sha256Hex(json(command));
        StoredResult existing = findBySource(tenantId, command.sourceRequestId());
        if (existing != null) return finish(replay(existing, requestHash));

        EvaluationClaim claim = transactions.execute(status -> claimEvaluation(
                tenantId, command.sourceRequestId(), requestHash, clock.instant()));
        if (claim == null) throw new IllegalStateException("award evaluation claim returned no result");
        if (!claim.owner()) {
            return finish(awaitFirstResult(tenantId, command.sourceRequestId(), requestHash));
        }

        try {
            DeliveryMode mode = modeRouter.modeFor(tenantId);
            // LEGACY 继续不解析 OfferToken，但必须用正数哨兵通过同一风控门禁。
            AssembledIntent assembled = mode == DeliveryMode.LEGACY ? null : assembler.assemble(scope, command);
            String subjectHash = assembled == null
                    ? AwardIntentAssembler.subjectHash(tenantId, command.subjectRef()) : assembled.subjectHash();
            RiskInputs riskInputs = riskInputs(assembled);
            RiskOutcome riskOutcome;
            try {
                RiskDecision decision = riskGateway.evaluate(new RiskEvaluationRequest(scope.tenantId(),
                        command.sourceRequestId(), command.subjectRef(), riskInputs.amount(),
                        riskInputs.currency(), clock.instant()));
                riskOutcome = classify(decision);
            } catch (RiskEvaluationUnavailableException unavailable) {
                riskOutcome = RiskOutcome.unavailable(unavailable.reason());
            }

            Instant now = clock.instant();
            RiskOutcome finalOutcome = riskOutcome;
            AwardIntentView stored = transactions.execute(status -> persistFirstResult(tenantId, command,
                    requestHash, mode, assembled, subjectHash, finalOutcome, claim, now));
            if (stored == null) throw new IllegalStateException("award intent transaction returned no result");
            return finish(stored);
        } catch (EvaluationLeaseLostException lost) {
            return finish(awaitFirstResult(tenantId, command.sourceRequestId(), requestHash));
        } catch (RuntimeException failure) {
            // 请求校验或外部目录失败不应留下不可恢复的 PROCESSING；CAS 确保不会删除新 owner 的租约。
            transactions.executeWithoutResult(status -> abandonEvaluation(
                    tenantId, command.sourceRequestId(), claim));
            throw failure;
        }
    }

    /** 按 campaignId 合并 outbox 与 block，并以 createdAt + intentId 做统一 seek 分页。 */
    @Transactional(readOnly = true)
    public List<AwardIntentView> list(String campaignId, Integer limit, String cursor) {
        var scope = TenantContextHolder.requireCurrent();
        scope.requirePermission("trace:read");
        String tenantId = scope.tenantId().value();
        String requiredCampaign = requireText(campaignId, "campaignId", 64);
        int pageSize = limit == null ? 20 : limit;
        if (pageSize < 1 || pageSize > 50) {
            throw new IllegalArgumentException("limit must be between 1 and 50");
        }
        if (cursor != null && !cursor.isBlank()) {
            CursorPoint point = findCursor(tenantId, requiredCampaign,
                    requireText(cursor, "cursor", 64));
            return queryCombined(tenantId, requiredCampaign, point, pageSize);
        }
        return queryCombined(tenantId, requiredCampaign, null, pageSize);
    }

    private AwardIntentView persistFirstResult(String tenantId, AssembleCommand command,
            String requestHash, DeliveryMode mode, AssembledIntent assembled, String subjectHash,
            RiskOutcome outcome, EvaluationClaim claim, Instant now) {
        String resultType = outcome.allowed() ? "OUTBOX" : "BLOCK";
        AwardIntentRepository.DedupeStateRow state = repository.findDedupeForUpdate(
                tenantId, AwardIntentAssembler.SOURCE_SYSTEM, command.sourceRequestId())
                .orElseThrow(EvaluationLeaseLostException::new);
        if (!state.requestHash().equals(requestHash)) {
            throw new ConflictException("AWARD_INTENT_IDEMPOTENCY_CONFLICT",
                    "sourceRequestId was reused with a different award trigger");
        }
        if (!"PROCESSING".equals(state.resultType()) || !evaluationOwner.equals(state.leaseOwner())
                || state.leaseVersion() != claim.leaseVersion()) {
            throw new EvaluationLeaseLostException();
        }
        if (!outcome.allowed()) {
            insertBlock(tenantId, command, requestHash, mode, subjectHash, outcome, now);
            completeEvaluation(tenantId, command.sourceRequestId(), claim, resultType, now);
            return requireBySource(tenantId, command.sourceRequestId()).view();
        }

        String intentId = UUID.randomUUID().toString();
        String payload = assembled == null ? "{}" : assembled.payload();
        String payloadHash = assembled == null ? Digests.sha256Hex(payload) : assembled.payloadHash();
        String status = mode == DeliveryMode.CENTER ? "PENDING" : "SENT";
        String deliveryResult = switch (mode) {
            case LEGACY -> "LEGACY_OWNED";
            case SHADOW -> "SHADOW_RECORDED";
            case CENTER -> "CENTER_ENQUEUED";
        };
        repository.saveIntent(new AwardIntentRepository.IntentWrite(tenantId, intentId,
                AwardIntentAssembler.SOURCE_SYSTEM, command.sourceRequestId(), command.campaignId(),
                command.definitionVersion(), subjectHash, mode.name(), requestHash, payloadHash, payload,
                status, deliveryResult, format(now), format(now), format(now)));
        if (mode == DeliveryMode.CENTER) {
            writeExpectedFacts(tenantId, intentId, command, assembled, now);
        }
        completeEvaluation(tenantId, command.sourceRequestId(), claim, resultType, now);
        return requireBySource(tenantId, command.sourceRequestId()).view();
    }

    /** 在任何外部调用前提交 durable claim；并发 loser 只能等待或重放，不会再次查询风控。 */
    private EvaluationClaim claimEvaluation(String tenantId, String sourceRequestId,
            String requestHash, Instant now) {
        Instant leaseUntil = now.plus(evaluationLease);
        boolean created = repository.tryBeginEvaluation(new AwardIntentRepository.EvaluationWrite(
                tenantId, AwardIntentAssembler.SOURCE_SYSTEM, sourceRequestId, requestHash, "PROCESSING",
                evaluationOwner, format(leaseUntil), 1L, format(now), format(now)));
        if (created) {
            return new EvaluationClaim(true, 1L);
        }
        // 普通 INSERT 让并发请求等待首次 claim 提交后收到唯一键冲突；避免 INSERT IGNORE
        // 留下多个共享锁后再统一升级 FOR UPDATE 所形成的 MySQL 死锁环。

        AwardIntentRepository.ClaimStateRow state = repository.findEvaluation(
                tenantId, AwardIntentAssembler.SOURCE_SYSTEM, sourceRequestId)
                .orElseThrow(() -> new IllegalStateException("award evaluation claim disappeared"));
        if (!state.requestHash().equals(requestHash)) {
            throw new ConflictException("AWARD_INTENT_IDEMPOTENCY_CONFLICT",
                    "sourceRequestId was reused with a different award trigger");
        }
        if (!"PROCESSING".equals(state.resultType())) {
            return new EvaluationClaim(false, state.leaseVersion());
        }
        if (state.leaseUntil() != null && !Instant.parse(state.leaseUntil()).isAfter(now)) {
            long nextVersion = Math.addExact(state.leaseVersion(), 1);
            int taken = repository.takeEvaluationLease(new AwardIntentRepository.EvaluationLeaseWrite(
                    tenantId, AwardIntentAssembler.SOURCE_SYSTEM, sourceRequestId, requestHash,
                    evaluationOwner, format(leaseUntil), nextVersion, state.leaseVersion(),
                    format(now), format(now)));
            if (taken == 1) return new EvaluationClaim(true, nextVersion);
        }
        return new EvaluationClaim(false, state.leaseVersion());
    }

    private void completeEvaluation(String tenantId, String sourceRequestId,
            EvaluationClaim claim, String resultType, Instant now) {
        int updated = repository.completeEvaluation(new AwardIntentRepository.EvaluationCompletionWrite(
                tenantId, AwardIntentAssembler.SOURCE_SYSTEM, sourceRequestId, resultType,
                evaluationOwner, claim.leaseVersion(), format(now)));
        if (updated != 1) throw new EvaluationLeaseLostException();
    }

    private void abandonEvaluation(String tenantId, String sourceRequestId, EvaluationClaim claim) {
        repository.abandonEvaluation(new AwardIntentRepository.EvaluationOwnerKey(
                tenantId, AwardIntentAssembler.SOURCE_SYSTEM, sourceRequestId,
                evaluationOwner, claim.leaseVersion()));
    }

    private AwardIntentView awaitFirstResult(String tenantId, String sourceRequestId, String requestHash) {
        long deadline = System.nanoTime() + concurrentWait.toNanos();
        while (System.nanoTime() < deadline) {
            StoredResult stored = findBySource(tenantId, sourceRequestId);
            if (stored != null) return replay(stored, requestHash);
            try {
                TimeUnit.MILLISECONDS.sleep(25);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new DependencyUnavailableException("AWARD_INTENT_IN_PROGRESS",
                "the first request is still evaluating risk; retry with the same Idempotency-Key");
    }

    private void insertBlock(String tenantId, AssembleCommand command, String requestHash,
            DeliveryMode mode, String subjectHash, RiskOutcome outcome, Instant now) {
        repository.saveBlock(new AwardIntentRepository.BlockWrite(tenantId, UUID.randomUUID().toString(),
                AwardIntentAssembler.SOURCE_SYSTEM, command.sourceRequestId(), command.campaignId(),
                command.definitionVersion(), subjectHash, mode.name(), requestHash, outcome.action(),
                outcome.reason(), outcome.decisionId(), format(now), format(now)));
    }

    private AwardIntentView replay(StoredResult existing, String requestHash) {
        if (!existing.requestHash().equals(requestHash)) {
            throw new ConflictException("AWARD_INTENT_IDEMPOTENCY_CONFLICT",
                    "sourceRequestId was reused with a different award trigger");
        }
        return existing.view();
    }

    private AwardIntentView finish(AwardIntentView view) {
        if ("RISK_BLOCKED".equals(view.status()) && "UNAVAILABLE".equals(view.riskAction())) {
            throw new DependencyUnavailableException(RISK_UNAVAILABLE,
                    "risk evaluation is unavailable; the fail-closed result was recorded");
        }
        return view;
    }

    private StoredResult findBySource(String tenantId, String sourceRequestId) {
        AwardIntentRepository.IntentRow outbox = repository.findIntentBySource(
                tenantId, AwardIntentAssembler.SOURCE_SYSTEM, sourceRequestId).orElse(null);
        AwardIntentRepository.IntentRow blocked = repository.findBlockBySource(
                tenantId, AwardIntentAssembler.SOURCE_SYSTEM, sourceRequestId).orElse(null);
        if (outbox != null && blocked != null) {
            throw new IllegalStateException("award intent exists in both outbox and block");
        }
        AwardIntentRepository.IntentRow value = outbox == null ? blocked : outbox;
        return value == null ? null : new StoredResult(value.requestHash(), view(value));
    }

    private StoredResult requireBySource(String tenantId, String sourceRequestId) {
        StoredResult value = findBySource(tenantId, sourceRequestId);
        if (value == null) throw new IllegalStateException("stored award intent is missing");
        return value;
    }

    private CursorPoint findCursor(String tenantId, String campaignId, String cursor) {
        AwardIntentRepository.CursorRow row = repository.findCursor(tenantId, campaignId, cursor)
                .orElseThrow(() -> new IllegalArgumentException("cursor is invalid for campaignId"));
        return new CursorPoint(row.createdAt(), row.intentId());
    }

    private List<AwardIntentView> queryCombined(String tenantId, String campaignId,
            CursorPoint point, int pageSize) {
        return repository.findByCampaign(tenantId, campaignId,
                        point == null ? null : point.createdAt(), point == null ? null : point.intentId(), pageSize)
                .stream().map(AwardIntentService::view).toList();
    }

    private void writeExpectedFacts(String tenantId, String intentId, AssembleCommand command,
            AssembledIntent assembled, Instant now) {
        List<AwardItemIntent> items = assembled.intent().items();
        repository.saveExpectedPosition(tenantId, intentId, items.size(), format(now));
        for (int index = 0; index < items.size(); index++) {
            AwardItemIntent item = items.get(index);
            String eventId = UUID.randomUUID().toString();
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("schemaVersion", "1.0");
            event.put("eventId", eventId);
            event.put("eventType", "MARKETING_AWARD_EXPECTED");
            event.put("tenantId", tenantId);
            event.put("sourceRequestId", command.sourceRequestId());
            event.put("campaignId", command.campaignId());
            event.put("definitionVersion", command.definitionVersion());
            event.put("subjectHash", assembled.subjectHash());
            event.put("clientItemId", item.clientItemId());
            event.put("skuId", item.benefitSkuId());
            event.put("benefitType", item.benefitType().name());
            if (item.amountMinor() != null) event.put("amountMinor", item.amountMinor());
            if (item.currency() != null) event.put("currency", item.currency());
            event.put("quantity", item.quantity());
            event.put("occurredAt", format(now));
            repository.saveExpectedEvent(new AwardIntentRepository.ExpectedEventWrite(tenantId, eventId,
                    intentId, "MARKETING_AWARD_EXPECTED", EXPECTED_TOPIC,
                    tenantId + ':' + command.sourceRequestId(), index + 1L, json(event),
                    format(now), format(now)));
        }
    }

    private static RiskInputs riskInputs(AssembledIntent assembled) {
        if (assembled == null) return new RiskInputs(1L, "XXX");
        long total = 0L;
        String currency = null;
        for (AwardItemIntent item : assembled.intent().items()) {
            if (item.benefitType() != BenefitType.CASH) continue;
            try {
                total = Math.addExact(total, item.amountMinor());
            } catch (ArithmeticException overflow) {
                throw new ConflictException("AWARD_CASH_AMOUNT_INVALID", "cash award total exceeds int64 range");
            }
            if (currency == null) currency = item.currency();
            else if (!currency.equals(item.currency())) {
                throw new ConflictException("AWARD_CASH_CURRENCY_MIXED",
                        "one AwardIntent cannot be risk-evaluated with mixed cash currencies");
            }
        }
        return total > 0L ? new RiskInputs(total, currency) : new RiskInputs(1L, "XXX");
    }

    private static RiskOutcome classify(RiskDecision decision) {
        String action = decision.action().toUpperCase(Locale.ROOT);
        if ("ALLOW".equals(action)) return RiskOutcome.allow();
        if (decision.decisionId() != null && decision.decisionId().length() > 128) {
            return RiskOutcome.unavailable("RISK_RESPONSE_INVALID");
        }
        if ("CHALLENGE".equals(action) && decision.hitRules().contains(DEGRADED_HIT)) {
            return new RiskOutcome(false, "UNAVAILABLE", DEGRADED_HIT, decision.decisionId());
        }
        if (List.of("REJECT", "CHALLENGE", "REVIEW").contains(action)) {
            String reason = decision.hitRules().isEmpty() ? "RISK_" + action
                    : String.join(",", decision.hitRules());
            return new RiskOutcome(false, action, truncate(reason), decision.decisionId());
        }
        return RiskOutcome.unavailable("RISK_RESPONSE_ACTION_INVALID");
    }

    private static AwardIntentView view(AwardIntentRepository.IntentRow row) {
        String publicStatus = "SENDING".equals(row.internalStatus()) ? "PENDING" : row.internalStatus();
        return new AwardIntentView(row.intentId(), row.sourceSystem(), row.sourceRequestId(), row.campaignId(),
                row.definitionVersion(), row.subjectHash(), DeliveryMode.valueOf(row.deliveryMode()), publicStatus,
                row.deliveryResult(), row.attempts(), row.benefitOrderNo(), row.lastError(),
                Instant.parse(row.createdAt()), Instant.parse(row.updatedAt()),
                row.sentAt() == null ? null : Instant.parse(row.sentAt()),
                row.riskAction(), row.riskReason(), row.riskDecisionId());
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JacksonException failure) {
            throw new IllegalArgumentException("award trigger cannot be serialized", failure);
        }
    }

    private static String requireText(String value, String name, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException(name + " is invalid");
        }
        return value.trim();
    }

    private static String truncate(String value) {
        return value.length() <= 1_000 ? value : value.substring(0, 1_000);
    }

    private record StoredResult(String requestHash, AwardIntentView view) { }
    private record EvaluationClaim(boolean owner, long leaseVersion) { }

    private static final class EvaluationLeaseLostException extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    private record CursorPoint(String createdAt, String intentId) { }
    private record RiskInputs(long amount, String currency) { }

    private record RiskOutcome(boolean allowed, String action, String reason, String decisionId) {
        private static RiskOutcome allow() {
            return new RiskOutcome(true, null, null, null);
        }

        private static RiskOutcome unavailable(String reason) {
            return new RiskOutcome(false, "UNAVAILABLE", truncate(reason), null);
        }
    }

    /** 发放意图统一读模型；risk 字段仅在 RISK_BLOCKED 行有值。 */
    public record AwardIntentView(String intentId, String sourceSystem, String sourceRequestId,
            String campaignId, long definitionVersion, String subjectHash, DeliveryMode deliveryMode,
            String status, String deliveryResult, int attempts, String benefitOrderNo, String lastError,
            Instant createdAt, Instant updatedAt, Instant sentAt,
            @JsonInclude(JsonInclude.Include.NON_NULL) String riskAction,
            @JsonInclude(JsonInclude.Include.NON_NULL) String riskReason,
            @JsonInclude(JsonInclude.Include.NON_NULL) String riskDecisionId) { }
}
