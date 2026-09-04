package com.acme.marketing.lowcode.compiler;

import com.acme.marketing.lowcode.model.GraphDefinition;
import com.acme.marketing.lowcode.model.GraphEdge;
import com.acme.marketing.lowcode.model.GraphNode;
import com.acme.marketing.platform.crypto.Digests;
import java.text.Normalizer;
import java.util.Comparator;
import java.util.Map;
import java.util.TreeMap;

public final class CanonicalGraphHasher {
    public String semanticHash(GraphDefinition graph) {
        StringBuilder canonical = new StringBuilder()
                .append(normalize(graph.definitionId())).append('|')
                .append(graph.dialect()).append('|').append(graph.dialectVersion()).append('\n');
        graph.nodes().stream().sorted(Comparator.comparing(GraphNode::id)).forEach(node -> {
            canonical.append("N|").append(node.id()).append('|').append(node.versionedTypeId()).append('|');
            semanticConfig(node.config()).forEach((key, value) -> canonical
                    .append(normalize(key)).append('=').append(normalize(value)).append(';'));
            canonical.append('\n');
        });
        graph.edges().stream().sorted(Comparator.comparing(GraphEdge::id)).forEach(edge -> canonical
                .append("E|").append(edge.id()).append('|').append(edge.sourceNodeId()).append('|')
                .append(edge.sourcePort()).append('|').append(edge.targetNodeId()).append('|')
                .append(edge.targetPort()).append('\n'));
        new TreeMap<>(graph.variables()).forEach((key, value) -> canonical
                .append("V|").append(normalize(key)).append('=').append(normalize(value)).append('\n'));
        return "sha256:" + Digests.sha256Hex(canonical.toString());
    }

    private static Map<String, String> semanticConfig(Map<String, String> config) {
        TreeMap<String, String> result = new TreeMap<>();
        config.forEach((key, value) -> {
            if (!key.startsWith("ui.")) {
                result.put(key, value);
            }
        });
        return result;
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFC);
    }
}
