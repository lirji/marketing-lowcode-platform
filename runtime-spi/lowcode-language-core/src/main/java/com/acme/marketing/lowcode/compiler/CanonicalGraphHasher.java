package com.acme.marketing.lowcode.compiler;

import com.acme.marketing.lowcode.model.GraphDefinition;
import com.acme.marketing.lowcode.model.GraphEdge;
import com.acme.marketing.lowcode.model.GraphNode;
import com.acme.marketing.platform.crypto.Digests;
import java.text.Normalizer;
import java.util.Comparator;
import java.util.Map;
import java.util.TreeMap;

/** 共享图语义摘要；裂变使用无歧义编码，旧方言保留历史摘要以兼容已签名产物。 */
public final class CanonicalGraphHasher {
    /** 计算语义摘要；REFERRAL_POLICY 的原始字符串精确参与编码，不把 Unicode 不同标识归一化。 */
    public String semanticHash(GraphDefinition graph) {
        if (graph.dialect() == com.acme.marketing.lowcode.model.Dialect.REFERRAL_POLICY)
            return referralHash(graph);
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

    private static String referralHash(GraphDefinition graph) {
        try {
            var bytes = new java.io.ByteArrayOutputStream();
            var out = new java.io.DataOutputStream(bytes);
            // 版本域隔离 + UTF-8 字节长度前缀，字段内的分号/等号/换行不能伪造下一个字段。
            field(out, "marketing-referral-graph-semantic/1");
            field(out, graph.definitionId());
            field(out, graph.dialect().name());
            field(out, graph.dialectVersion());
            var nodes = graph.nodes().stream().sorted(Comparator.comparing(GraphNode::id)).toList();
            out.writeInt(nodes.size());
            for (var node : nodes) {
                field(out, node.id()); field(out, node.stableTypeId()); field(out, node.semanticVersion());
                fields(out, node.config());
            }
            var edges = graph.edges().stream().sorted(Comparator.comparing(GraphEdge::id)).toList();
            out.writeInt(edges.size());
            for (var edge : edges) {
                field(out, edge.id()); field(out, edge.sourceNodeId()); field(out, edge.sourcePort());
                field(out, edge.targetNodeId()); field(out, edge.targetPort());
            }
            fields(out, graph.variables());
            out.flush();
            return "sha256:" + Digests.sha256Hex(bytes.toByteArray());
        } catch (java.io.IOException impossible) {
            throw new IllegalStateException("内存语义编码失败", impossible);
        }
    }

    private static void fields(java.io.DataOutputStream out, Map<String, String> values) throws java.io.IOException {
        out.writeInt(values.size());
        for (var entry : new TreeMap<>(values).entrySet()) {
            field(out, entry.getKey()); field(out, entry.getValue());
        }
    }

    private static void field(java.io.DataOutputStream out, String value) throws java.io.IOException {
        // Java 字符串可含孤立代理项；UTF-8 默认替换会让不同原字符串变成同一摘要，必须拒绝。
        if (!java.nio.charset.StandardCharsets.UTF_8.newEncoder().canEncode(value))
            throw new IllegalArgumentException("裂变语义字段含非法 Unicode 代理项");
        byte[] bytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
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
