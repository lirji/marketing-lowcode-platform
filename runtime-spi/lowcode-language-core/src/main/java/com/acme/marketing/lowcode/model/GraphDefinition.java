package com.acme.marketing.lowcode.model;

import java.util.List;
import java.util.Map;

public record GraphDefinition(
        String definitionId,
        Dialect dialect,
        String dialectVersion,
        List<GraphNode> nodes,
        List<GraphEdge> edges,
        Map<String, String> variables,
        Map<String, String> annotations) {
    public GraphDefinition {
        if (definitionId == null || definitionId.isBlank()) {
            throw new IllegalArgumentException("definitionId is required");
        }
        if (dialect == null || dialectVersion == null || dialectVersion.isBlank()) {
            throw new IllegalArgumentException("dialect and version are required");
        }
        nodes = List.copyOf(nodes == null ? List.of() : nodes);
        edges = List.copyOf(edges == null ? List.of() : edges);
        variables = Map.copyOf(variables == null ? Map.of() : variables);
        annotations = Map.copyOf(annotations == null ? Map.of() : annotations);
    }
}
