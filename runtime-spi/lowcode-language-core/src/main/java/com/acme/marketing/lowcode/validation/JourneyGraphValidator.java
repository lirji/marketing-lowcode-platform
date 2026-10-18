package com.acme.marketing.lowcode.validation;

import com.acme.marketing.lowcode.model.GraphDefinition;
import com.acme.marketing.lowcode.model.GraphNode;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 运行时必需路由的唯一校验入口，防止控制面通过而编译制品在特定分支才失败。 */
public final class JourneyGraphValidator {
    private static final String TRIGGER = "journey.trigger";
    private static final Map<String, Set<String>> REQUIRED_PORTS = Map.of(
            TRIGGER, Set.of("next"), "journey.condition", Set.of("true", "false"),
            "journey.wait-event", Set.of("matched"), "journey.wait-timer", Set.of("elapsed"),
            "journey.send", Set.of("next"), "journey.grant", Set.of("next"),
            "journey.webhook", Set.of("next"), "journey.repeat", Set.of("repeat", "exit"),
            "journey.end", Set.of());

    private JourneyGraphValidator() { }

    /** 校验实际执行所需的结构；编译器直接入口同样调用，不依赖调用方先做 UI 校验。 */
    public static List<ValidationIssue> validate(GraphDefinition graph) {
        List<ValidationIssue> issues = new ArrayList<>();
        Map<String, GraphNode> nodes = new HashMap<>();
        Map<String, Map<String, String>> routes = new HashMap<>();
        List<String> triggers = new ArrayList<>();
        for (GraphNode node : graph.nodes()) {
            if (nodes.putIfAbsent(node.id(), node) != null) add(issues, Problem.DUPLICATE_NODE, node.id());
            if (!REQUIRED_PORTS.containsKey(node.stableTypeId())) add(issues, Problem.UNKNOWN_NODE, node.id());
            if (TRIGGER.equals(node.stableTypeId())) triggers.add(node.id());
        }
        if (triggers.size() != 1) add(issues, Problem.TRIGGER_COUNT, null);
        for (var edge : graph.edges()) {
            GraphNode source = nodes.get(edge.sourceNodeId());
            GraphNode target = nodes.get(edge.targetNodeId());
            if (source == null || target == null) {
                add(issues, Problem.DANGLING_EDGE, edge.sourceNodeId());
                continue;
            }
            if (!"in".equals(edge.targetPort()) || TRIGGER.equals(target.stableTypeId())) {
                add(issues, Problem.TARGET_PORT, target.id());
            }
            if (!REQUIRED_PORTS.getOrDefault(source.stableTypeId(), Set.of()).contains(edge.sourcePort())) {
                add(issues, Problem.OUTPUT_PORT, source.id());
            }
            String previous = routes.computeIfAbsent(source.id(), ignored -> new HashMap<>())
                    .putIfAbsent(edge.sourcePort(), target.id());
            if (previous != null) add(issues, Problem.DUPLICATE_ROUTE, source.id());
        }
        for (GraphNode node : graph.nodes()) {
            if (!routes.getOrDefault(node.id(), Map.of()).keySet()
                    .containsAll(REQUIRED_PORTS.getOrDefault(node.stableTypeId(), Set.of()))) {
                add(issues, Problem.MISSING_ROUTE, node.id());
            }
        }
        if (triggers.size() == 1) {
            Set<String> reached = new HashSet<>();
            var pending = new ArrayDeque<String>();
            pending.add(triggers.getFirst());
            while (!pending.isEmpty()) {
                String nodeId = pending.removeFirst();
                if (reached.add(nodeId)) pending.addAll(routes.getOrDefault(nodeId, Map.of()).values());
            }
            for (GraphNode node : graph.nodes()) {
                if (!reached.contains(node.id())) add(issues, Problem.UNREACHABLE_NODE, node.id());
            }
        }
        return List.copyOf(issues);
    }

    private static void add(List<ValidationIssue> issues, Problem problem, String nodeId) {
        issues.add(new ValidationIssue(ValidationIssue.Severity.ERROR, problem.code, "/edges", nodeId,
                problem.message));
    }

    /** 稳定校验码和提示集中维护，API 与编译报告复用同一语义。 */
    private enum Problem {
        DUPLICATE_NODE("JOURNEY_DUPLICATE_NODE", "旅程节点 ID 不能重复"),
        UNKNOWN_NODE("JOURNEY_UNKNOWN_NODE", "旅程包含不支持的节点类型"),
        TRIGGER_COUNT("JOURNEY_TRIGGER_COUNT", "旅程必须且只能包含一个触发节点"),
        DANGLING_EDGE("JOURNEY_DANGLING_EDGE", "旅程连线的端点不存在"),
        TARGET_PORT("JOURNEY_TARGET_PORT", "旅程连线必须连接非触发节点的 in 端口"),
        OUTPUT_PORT("JOURNEY_OUTPUT_PORT", "旅程连线使用了不支持的出口"),
        DUPLICATE_ROUTE("JOURNEY_DUPLICATE_ROUTE", "旅程同一出口只能连接一个目标"),
        MISSING_ROUTE("JOURNEY_MISSING_ROUTE", "旅程节点缺少运行必需的出口连线"),
        UNREACHABLE_NODE("JOURNEY_UNREACHABLE_NODE", "旅程节点无法从触发节点到达");

        private final String code;
        private final String message;

        Problem(String code, String message) {
            this.code = code;
            this.message = message;
        }
    }
}
