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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
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

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final AwardIntentAssembler assembler;
    private final AwardDispatchModeRouter modeRouter;
    private final RiskEvaluationGateway riskGateway;
    private final TransactionTemplate transactions;

    /** 构造使用外部风控端口和本地短事务保存首次结果的应用服务。 */
    public AwardIntentService(JdbcTemplate jdbc, ObjectMapper mapper, Clock clock,
            AwardIntentAssembler assembler, AwardDispatchModeRouter modeRouter,
            RiskEvaluationGateway riskGateway, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.clock = clock;
        this.assembler = assembler;
        this.modeRouter = modeRouter;
        this.riskGateway = riskGateway;
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
                requestHash, mode, assembled, subjectHash, finalOutcome, now));
        if (stored == null) throw new IllegalStateException("award intent transaction returned no result");
        return finish(stored);
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
            RiskOutcome outcome, Instant now) {
        String resultType = outcome.allowed() ? "OUTBOX" : "BLOCK";
        int claimed = jdbc.update("insert ignore into mk_award_intent_dedupe(tenant_id,source_system,source_request_id,request_hash,result_type,created_at,updated_at) values(?,?,?,?,?,?,?)",
                tenantId, AwardIntentAssembler.SOURCE_SYSTEM, command.sourceRequestId(), requestHash,
                resultType, format(now), format(now));
        if (claimed == 0) {
            // 唯一键插入会等待赢家提交；READ COMMITTED 随后即可重放它的首次持久化结果。
            StoredResult concurrent = findBySource(tenantId, command.sourceRequestId());
            if (concurrent == null) throw new IllegalStateException("award intent concurrent insert was lost");
            return replay(concurrent, requestHash);
        }
        if (!outcome.allowed()) {
            insertBlock(tenantId, command, requestHash, mode, subjectHash, outcome, now);
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
        jdbc.update("insert into mk_award_intent_outbox(tenant_id,intent_id,source_system,source_request_id,campaign_id,definition_version,subject_hash,delivery_mode,request_hash,payload_hash,payload_json,status_name,delivery_result,next_attempt_at,created_at,updated_at) values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                tenantId, intentId, AwardIntentAssembler.SOURCE_SYSTEM, command.sourceRequestId(),
                command.campaignId(), command.definitionVersion(), subjectHash, mode.name(), requestHash,
                payloadHash, payload, status, deliveryResult, format(now), format(now), format(now));
        if (mode == DeliveryMode.CENTER) {
            writeExpectedFacts(tenantId, intentId, command, assembled, now);
        }
        return requireBySource(tenantId, command.sourceRequestId()).view();
    }

    private void insertBlock(String tenantId, AssembleCommand command, String requestHash,
            DeliveryMode mode, String subjectHash, RiskOutcome outcome, Instant now) {
        jdbc.update("insert into mk_award_intent_block(tenant_id,intent_id,source_system,source_request_id,campaign_id,definition_version,subject_hash,delivery_mode,request_hash,risk_action,risk_reason,risk_decision_id,created_at,updated_at) values(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                tenantId, UUID.randomUUID().toString(), AwardIntentAssembler.SOURCE_SYSTEM,
                command.sourceRequestId(), command.campaignId(), command.definitionVersion(), subjectHash,
                mode.name(), requestHash, outcome.action(), outcome.reason(), outcome.decisionId(),
                format(now), format(now));
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
        List<StoredIntent> outbox = jdbc.query("select intent_id,source_system,source_request_id,campaign_id,definition_version,subject_hash,delivery_mode,request_hash,status_name,delivery_result,attempt_count,benefit_order_no,last_error,created_at,updated_at,sent_at,null,null,null from mk_award_intent_outbox where tenant_id=? and source_system=? and source_request_id=?",
                (rs, rowNum) -> stored(rs), tenantId, AwardIntentAssembler.SOURCE_SYSTEM, sourceRequestId);
        List<StoredIntent> blocked = jdbc.query("select intent_id,source_system,source_request_id,campaign_id,definition_version,subject_hash,delivery_mode,request_hash,'RISK_BLOCKED',null,0,null,'',created_at,updated_at,null,risk_action,risk_reason,risk_decision_id from mk_award_intent_block where tenant_id=? and source_system=? and source_request_id=?",
                (rs, rowNum) -> stored(rs), tenantId, AwardIntentAssembler.SOURCE_SYSTEM, sourceRequestId);
        if (!outbox.isEmpty() && !blocked.isEmpty()) {
            throw new IllegalStateException("award intent exists in both outbox and block");
        }
        StoredIntent value = outbox.isEmpty() ? (blocked.isEmpty() ? null : blocked.getFirst()) : outbox.getFirst();
        return value == null ? null : new StoredResult(value.requestHash(), value.view());
    }

    private StoredResult requireBySource(String tenantId, String sourceRequestId) {
        StoredResult value = findBySource(tenantId, sourceRequestId);
        if (value == null) throw new IllegalStateException("stored award intent is missing");
        return value;
    }

    private CursorPoint findCursor(String tenantId, String campaignId, String cursor) {
        List<CursorPoint> points = jdbc.query("select created_at,intent_id from mk_award_intent_outbox where tenant_id=? and campaign_id=? and intent_id=? union all select created_at,intent_id from mk_award_intent_block where tenant_id=? and campaign_id=? and intent_id=?",
                (rs, rowNum) -> new CursorPoint(rs.getString(1), rs.getString(2)),
                tenantId, campaignId, cursor, tenantId, campaignId, cursor);
        if (points.isEmpty()) throw new IllegalArgumentException("cursor is invalid for campaignId");
        return points.getFirst();
    }

    private List<AwardIntentView> queryCombined(String tenantId, String campaignId,
            CursorPoint point, int pageSize) {
        String union = "select intent_id,source_system,source_request_id,campaign_id,definition_version,subject_hash,delivery_mode,request_hash,status_name,delivery_result,attempt_count,benefit_order_no,last_error,created_at,updated_at,sent_at,null risk_action,null risk_reason,null risk_decision_id from mk_award_intent_outbox where tenant_id=? and campaign_id=? union all select intent_id,source_system,source_request_id,campaign_id,definition_version,subject_hash,delivery_mode,request_hash,'RISK_BLOCKED' status_name,null delivery_result,0 attempt_count,null benefit_order_no,'' last_error,created_at,updated_at,null sent_at,risk_action,risk_reason,risk_decision_id from mk_award_intent_block where tenant_id=? and campaign_id=?";
        if (point == null) {
            return jdbc.query("select * from (" + union + ") combined order by created_at desc,intent_id desc limit ?",
                    (rs, rowNum) -> stored(rs).view(), tenantId, campaignId, tenantId, campaignId, pageSize);
        }
        return jdbc.query("select * from (" + union + ") combined where created_at<? or (created_at=? and intent_id<?) order by created_at desc,intent_id desc limit ?",
                (rs, rowNum) -> stored(rs).view(), tenantId, campaignId, tenantId, campaignId,
                point.createdAt(), point.createdAt(), point.intentId(), pageSize);
    }

    private void writeExpectedFacts(String tenantId, String intentId, AssembleCommand command,
            AssembledIntent assembled, Instant now) {
        List<AwardItemIntent> items = assembled.intent().items();
        jdbc.update("insert into mk_benefit_outbox_position(tenant_id,aggregate_id,last_sequence,updated_at) values(?,?,?,?)",
                tenantId, intentId, items.size(), format(now));
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
            jdbc.update("insert into mk_benefit_outbox(tenant_id,event_id,aggregate_id,event_type,destination_topic,partition_key,stream_sequence,payload_json,next_attempt_at,created_at) values(?,?,?,?,?,?,?,?,?,?)",
                    tenantId, eventId, intentId, "MARKETING_AWARD_EXPECTED", EXPECTED_TOPIC,
                    tenantId + ':' + command.sourceRequestId(), index + 1L, json(event),
                    format(now), format(now));
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

    private static StoredIntent stored(ResultSet rs) throws SQLException {
        return new StoredIntent(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getLong(5), rs.getString(6), DeliveryMode.valueOf(rs.getString(7)), rs.getString(8),
                rs.getString(9), rs.getString(10), rs.getInt(11), rs.getString(12), rs.getString(13),
                Instant.parse(rs.getString(14)), Instant.parse(rs.getString(15)),
                rs.getString(16) == null ? null : Instant.parse(rs.getString(16)), rs.getString(17),
                rs.getString(18), rs.getString(19));
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

    private record StoredIntent(String intentId, String sourceSystem, String sourceRequestId,
            String campaignId, long definitionVersion, String subjectHash, DeliveryMode deliveryMode,
            String requestHash, String internalStatus, String deliveryResult, int attempts,
            String benefitOrderNo, String lastError, Instant createdAt, Instant updatedAt, Instant sentAt,
            String riskAction, String riskReason, String riskDecisionId) {
        private AwardIntentView view() {
            String publicStatus = "SENDING".equals(internalStatus) ? "PENDING" : internalStatus;
            return new AwardIntentView(intentId, sourceSystem, sourceRequestId, campaignId, definitionVersion,
                    subjectHash, deliveryMode, publicStatus, deliveryResult, attempts, benefitOrderNo,
                    lastError, createdAt, updatedAt, sentAt, riskAction, riskReason, riskDecisionId);
        }
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
