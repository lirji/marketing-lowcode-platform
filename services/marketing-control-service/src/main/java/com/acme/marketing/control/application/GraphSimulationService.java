package com.acme.marketing.control.application;

import com.acme.marketing.lowcode.model.GraphDefinition;
import com.acme.marketing.lowcode.model.GraphEdge;
import com.acme.marketing.lowcode.model.GraphNode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public final class GraphSimulationService {
    public Simulation simulate(GraphDefinition graph, Map<String, String> facts) {
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
                case "offer.condition" -> {
                    boolean matched = node.config().getOrDefault("equals", "")
                            .equals(facts.get(node.config().get("field")));
                    port = matched ? "true" : "false";
                    outcome = matched ? "matched" : "not-matched";
                }
                case "offer.percentage" -> {
                    BigDecimal rate = new BigDecimal(node.config().getOrDefault("basisPoints", "0"));
                    discount = Math.addExact(discount, BigDecimal.valueOf(amount)
                            .multiply(rate).divide(BigDecimal.valueOf(10_000), 0, RoundingMode.DOWN).longValueExact());
                    outcome = "discount=" + discount;
                }
                case "offer.fixed" -> {
                    discount = Math.addExact(discount, Long.parseLong(node.config().getOrDefault("amountMinor", "0")));
                    outcome = "discount=" + discount;
                }
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

    private static Map<String, Map<String, String>> routes(List<GraphEdge> edges) {
        Map<String, Map<String, String>> result = new HashMap<>();
        for (GraphEdge edge : edges) {
            result.computeIfAbsent(edge.sourceNodeId(), ignored -> new HashMap<>())
                    .put(edge.sourcePort(), edge.targetNodeId());
        }
        return result;
    }

    public record TraceStep(String nodeId, String nodeType, String outcome) { }
    public record Simulation(long subtotalMinor, long discountMinor, long payableMinor, List<TraceStep> trace) {
        public Simulation {
            trace = List.copyOf(trace);
        }
    }
}
