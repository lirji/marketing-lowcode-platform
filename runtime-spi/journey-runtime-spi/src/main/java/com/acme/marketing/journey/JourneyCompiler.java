package com.acme.marketing.journey;

import com.acme.marketing.lowcode.model.Dialect;
import com.acme.marketing.lowcode.model.GraphDefinition;
import com.acme.marketing.lowcode.model.GraphEdge;
import com.acme.marketing.lowcode.model.GraphNode;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public final class JourneyCompiler {
    public JourneyPlan compile(GraphDefinition graph, long version) {
        if (graph.dialect() != Dialect.JOURNEY_STATE_MACHINE) {
            throw new IllegalArgumentException("JOURNEY dialect required");
        }
        Map<String, Map<String, String>> routes = new LinkedHashMap<>();
        for (GraphEdge edge : graph.edges()) {
            String previous = routes.computeIfAbsent(edge.sourceNodeId(), ignored -> new LinkedHashMap<>())
                    .putIfAbsent(edge.sourcePort(), edge.targetNodeId());
            if (previous != null) {
                throw new IllegalArgumentException("duplicate route from " + edge.sourceNodeId() + ':' + edge.sourcePort());
            }
        }
        Map<String, JourneyNode> nodes = new LinkedHashMap<>();
        String start = null;
        for (GraphNode source : graph.nodes()) {
            JourneyNode.Type type = toType(source.stableTypeId());
            if (type == JourneyNode.Type.TRIGGER) {
                if (start != null) {
                    throw new IllegalArgumentException("journey must have exactly one trigger");
                }
                start = source.id();
            }
            nodes.put(source.id(), new JourneyNode(source.id(), type, source.config(),
                    routes.getOrDefault(source.id(), Map.of())));
        }
        if (start == null) {
            throw new IllegalArgumentException("journey trigger is required");
        }
        return new JourneyPlan(graph.definitionId(), version, start, nodes,
                integer(graph.variables(), "maxStepsPerSignal", 100),
                integer(graph.variables(), "maxIterations", 10),
                Duration.ofSeconds(integer(graph.variables(), "stateTtlSeconds", 2_592_000)));
    }

    private static JourneyNode.Type toType(String stableTypeId) {
        String name = stableTypeId.substring(stableTypeId.lastIndexOf('.') + 1)
                .replace('-', '_').toUpperCase(Locale.ROOT);
        return JourneyNode.Type.valueOf(name);
    }

    private static int integer(Map<String, String> variables, String key, int defaultValue) {
        String value = variables.get(key);
        return value == null ? defaultValue : Integer.parseInt(value);
    }
}
