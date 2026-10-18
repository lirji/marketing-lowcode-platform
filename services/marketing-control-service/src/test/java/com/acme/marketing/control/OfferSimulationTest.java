package com.acme.marketing.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.acme.marketing.control.application.GraphSimulationService;
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
