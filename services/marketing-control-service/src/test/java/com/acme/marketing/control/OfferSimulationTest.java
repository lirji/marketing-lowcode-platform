package com.acme.marketing.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.acme.marketing.control.application.GraphSimulationService;
import com.acme.marketing.decision.model.Cart;
import com.acme.marketing.decision.model.CartLine;
import com.acme.marketing.decision.model.Money;
import com.acme.marketing.decision.pricing.PricingEngine;
import com.acme.marketing.decision.runtime.OfferPolicyGraphCompiler;
import com.acme.marketing.lowcode.model.Dialect;
import com.acme.marketing.lowcode.model.GraphDefinition;
import com.acme.marketing.lowcode.model.GraphEdge;
import com.acme.marketing.lowcode.model.GraphNode;
import com.acme.marketing.platform.error.ConflictException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OfferSimulationTest {
    private final GraphSimulationService simulation = new GraphSimulationService();
    private final OfferPolicyGraphCompiler compiler = new OfferPolicyGraphCompiler();

    @Test
    void bothRateNamesHaveSamePriceAndConflictsAreRejected() {
        for (String field : List.of("basisPoints", "discountBasisPoints")) {
            var graph = graph(List.of(node("rate", "offer.percentage", Map.of(field, "1000", "capMinor", "800"))));
            assertEquals(800, simulation.simulate(graph, Map.of("amountMinor", "10000")).discountMinor());
            assertEquals(1000, compiler.compile(graph).policies().getFirst().candidate().formula().discountBasisPoints());
        }
        var conflict = graph(List.of(node("rate", "offer.percentage", Map.of(
                "basisPoints", "1000", "discountBasisPoints", "2000"))));
        assertThrows(IllegalArgumentException.class, () -> simulation.simulate(conflict, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> compiler.compile(conflict));
    }

    @Test
    void previewUsesThresholdCapAndBestSelectionInsteadOfAddingEveryNode() {
        var graph = graph(List.of(node("small", "offer.fixed", Map.of("amountMinor", "500")),
                node("large", "offer.fixed", Map.of("amountMinor", "2000", "thresholdMinor", "10000", "capMinor", "1200"))));
        assertEquals(500, simulation.simulate(graph, Map.of("amountMinor", "9999")).discountMinor());
        assertEquals(1200, simulation.simulate(graph, Map.of("amountMinor", "10000")).discountMinor());
        assertTrue(simulation.simulate(graph, Map.of()).trace().getFirst().outcome().contains("未核验"));
    }

    @Test
    void unsupportedConditionsDisconnectedFlowAndMissingPreviewContextFailClosed() {
        var condition = graph(List.of(node("condition", "offer.condition", Map.of("field", "vip", "equals", "true")),
                node("discount", "offer.fixed", Map.of("amountMinor", "1000"))));
        assertThrows(IllegalArgumentException.class, () -> simulation.simulate(condition, Map.of("vip", "false")));
        assertThrows(IllegalArgumentException.class, () -> compiler.compile(condition));
        var disconnected = new GraphDefinition("offer", Dialect.OFFER_DECISION_DAG, "1.0.0",
                List.of(node("start", "offer.start", Map.of()), node("end", "offer.end", Map.of()),
                        node("discount", "offer.fixed", Map.of("amountMinor", "1000"))),
                List.of(new GraphEdge("e", "start", "next", "end", "in")), Map.of(), Map.of());
        assertThrows(IllegalArgumentException.class, () -> compiler.compile(disconnected));
        var scoped = graph(List.of(node("discount", "offer.fixed", Map.of("amountMinor", "1000", "skuIds", "real-sku"))));
        assertEquals("OFFER_PREVIEW_CONTEXT_REQUIRED", assertThrows(ConflictException.class,
                () -> simulation.simulate(scoped, Map.of("amountMinor", "10000"))).code());
        var shipping = graph(List.of(node("shipping", "offer.fixed", Map.of("amountMinor", "500", "stage", "SHIPPING"))));
        assertEquals("OFFER_PREVIEW_CONTEXT_REQUIRED", assertThrows(ConflictException.class,
                () -> simulation.simulate(shipping, Map.of("amountMinor", "10000"))).code());
    }

    @Test
    void duplicateIdentityAcrossStagesCannotPreviewAsTwoIndependentOffers() {
        var graph = graph(List.of(node("one", "offer.fixed", Map.of("offerId", "duplicate", "stage", "ITEM", "amountMinor", "100")),
                node("two", "offer.fixed", Map.of("offerId", "duplicate", "stage", "PLATFORM", "amountMinor", "100"))));
        assertThrows(IllegalArgumentException.class, () -> simulation.simulate(graph, Map.of("amountMinor", "1000")));
        assertThrows(IllegalArgumentException.class, () -> compiler.compile(graph));
    }

    @Test
    void scopedPreviewReportsThresholdProductAndValiditySeparately() {
        var graph = thresholdOffer();
        var facts = new HashMap<>(Map.of("shopId", "shop-a", "skuId", "sku-a", "currency", "CNY",
                "occurredAt", "2026-01-01T00:00:00Z", "amountMinor", "50000"));
        assertEquals(5000, simulation.simulate(graph, facts).discountMinor());
        facts.put("amountMinor", "49999");
        assertEquals("THRESHOLD_NOT_MET", simulation.simulate(graph, facts).trace().getLast().outcome());
        facts.put("shopId", "shop-b");
        assertEquals("PRODUCT_SCOPE_MISMATCH", simulation.simulate(graph, facts).trace().getLast().outcome());
        facts.put("occurredAt", "2030-01-01T00:00:00Z");
        assertEquals("OUTSIDE_VALIDITY", simulation.simulate(graph, facts).trace().getLast().outcome());
    }

    @Test
    void mixedCartCannotReachThresholdWithOtherProductsOrReceiveDiscountOnOtherLines() {
        var policy = compiler.compile(thresholdOffer()).policies().getFirst();
        var otherSku = line("other-sku", "sku-b", "shop-a", 80000);
        var otherShop = line("other-shop", "sku-a", "shop-b", 90000);
        var mixed = new Cart("CNY", List.of(line("matched", "sku-a", "shop-a", 49000), otherSku, otherShop));
        var pricing = new PricingEngine();
        assertEquals(0, pricing.price(mixed, List.of(policy.candidateFor(mixed).orElseThrow())).totalDiscount().minorUnits());
        var eligible = new Cart("CNY", List.of(line("matched", "sku-a", "shop-a", 50000), otherSku, otherShop));
        var result = pricing.price(eligible, List.of(policy.candidateFor(eligible).orElseThrow()));
        assertEquals(Map.of("matched", new Money("CNY", 5000)), result.appliedOffers().getFirst().lineDiscounts());
        assertTrue(policy.candidateFor(new Cart("CNY", List.of(otherSku, otherShop))).isEmpty());
        var foreign = new Cart("USD", List.of(new CartLine("matched", "sku-a", "shop-a", "", "", 1,
                new Money("USD", 50000), Money.zero("USD"), false)));
        assertTrue(policy.candidateFor(foreign).isEmpty());
    }

    private static GraphDefinition thresholdOffer() {
        return graph(List.of(node("discount", "offer.fixed", Map.of("amountMinor", "5000", "thresholdMinor", "50000",
                "skuIds", "sku-a", "shopIds", "shop-a", "pricingCurrency", "CNY"))));
    }

    private static CartLine line(String id, String sku, String shop, long price) {
        return new CartLine(id, sku, shop, "", "", 1, new Money("CNY", price), Money.zero("CNY"), false);
    }

    private static GraphNode node(String id, String type, Map<String, String> config) {
        var complete = new HashMap<>(config);
        complete.put("benefitDefinitionVersion", "coupon@1");
        complete.put("validFrom", "2025-01-01T00:00:00Z");
        complete.put("validTo", "2030-01-01T00:00:00Z");
        return new GraphNode(id, type, "1.0.0", complete);
    }

    private static GraphDefinition graph(List<GraphNode> nodes) {
        return new GraphDefinition("offer", Dialect.OFFER_DECISION_DAG, "1.0.0", nodes, List.of(), Map.of(), Map.of());
    }
}
