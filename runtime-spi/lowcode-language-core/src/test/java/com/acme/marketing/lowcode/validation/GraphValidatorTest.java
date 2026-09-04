package com.acme.marketing.lowcode.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.acme.marketing.lowcode.model.Dialect;
import com.acme.marketing.lowcode.model.GraphDefinition;
import com.acme.marketing.lowcode.model.GraphEdge;
import com.acme.marketing.lowcode.model.GraphNode;
import com.acme.marketing.lowcode.model.MissingSemantics;
import com.acme.marketing.lowcode.model.NodeDefinition;
import com.acme.marketing.lowcode.model.NullSemantics;
import com.acme.marketing.lowcode.model.SideEffect;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class GraphValidatorTest {
    private static final String DIGEST = "sha256:" + "a".repeat(64);

    @Test
    void rejectsSideEffectsAndCyclesInDecisionDialect() {
        NodeDefinition start = node("flow.start", SideEffect.NONE, Map.of(), Map.of("out", "Flow"));
        NodeDefinition send = node("action.send", SideEffect.CONTACT, Map.of("in", "Flow"), Map.of("out", "Flow"));
        GraphDefinition graph = new GraphDefinition("offer-1", Dialect.OFFER_DECISION_DAG, "1.0.0",
                List.of(
                        new GraphNode("a", "flow.start", "1.0.0", Map.of()),
                        new GraphNode("b", "action.send", "1.0.0", Map.of())),
                List.of(
                        new GraphEdge("e1", "a", "out", "b", "in"),
                        new GraphEdge("e2", "b", "out", "a", "in")),
                Map.of(), Map.of());

        List<ValidationIssue> issues = new GraphValidator(List.of(start, send), GraphValidator.Limits.productionDefaults())
                .validate(graph);

        assertTrue(issues.stream().anyMatch(issue -> issue.code().equals("SIDE_EFFECT_FORBIDDEN")));
        assertTrue(issues.stream().anyMatch(issue -> issue.code().equals("UNBOUNDED_CYCLE")));
    }

    @Test
    void acceptsTypedAcyclicDecisionGraph() {
        NodeDefinition start = node("flow.start", SideEffect.NONE, Map.of(), Map.of("out", "Flow"));
        NodeDefinition condition = node("condition.amount", SideEffect.NONE,
                Map.of("in", "Flow"), Map.of("matched", "Flow"));
        GraphDefinition graph = new GraphDefinition("offer-1", Dialect.OFFER_DECISION_DAG, "1.0.0",
                List.of(
                        new GraphNode("a", "flow.start", "1.0.0", Map.of()),
                        new GraphNode("b", "condition.amount", "1.0.0", Map.of())),
                List.of(new GraphEdge("e1", "a", "out", "b", "in")), Map.of(), Map.of());

        assertEquals(List.of(), new GraphValidator(List.of(start, condition), GraphValidator.Limits.productionDefaults())
                .validate(graph));
    }

    private static NodeDefinition node(
            String type, SideEffect effect, Map<String, String> inputs, Map<String, String> outputs) {
        return new NodeDefinition(type, "1.0.0", Set.of(Dialect.OFFER_DECISION_DAG), "decision-abi-1",
                inputs, outputs, "schema:config", "schema:ui", NullSemantics.NO_MATCH,
                MissingSemantics.NO_MATCH, effect, 1, List.of(), DIGEST, Map.of(), Set.of("offer:edit"));
    }
}
