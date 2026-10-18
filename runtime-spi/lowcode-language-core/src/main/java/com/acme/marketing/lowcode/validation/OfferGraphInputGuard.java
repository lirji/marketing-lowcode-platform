package com.acme.marketing.lowcode.validation;

import com.acme.marketing.lowcode.model.Dialect;
import com.acme.marketing.lowcode.model.GraphDefinition;
import com.acme.marketing.lowcode.model.GraphNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 策略 ABI 尚无任意事实谓词，不能把包含条件的图降级为无条件优惠。 */
public final class OfferGraphInputGuard {
    private static final String START = "offer.start";
    private static final String END = "offer.end";
    private static final String FIXED = "offer.fixed";
    private static final String PERCENTAGE = "offer.percentage";
    private static final String NEXT_PORT = "next";
    private static final String INPUT_PORT = "in";
    private static final Set<String> OFFERS = Set.of(FIXED, PERCENTAGE);
    private static final Set<String> SUPPORTED = Set.of(START, END, FIXED, PERCENTAGE);
    private static final String RATE = "discountBasisPoints";
    private static final String LEGACY_RATE = "basisPoints";
    private static final int MAX_BASIS_POINTS = 10_000;

    private OfferGraphInputGuard() { }

    /** 图和参数的支持边界由预览、校验与编译共同使用。 */
    public static List<ValidationIssue> validate(GraphDefinition graph) {
        List<ValidationIssue> issues = new ArrayList<>();
        if (graph == null || graph.dialect() != Dialect.OFFER_DECISION_DAG) {
            return List.of(issue(Problem.DIALECT, null));
        }
        Map<String, GraphNode> nodes = new HashMap<>();
        for (GraphNode node : graph.nodes()) {
            if (nodes.putIfAbsent(node.id(), node) != null) issues.add(issue(Problem.TOPOLOGY, node.id()));
            if (!SUPPORTED.contains(node.stableTypeId())) issues.add(issue(Problem.UNSUPPORTED_NODE, node.id()));
            if (PERCENTAGE.equals(node.stableTypeId())) {
                try { discountBasisPoints(node.config()); }
                catch (IllegalArgumentException invalid) { issues.add(issue(Problem.RATE, node.id())); }
            }
        }
        // 既有 ABI 支持无连线的策略集合；有控制流时仅支持覆盖全部节点的单条链。
        boolean policySet = !graph.nodes().isEmpty() && graph.edges().isEmpty()
                && graph.nodes().stream().allMatch(node -> OFFERS.contains(node.stableTypeId()));
        boolean emptyDraft = graph.nodes().size() == 1 && graph.edges().isEmpty()
                && Set.of(START, END).contains(graph.nodes().getFirst().stableTypeId());
        if (!policySet && !emptyDraft && !linear(graph, nodes)) issues.add(issue(Problem.TOPOLOGY, null));
        return List.copyOf(issues);
    }

    /** 直接编译/预览入口不能依赖此前保存过的 VALIDATED 状态。 */
    public static void requireSupported(GraphDefinition graph) {
        var issues = validate(graph);
        if (!issues.isEmpty()) throw new IllegalArgumentException(issues.getFirst().code() + ": " + issues.getFirst().message());
    }

    /** 兼容旧字段；两种字段同时存在时必须数值相同，防止预览和发布各取一份。 */
    public static int discountBasisPoints(Map<String, String> config) {
        try {
            int current = Integer.parseInt(config.getOrDefault(RATE, config.getOrDefault(LEGACY_RATE, "0")));
            if (current < 0 || current > MAX_BASIS_POINTS
                    || (config.containsKey(LEGACY_RATE) && Integer.parseInt(config.get(LEGACY_RATE)) != current)) {
                throw new IllegalArgumentException(Problem.RATE.message);
            }
            return current;
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException(Problem.RATE.message, invalid);
        }
    }

    private static boolean linear(GraphDefinition graph, Map<String, GraphNode> nodes) {
        var starts = graph.nodes().stream().filter(node -> START.equals(node.stableTypeId())).toList();
        if (starts.size() != 1 || graph.nodes().stream().filter(node -> END.equals(node.stableTypeId())).count() != 1) return false;
        Map<String, String> next = new HashMap<>();
        for (var edge : graph.edges()) {
            GraphNode source = nodes.get(edge.sourceNodeId());
            GraphNode target = nodes.get(edge.targetNodeId());
            if (source == null || target == null || END.equals(source.stableTypeId()) || START.equals(target.stableTypeId())
                    || !NEXT_PORT.equals(edge.sourcePort()) || !INPUT_PORT.equals(edge.targetPort())
                    || next.putIfAbsent(source.id(), target.id()) != null) return false;
        }
        Set<String> visited = new HashSet<>();
        String current = starts.getFirst().id();
        while (current != null && visited.add(current)) {
            if (END.equals(nodes.get(current).stableTypeId())) return visited.size() == nodes.size();
            current = next.get(current);
        }
        return false;
    }

    private static ValidationIssue issue(Problem problem, String nodeId) {
        return new ValidationIssue(ValidationIssue.Severity.ERROR, problem.code, "/nodes", nodeId, problem.message);
    }

    private enum Problem {
        DIALECT("OFFER_DIALECT_REQUIRED", "需要优惠决策图"),
        UNSUPPORTED_NODE("OFFER_NODE_NOT_EXECUTABLE", "当前策略运行时不支持此节点，不能忽略条件后发布"),
        TOPOLOGY("OFFER_FLOW_NOT_EXECUTABLE", "优惠图必须是无连线的策略集合或完整单链，不能忽略分支、孤立节点或环"),
        RATE("OFFER_PERCENTAGE_INVALID", "折扣基点必须在 0 到 10000 之间，兼容字段同时出现时必须相等");
        private final String code;
        private final String message;
        Problem(String code, String message) { this.code = code; this.message = message; }
    }
}
