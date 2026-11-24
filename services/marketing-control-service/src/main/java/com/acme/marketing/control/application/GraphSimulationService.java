package com.acme.marketing.control.application;

import com.acme.marketing.lowcode.model.GraphDefinition;
import com.acme.marketing.decision.model.Cart;
import com.acme.marketing.decision.model.CartLine;
import com.acme.marketing.decision.model.Money;
import com.acme.marketing.decision.model.OfferCandidate;
import com.acme.marketing.decision.model.PriceStage;
import com.acme.marketing.decision.pricing.PricingEngine;
import com.acme.marketing.decision.runtime.OfferPolicy;
import com.acme.marketing.decision.runtime.OfferPolicyGraphCompiler;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public final class GraphSimulationService {
    private static final String PREVIEW_LINE = "preview-line";
    private static final String PRICE_ONLY_NOTICE = "金额试算：未核验生效时间、库存与领取资格";
    private static final String CONTEXT_NOTICE = "商品试跑：按报送的商品、门店和时间计算，库存与预算未核验";
    private static final java.util.Set<String> SCOPED_FIELDS = java.util.Set.of(
            "eligibleLineIds", "channels", "skuIds", "categoryIds", "shopIds", "requiredAudienceSnapshotId", "pricingCurrency");
    /** API预览结果的受控类型；旧价格响应保持原JSON，新裂变响应没有虚构价格字段。 */
    public sealed interface SimulationResult permits Simulation, ReferralSimulationService.ReferralSimulation { }

    /** 根据方言选择纯预览；裂变只能进入同源规则evaluator，不能走旧visited默认分支。 */
    public SimulationResult preview(GraphDefinition graph, Map<String, String> facts) {
        if (graph.dialect() == com.acme.marketing.lowcode.model.Dialect.REFERRAL_POLICY)
            return new ReferralSimulationService().simulate(graph, facts);
        return simulate(graph, facts);
    }

    /** 兼容旧价格仿真调用；裂变由preview入口返回专属结果，不能返回空轨迹假成功。 */
    public Simulation simulate(GraphDefinition graph, Map<String, String> facts) {
        if (graph.dialect() == com.acme.marketing.lowcode.model.Dialect.REFERRAL_POLICY)
            throw new com.acme.marketing.platform.error.ConflictException("REFERRAL_SIMULATION_NOT_AVAILABLE",
                    "referral simulation requires the dedicated policy evaluator integration");
        if (graph.dialect() == com.acme.marketing.lowcode.model.Dialect.OFFER_DECISION_DAG)
            return pricePreview(graph, facts);
        throw JourneySimulationErrorCode.DEDICATED_ENDPOINT_REQUIRED.invalid();
    }

    /** 保持旧金额试算的边界；显式提交商品上下文时进入独立试跑路径。 */
    private Simulation pricePreview(GraphDefinition graph, Map<String, String> facts) {
        if (facts.containsKey("skuId") || facts.containsKey("shopId") || facts.containsKey("occurredAt")) {
            return scopedPreview(graph, facts);
        }
        var candidates = new OfferPolicyGraphCompiler().previewCandidates(graph);
        if (candidates.stream().anyMatch(candidate -> candidate.stage() == com.acme.marketing.decision.model.PriceStage.SHIPPING)
                || graph.nodes().stream().anyMatch(node -> SCOPED_FIELDS.stream()
                .anyMatch(field -> !node.config().getOrDefault(field, "").isBlank()))) {
            throw OfferPolicyError.PREVIEW_CONTEXT_REQUIRED.exception();
        }
        String currency = facts.getOrDefault("currency", "CNY");
        Money amount = new Money(currency, Long.parseLong(facts.getOrDefault("amountMinor", "0")));
        Cart cart = new Cart(currency, List.of(new CartLine(PREVIEW_LINE, PREVIEW_LINE, PREVIEW_LINE,
                "", "", 1, amount, Money.zero(currency), false)));
        var result = new PricingEngine().price(cart, candidates);
        List<TraceStep> trace = new ArrayList<>();
        trace.add(new TraceStep(graph.definitionId(), "price-preview", PRICE_ONLY_NOTICE));
        graph.nodes().forEach(node -> {
            String offerId = node.config().getOrDefault("offerId", node.id());
            long discount = result.appliedOffers().stream().filter(applied -> applied.offerId().equals(offerId))
                    .mapToLong(applied -> applied.discount().minorUnits()).sum();
            trace.add(new TraceStep(node.id(), node.stableTypeId(), "discount=" + discount));
        });
        return new Simulation(result.original().minorUnits(), result.totalDiscount().minorUnits(),
                result.payable().minorUnits(), trace);
    }

    /** 以显式门店、商品和时间试跑已保存版本，复用线上商品行过滤与定价。 */
    private Simulation scopedPreview(GraphDefinition graph, Map<String, String> facts) {
        String sku = requirePreviewId(facts, "skuId");
        String shop = requirePreviewId(facts, "shopId");
        Instant occurredAt;
        try {
            occurredAt = Instant.parse(facts.getOrDefault("occurredAt", ""));
        } catch (DateTimeParseException | NullPointerException invalid) {
            throw new IllegalArgumentException("offer preview occurredAt must be an ISO-8601 instant", invalid);
        }
        Money amount = new Money(facts.getOrDefault("currency", "CNY"), Long.parseLong(facts.getOrDefault("amountMinor", "0")));
        Cart cart = new Cart(amount.currency(), List.of(new CartLine(PREVIEW_LINE, sku, shop,
                "", "", 1, amount, Money.zero(amount.currency()), false)));
        List<OfferPolicy> policies = new OfferPolicyGraphCompiler().compile(graph).policies();
        if (policies.stream().anyMatch(policy -> !policy.channels().isEmpty() || !policy.categoryIds().isEmpty()
                || !policy.requiredAudienceSnapshotId().isEmpty() || !policy.candidate().eligibleLineIds().isEmpty()
                || policy.candidate().stage() == PriceStage.SHIPPING)) {
            throw OfferPolicyError.PREVIEW_CONTEXT_REQUIRED.exception();
        }
        List<TraceStep> trace = new ArrayList<>();
        trace.add(new TraceStep(graph.definitionId(), "product-preview", CONTEXT_NOTICE));
        List<OfferCandidate> candidates = new ArrayList<>();
        for (OfferPolicy policy : policies) {
            String offerId = policy.candidate().offerId();
            var candidate = policy.candidateFor(cart);
            if (occurredAt.isBefore(policy.validFrom()) || !occurredAt.isBefore(policy.validTo())) {
                trace.add(new TraceStep(offerId, "product-preview", "OUTSIDE_VALIDITY"));
            } else if (!policy.pricingCurrency().isEmpty() && !policy.pricingCurrency().equals(cart.currency())) {
                trace.add(new TraceStep(offerId, "product-preview", "CURRENCY_MISMATCH"));
            } else if (candidate.isEmpty()) {
                trace.add(new TraceStep(offerId, "product-preview", "PRODUCT_SCOPE_MISMATCH"));
            } else {
                candidates.add(candidate.orElseThrow());
            }
        }
        var result = new PricingEngine().price(cart, candidates);
        for (OfferCandidate candidate : candidates) {
            boolean applied = result.appliedOffers().stream().anyMatch(offer -> offer.offerId().equals(candidate.offerId()));
            String outcome = applied ? "APPLIED" : amount.minorUnits() < candidate.formula().thresholdMinor()
                    ? "THRESHOLD_NOT_MET" : "NOT_APPLIED";
            trace.add(new TraceStep(candidate.offerId(), "product-preview", outcome));
        }
        return new Simulation(result.original().minorUnits(), result.totalDiscount().minorUnits(),
                result.payable().minorUnits(), trace);
    }

    private static String requirePreviewId(Map<String, String> facts, String name) {
        String value = facts.getOrDefault(name, "");
        if (value == null || value.isBlank() || value.length() > 128 || value.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("offer preview " + name + " is invalid");
        }
        return value;
    }

    public record TraceStep(String nodeId, String nodeType, String outcome) { }
    public record Simulation(long subtotalMinor, long discountMinor, long payableMinor, List<TraceStep> trace) implements SimulationResult {
        public Simulation {
            trace = List.copyOf(trace);
        }
    }
}
