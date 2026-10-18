package com.acme.marketing.control.application;

import com.acme.marketing.lowcode.model.GraphDefinition;
import com.acme.marketing.lowcode.model.GraphEdge;
import com.acme.marketing.lowcode.model.GraphNode;
import com.acme.marketing.decision.model.Cart;
import com.acme.marketing.decision.model.CartLine;
import com.acme.marketing.decision.model.Money;
import com.acme.marketing.decision.pricing.PricingEngine;
import com.acme.marketing.decision.runtime.OfferPolicyGraphCompiler;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public final class GraphSimulationService {
    private static final String PREVIEW_LINE = "preview-line";
    private static final String PRICE_ONLY_NOTICE = "金额试算：未核验生效时间、库存与领取资格";
    private static final java.util.Set<String> SCOPED_FIELDS = java.util.Set.of(
            "eligibleLineIds", "channels", "skuIds", "categoryIds", "shopIds", "requiredAudienceSnapshotId");
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
        Map<String, GraphNode> nodes = new HashMap<>();
        graph.nodes().forEach(node -> nodes.put(node.id(), node));
        Map<String, Map<String, String>> routes = routes(graph.edges());
        String current = graph.nodes().stream()
                .filter(node -> graph.edges().stream().noneMatch(edge -> edge.targetNodeId().equals(node.id())))
                .map(GraphNode::id).findFirst().orElseThrow(() -> new IllegalArgumentException("entry node not found"));
        long amount = Long.parseLong(facts.getOrDefault("amountMinor", "0"));
        long discount = 0;
        List<TraceStep> trace = new ArrayList<>();
        for (int step = 0; step < 1_000 && current != null; step++) {
            GraphNode node = nodes.get(current);
            if (node == null) {
                throw new IllegalArgumentException("node not found: " + current);
            }
            String port = "next";
            String outcome = "visited";
            switch (node.stableTypeId()) {
                case "offer.end" -> current = null;
                default -> { }
            }
            trace.add(new TraceStep(node.id(), node.stableTypeId(), outcome));
            if (current != null) {
                current = routes.getOrDefault(node.id(), Map.of()).get(port);
            }
        }
        long cappedDiscount = Math.min(Math.max(discount, 0), amount);
        return new Simulation(amount, cappedDiscount, amount - cappedDiscount, trace);
    }

    /** 当前 API 只有总金额；范围受限策略缺少真实购物车/人群事实时明确拒绝试算。 */
    private Simulation pricePreview(GraphDefinition graph, Map<String, String> facts) {
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

    private static Map<String, Map<String, String>> routes(List<GraphEdge> edges) {
        Map<String, Map<String, String>> result = new HashMap<>();
        for (GraphEdge edge : edges) {
            result.computeIfAbsent(edge.sourceNodeId(), ignored -> new HashMap<>())
                    .put(edge.sourcePort(), edge.targetNodeId());
        }
        return result;
    }

    public record TraceStep(String nodeId, String nodeType, String outcome) { }
    public record Simulation(long subtotalMinor, long discountMinor, long payableMinor, List<TraceStep> trace) implements SimulationResult {
        public Simulation {
            trace = List.copyOf(trace);
        }
    }
}
