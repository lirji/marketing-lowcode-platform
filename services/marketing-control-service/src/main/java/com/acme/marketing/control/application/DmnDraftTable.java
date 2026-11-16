package com.acme.marketing.control.application;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/** 可保存不完整编辑内容的有界草稿；保存不代表通过 DMN 语义分析。 */
public record DmnDraftTable(HitPolicy hitPolicy, List<Column> inputs, List<Column> outputs, List<Rule> rules) {
    private static final int MAX_COLUMNS = 4;
    private static final int MAX_RULES = 32;
    private static final int MAX_NAME = 32;
    private static final int MAX_CELL = 256;

    /** 仅保证结构可以恢复编辑；空名字、空单元格和语义问题交由分析入口处理。 */
    public DmnDraftTable {
        if (hitPolicy == null || inputs == null || outputs == null || rules == null) throw DmnDraftError.INPUT_INVALID.invalid();
        if (inputs.isEmpty() || outputs.isEmpty() || inputs.size() > MAX_COLUMNS || outputs.size() > MAX_COLUMNS
                || rules.isEmpty() || rules.size() > MAX_RULES) throw DmnDraftError.LIMIT_EXCEEDED.invalid();
        if (inputs.stream().anyMatch(java.util.Objects::isNull) || outputs.stream().anyMatch(java.util.Objects::isNull)
                || rules.stream().anyMatch(java.util.Objects::isNull)) throw DmnDraftError.INPUT_INVALID.invalid();
        inputs = List.copyOf(inputs); outputs = List.copyOf(outputs); rules = List.copyOf(rules);
        for (Rule rule : rules) if (rule.inputs().size() != inputs.size() || rule.outputs().size() != outputs.size())
            throw DmnDraftError.INPUT_INVALID.invalid();
    }

    /** 原始 JSON 验证避免把数字/布尔字段静默转换为字符串草稿。 */
    public static DmnDraftTable read(JsonNode node) {
        object(node, Set.of("hitPolicy", "inputs", "outputs", "rules"));
        List<Column> inputs = columns(node.get("inputs"));
        List<Column> outputs = columns(node.get("outputs"));
        JsonNode rows = node.get("rules");
        if (rows == null || !rows.isArray() || rows.isEmpty() || rows.size() > MAX_RULES) throw DmnDraftError.LIMIT_EXCEEDED.invalid();
        List<Rule> rules = new ArrayList<>();
        for (JsonNode row : rows) {
            object(row, Set.of("inputs", "outputs"));
            rules.add(new Rule(cells(row.get("inputs")), cells(row.get("outputs"))));
        }
        return new DmnDraftTable(HitPolicy.read(text(node.get("hitPolicy"))), inputs, outputs, rules);
    }

    /** 严格对象形状，允许前端草稿中的空串但不允许未知协议字段。 */
    public static void object(JsonNode node, Set<String> fields) {
        if (node == null || !node.isObject() || node.size() != fields.size()) throw DmnDraftError.INPUT_INVALID.invalid();
        for (String field : fields) if (!node.has(field)) throw DmnDraftError.INPUT_INVALID.invalid();
    }

    private static List<Column> columns(JsonNode node) {
        if (node == null || !node.isArray() || node.isEmpty() || node.size() > MAX_COLUMNS) throw DmnDraftError.LIMIT_EXCEEDED.invalid();
        List<Column> result = new ArrayList<>();
        for (JsonNode value : node) {
            object(value, Set.of("name", "type"));
            result.add(new Column(text(value.get("name")), ColumnType.read(text(value.get("type")))));
        }
        return result;
    }
    private static List<String> cells(JsonNode node) {
        if (node == null || !node.isArray() || node.isEmpty() || node.size() > MAX_COLUMNS) throw DmnDraftError.LIMIT_EXCEEDED.invalid();
        List<String> result = new ArrayList<>();
        for (JsonNode value : node) result.add(text(value));
        return result;
    }
    private static String text(JsonNode node) {
        if (node == null || !node.isString()) throw DmnDraftError.INPUT_INVALID.invalid();
        return node.asString();
    }
    /** 编辑列保存原名称，防止草稿往返时自动纠正导致内容变化。 */
    public record Column(String name, ColumnType type) {
        /** 字段名允许空白草稿，类型必须为协议支持的稳定 code。 */
        public Column {
            if (name == null || type == null) throw DmnDraftError.INPUT_INVALID.invalid();
            if (name.length() > MAX_NAME) throw DmnDraftError.LIMIT_EXCEEDED.invalid();
        }
    }
    /** 规则单元格有序保存，语义合法性由显式分析决定。 */
    public record Rule(List<String> inputs, List<String> outputs) {
        /** 保留字面量原样及规则顺序，避免保存改变 FIRST 规则的行为。 */
        public Rule {
            if (inputs == null || outputs == null) throw DmnDraftError.INPUT_INVALID.invalid();
            for (List<String> values : List.of(inputs, outputs)) for (String value : values) {
                if (value == null) throw DmnDraftError.INPUT_INVALID.invalid();
                if (value.length() > MAX_CELL) throw DmnDraftError.LIMIT_EXCEEDED.invalid();
            }
            inputs = List.copyOf(inputs); outputs = List.copyOf(outputs);
        }
    }
    /** 使用稳定协议码，避免枚举重命名改变历史草稿内容。 */
    public enum HitPolicy {
        UNIQUE("UNIQUE"), FIRST("FIRST");
        private final String code;
        HitPolicy(String code) { this.code = code; }
        /** 持久化和响应均使用显式协议码。 */
        @JsonValue public String code() { return code; }
        /** 拒绝未知策略，避免恢复时默认为另一种命中语义。 */
        @JsonCreator public static HitPolicy read(String value) {
            for (HitPolicy item : values()) if (item.code.equals(value)) return item;
            throw DmnDraftError.INPUT_INVALID.invalid();
        }
    }
    /** 草稿列类型与计算接口保持相同的有限集合。 */
    public enum ColumnType {
        NUMBER("number"), STRING("string"), BOOLEAN("boolean");
        private final String code;
        ColumnType(String code) { this.code = code; }
        /** 持久化与计算接口共同使用的小写协议码。 */
        @JsonValue public String code() { return code; }
        /** 不通过枚举名或展示文案猜测类型。 */
        @JsonCreator public static ColumnType read(String value) {
            for (ColumnType item : values()) if (item.code.equals(value)) return item;
            throw DmnDraftError.INPUT_INVALID.invalid();
        }
    }
}
