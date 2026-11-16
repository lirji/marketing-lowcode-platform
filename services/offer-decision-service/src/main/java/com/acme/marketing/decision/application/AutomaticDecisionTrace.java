package com.acme.marketing.decision.application;

import com.acme.marketing.platform.crypto.Digests;
import com.acme.marketing.platform.identity.TenantScope;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** 由真实决策响应生成的脱敏摘要，不把报价计算伪装成预占、支付或权益到账。 */
public record AutomaticDecisionTrace(String traceId, String requestId, String orderId, String maskedSubject,
        long generation, long durationMicros, Map<String, Object> candidates, Map<String, Object> pricing,
        String termsVersion, Instant expiresAt, boolean legalHold, Instant createdAt, List<Event> events) {
    private static final int MAX_CANDIDATES = 200;
    private static final int MAX_LABEL_LENGTH = 256;
    private static final String DECISION_EVENT = "DECISION_EVALUATED";
    private static final String EVENT_TITLE = "决策计算完成";
    private static final String EVENT_DETAIL = "记录本次候选裁决与报价摘要，不代表下单、资源预占或发奖。";

    /** 仅保留有界结果，禁止写入签名OfferToken、原始主体、购物车明细或请求头。 */
    public static AutomaticDecisionTrace from(TenantScope scope, DecisionApplicationService.DecisionRequest request,
            DecisionApplicationService.DecisionResponse response, long retentionSeconds) {
        List<Map<String, String>> outcomes = response.candidates().stream().limit(MAX_CANDIDATES)
                .map(candidate -> Map.of("offerId", label(candidate.offerId()), "outcome", label(candidate.outcome()))).toList();
        Map<String, Object> candidates = Map.of("count", response.candidates().size(),
                "truncated", response.candidates().size() > MAX_CANDIDATES || response.candidates().stream().anyMatch(candidate -> candidate.offerId().length() > MAX_LABEL_LENGTH || candidate.outcome().length() > MAX_LABEL_LENGTH), "outcomes", outcomes);
        var result = response.pricing();
        Map<String, Object> pricing = Map.of("currency", result.original().currency(),
                "originalMinor", result.original().minorUnits(), "payableMinor", result.payable().minorUnits(),
                "discountMinor", result.totalDiscount().minorUnits(), "appliedOfferCount", result.appliedOffers().size());
        String masked = Digests.sha256Hex(scope.tenantId().value() + '\0' + request.subjectToken()).substring(0, 12) + "…";
        return new AutomaticDecisionTrace(response.quoteId(), request.requestId(), request.orderId(), masked,
                response.generation(), response.durationMicros(), candidates, pricing, "",
                response.decidedAt().plusSeconds(retentionSeconds), false, response.decidedAt(),
                List.of(new Event(response.decidedAt(), DECISION_EVENT, EVENT_TITLE, EVENT_DETAIL)));
    }

    /** 只读结果容器不允许后续修改集合。 */
    public AutomaticDecisionTrace {
        candidates = Map.copyOf(candidates); pricing = Map.copyOf(pricing); events = List.copyOf(events);
    }

    private static String label(String value) { return value.length() <= MAX_LABEL_LENGTH ? value : value.substring(0, MAX_LABEL_LENGTH) + "…"; }
    /** 决策时间不冒充订单确认或下游发放时间。 */
    public record Event(Instant at, String type, String title, String detail) { }
}
