package com.acme.marketing.lowcode.validation;

import com.acme.marketing.lowcode.model.Dialect;
import com.acme.marketing.lowcode.model.GraphDefinition;
import com.acme.marketing.lowcode.model.GraphEdge;
import com.acme.marketing.lowcode.model.GraphNode;
import com.acme.marketing.lowcode.model.NodeDefinition;
import com.acme.marketing.lowcode.model.SideEffect;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class GraphValidator {
    private final Map<String, NodeDefinition> registry;
    private final Limits limits;

    public GraphValidator(List<NodeDefinition> definitions, Limits limits) {
        this.registry = definitions.stream().collect(Collectors.toUnmodifiableMap(
                NodeDefinition::versionedTypeId, Function.identity()));
        this.limits = limits;
    }

    public List<ValidationIssue> validate(GraphDefinition graph) {
        List<ValidationIssue> issues = new ArrayList<>();
        if (graph.nodes().isEmpty()) {
            issues.add(error("GRAPH_EMPTY", "/nodes", null, "graph must contain at least one node"));
            return List.copyOf(issues);
        }
        if (graph.nodes().size() > limits.maxNodes()) {
            issues.add(error("NODE_LIMIT", "/nodes", null, "node count exceeds dialect limit"));
        }
        if (graph.edges().size() > limits.maxEdges()) {
            issues.add(error("EDGE_LIMIT", "/edges", null, "edge count exceeds dialect limit"));
        }

        Map<String, GraphNode> nodesById = uniqueNodes(graph, issues);
        int totalCost = validateNodeDefinitions(graph, issues);
        if (totalCost > limits.maxCost()) {
            issues.add(error("COST_LIMIT", "/nodes", null, "compiled graph cost exceeds limit"));
        }
        validateEdges(graph, nodesById, issues);
        validateReachability(graph, nodesById, issues);
        validateCycles(graph, nodesById, issues);
        return List.copyOf(issues);
    }

    private Map<String, GraphNode> uniqueNodes(GraphDefinition graph, List<ValidationIssue> issues) {
        Map<String, GraphNode> result = new HashMap<>();
        for (int index = 0; index < graph.nodes().size(); index++) {
            GraphNode node = graph.nodes().get(index);
            if (result.putIfAbsent(node.id(), node) != null) {
                issues.add(error("DUPLICATE_NODE_ID", "/nodes/" + index + "/id", node.id(), "node id must be unique"));
            }
        }
        return result;
    }

    private int validateNodeDefinitions(GraphDefinition graph, List<ValidationIssue> issues) {
        int cost = 0;
        for (int index = 0; index < graph.nodes().size(); index++) {
            GraphNode node = graph.nodes().get(index);
            NodeDefinition definition = registry.get(node.versionedTypeId());
            String pointer = "/nodes/" + index;
            if (definition == null) {
                issues.add(error("UNKNOWN_NODE_VERSION", pointer, node.id(), "node type/version is not registered"));
                continue;
            }
            cost = Math.addExact(cost, definition.costWeight());
            if (!definition.dialects().contains(graph.dialect())) {
                issues.add(error("DIALECT_NODE_FORBIDDEN", pointer, node.id(), "node is not allowed in this dialect"));
            }
            if (isPureDialect(graph.dialect()) && definition.sideEffect() != SideEffect.NONE) {
                issues.add(error("SIDE_EFFECT_FORBIDDEN", pointer, node.id(), "side effects are forbidden in a pure dialect"));
            }
            if (graph.dialect() == Dialect.JOURNEY_STATE_MACHINE && node.stableTypeId().startsWith("price.")) {
                issues.add(error("PRICE_ACTION_FORBIDDEN", pointer, node.id(), "journey cannot directly mutate price"));
            }
            if (definition.missingSemantics().name().equals("USE_DECLARED_DEFAULT")
                    && !node.config().containsKey("default")) {
                issues.add(error("MISSING_DEFAULT", pointer + "/config/default", node.id(), "declared default is required"));
            }
        }
        return cost;
    }

    private void validateEdges(
            GraphDefinition graph, Map<String, GraphNode> nodesById, List<ValidationIssue> issues) {
        Set<String> edgeIds = new HashSet<>();
        for (int index = 0; index < graph.edges().size(); index++) {
            GraphEdge edge = graph.edges().get(index);
            String pointer = "/edges/" + index;
            if (!edgeIds.add(edge.id())) {
                issues.add(error("DUPLICATE_EDGE_ID", pointer + "/id", null, "edge id must be unique"));
            }
            GraphNode source = nodesById.get(edge.sourceNodeId());
            GraphNode target = nodesById.get(edge.targetNodeId());
            if (source == null || target == null) {
                issues.add(error("DANGLING_EDGE", pointer, null, "edge endpoint does not exist"));
                continue;
            }
            NodeDefinition sourceType = registry.get(source.versionedTypeId());
            NodeDefinition targetType = registry.get(target.versionedTypeId());
            if (sourceType == null || targetType == null) {
                continue;
            }
            String outputType = sourceType.outputPorts().get(edge.sourcePort());
            String inputType = targetType.inputPorts().get(edge.targetPort());
            if (outputType == null || inputType == null) {
                issues.add(error("UNKNOWN_PORT", pointer, null, "edge references an unknown port"));
            } else if (!outputType.equals(inputType)) {
                issues.add(error("PORT_TYPE_MISMATCH", pointer, null, "edge port types are incompatible"));
            }
        }
    }

    private void validateReachability(
            GraphDefinition graph, Map<String, GraphNode> nodesById, List<ValidationIssue> issues) {
        Map<String, List<String>> outgoing = adjacency(graph, nodesById);
        Set<String> incoming = graph.edges().stream().map(GraphEdge::targetNodeId).collect(Collectors.toSet());
        Set<String> starts = nodesById.keySet().stream().filter(id -> !incoming.contains(id)).collect(Collectors.toSet());
        if (starts.isEmpty()) {
            issues.add(error("NO_ENTRY", "/nodes", null, "graph has no entry node"));
            return;
        }
        Set<String> reached = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>(starts);
        while (!queue.isEmpty()) {
            String current = queue.removeFirst();
            if (reached.add(current)) {
                queue.addAll(outgoing.getOrDefault(current, List.of()));
            }
        }
        nodesById.keySet().stream().filter(id -> !reached.contains(id)).sorted().forEach(id ->
                issues.add(error("UNREACHABLE_NODE", "/nodes", id, "node is unreachable from an entry")));
    }

    private void validateCycles(
            GraphDefinition graph, Map<String, GraphNode> nodesById, List<ValidationIssue> issues) {
        Map<String, List<String>> outgoing = adjacency(graph, nodesById);
        Set<String> visiting = new HashSet<>();
        Set<String> visited = new HashSet<>();
        Deque<String> path = new ArrayDeque<>();
        for (String nodeId : nodesById.keySet()) {
            findCycles(nodeId, graph, nodesById, outgoing, visiting, visited, path, issues);
        }
    }

    private void findCycles(
            String nodeId,
            GraphDefinition graph,
            Map<String, GraphNode> nodesById,
            Map<String, List<String>> outgoing,
            Set<String> visiting,
            Set<String> visited,
            Deque<String> path,
            List<ValidationIssue> issues) {
        if (visited.contains(nodeId)) {
            return;
        }
        if (!visiting.add(nodeId)) {
            List<GraphNode> cycle = path.stream().map(nodesById::get).toList();
            if (!isAllowedBoundedJourneyCycle(graph.dialect(), cycle)) {
                issues.add(error("UNBOUNDED_CYCLE", "/edges", nodeId, "cycle is forbidden or lacks bounded-repeat limits"));
            }
            return;
        }
        path.addLast(nodeId);
        for (String next : outgoing.getOrDefault(nodeId, List.of())) {
            findCycles(next, graph, nodesById, outgoing, visiting, visited, path, issues);
        }
        path.removeLast();
        visiting.remove(nodeId);
        visited.add(nodeId);
    }

    private static boolean isAllowedBoundedJourneyCycle(Dialect dialect, List<GraphNode> cycle) {
        if (dialect != Dialect.JOURNEY_STATE_MACHINE) {
            return false;
        }
        return cycle.stream().filter(node -> node.stableTypeId().equals("control.bounded-repeat")).anyMatch(node -> {
            try {
                return Integer.parseInt(node.config().getOrDefault("maxIterations", "0")) > 0
                        && node.config().containsKey("deadline");
            } catch (NumberFormatException invalid) {
                return false;
            }
        });
    }

    private static Map<String, List<String>> adjacency(GraphDefinition graph, Map<String, GraphNode> nodesById) {
        Map<String, List<String>> outgoing = new HashMap<>();
        for (GraphEdge edge : graph.edges()) {
            if (nodesById.containsKey(edge.sourceNodeId()) && nodesById.containsKey(edge.targetNodeId())) {
                outgoing.computeIfAbsent(edge.sourceNodeId(), ignored -> new ArrayList<>()).add(edge.targetNodeId());
            }
        }
        return outgoing;
    }

    private static boolean isPureDialect(Dialect dialect) {
        return dialect == Dialect.OFFER_DECISION_DAG
                || dialect == Dialect.AUDIENCE_EXPRESSION
                || dialect == Dialect.DMN_DECISION_TABLE;
    }

    private static ValidationIssue error(String code, String pointer, String nodeId, String message) {
        return new ValidationIssue(ValidationIssue.Severity.ERROR, code, pointer, nodeId, message);
    }

    public record Limits(int maxNodes, int maxEdges, int maxCost) {
        public Limits {
            if (maxNodes <= 0 || maxEdges < 0 || maxCost <= 0) {
                throw new IllegalArgumentException("graph limits must be positive");
            }
        }

        public static Limits productionDefaults() {
            return new Limits(500, 1_000, 10_000);
        }
    }
}
