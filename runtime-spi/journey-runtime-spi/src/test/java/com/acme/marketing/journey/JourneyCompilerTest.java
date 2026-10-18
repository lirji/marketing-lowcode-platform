package com.acme.marketing.journey;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.acme.marketing.lowcode.model.Dialect;
import com.acme.marketing.lowcode.model.GraphDefinition;
import com.acme.marketing.lowcode.model.GraphEdge;
import com.acme.marketing.lowcode.model.GraphNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JourneyCompilerTest {
    @Test
    void rejectsMissingFalseRouteBeforeRuntime() {
        var graph = graph("condition", Map.of("field", "vip", "equals", "true"), List.of("true"));
        var failure = assertThrows(IllegalArgumentException.class, () -> new JourneyCompiler().compile(graph, 1));
        assertTrue(failure.getMessage().contains("JOURNEY_MISSING_ROUTE"));
    }

    @Test
    void validatesEveryRuntimeRequiredRoute() {
        Map<String, List<String>> cases = Map.of("wait-event", List.of("matched"), "wait-timer", List.of("elapsed"),
                "send", List.of("next"), "grant", List.of("next"), "webhook", List.of("next"),
                "repeat", List.of("repeat", "exit"));
        cases.forEach((type, ports) -> {
            assertThrows(IllegalArgumentException.class,
                    () -> new JourneyCompiler().compile(graph(type, Map.of(), List.of()), 1), type);
            new JourneyCompiler().compile(graph(type, Map.of(), ports), 1);
        });
    }

    @Test
    void rejectsDanglingDuplicateAndUnreachableRoutes() {
        GraphDefinition valid = graph("condition", Map.of(), List.of("true", "false"));
        var edges = new ArrayList<>(valid.edges());
        edges.add(new GraphEdge("duplicate", "c", "true", "e", "in"));
        assertThrows(IllegalArgumentException.class, () -> new JourneyCompiler().compile(withEdges(valid, edges), 1));
        assertThrows(IllegalArgumentException.class, () -> new JourneyCompiler().compile(withEdges(valid,
                List.of(new GraphEdge("bad", "s", "next", "missing", "in"))), 1));
        assertThrows(IllegalArgumentException.class, () -> new JourneyCompiler().compile(withEdges(valid,
                List.of(new GraphEdge("direct", "s", "next", "e", "in"))), 1));
    }

    @Test
    void completeConditionGraphRunsBothBranches() {
        var plan = new JourneyCompiler().compile(graph("condition", Map.of("field", "vip", "equals", "true"),
                List.of("true", "false")), 1);
        Instant now = Instant.parse("2026-10-18T00:00:00Z");
        var enrollment = EnrollmentSnapshot.start("tenant", "enrollment", "subject", plan, now);
        for (String value : List.of("true", "false")) {
            var result = new JourneyRuntime().advance(plan, enrollment,
                    new JourneySignal.Event("signal-" + value, "START", now, Map.of("vip", value)));
            assertEquals(EnrollmentSnapshot.Status.COMPLETED, result.snapshot().status());
        }
    }

    private static GraphDefinition graph(String type, Map<String, String> config, List<String> ports) {
        var edges = new ArrayList<GraphEdge>();
        edges.add(new GraphEdge("start", "s", "next", "c", "in"));
        ports.forEach(port -> edges.add(new GraphEdge(port, "c", port, "e", "in")));
        return new GraphDefinition("journey", Dialect.JOURNEY_STATE_MACHINE, "1.0.0",
                List.of(new GraphNode("s", "journey.trigger", "1.0.0", Map.of()),
                        new GraphNode("c", "journey." + type, "1.0.0", config),
                        new GraphNode("e", "journey.end", "1.0.0", Map.of())), edges, Map.of(), Map.of());
    }

    private static GraphDefinition withEdges(GraphDefinition graph, List<GraphEdge> edges) {
        return new GraphDefinition(graph.definitionId(), graph.dialect(), graph.dialectVersion(), graph.nodes(), edges,
                graph.variables(), graph.annotations());
    }
}
