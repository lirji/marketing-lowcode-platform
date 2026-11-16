package com.acme.marketing.compiler.application;

import static com.acme.marketing.compiler.application.DmnWorkbenchErrors.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 只把已验证的有限语法转换成 DMN XML，客户端不能提交 XML、函数或任意 FEEL。 */
final class DmnWorkbenchInput {
    static final String MODEL = "DmnWorkbenchModel";
    static final String DECISION = "DmnWorkbenchDecision";
    static final String NAMESPACE = "urn:marketing:dmn:workbench";
    private static final int MAX_COLUMNS = 4;
    private static final int MAX_RULES = 32;
    private static final int MAX_CELL = 256;
    private static final Pattern NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,31}");
    private static final Pattern NUMBER = Pattern.compile("-?[0-9]+(?:\\.[0-9]+)?");
    private static final Pattern COMPARISON = Pattern.compile("^(<=|>=|<|>|=)?\\s*(-?[0-9]+(?:\\.[0-9]+)?)$");
    private static final Set<String> RESERVED = Set.of(MODEL, DECISION, "true", "false", "null", "if", "then", "else",
            "for", "return", "some", "every", "satisfies", "in", "and", "or", "not", "between", "instance", "of", "function");
    private final ObjectMapper mapper;

    DmnWorkbenchInput(ObjectMapper mapper) { this.mapper = mapper; }

    /** 原始 JSON 上验证节点类型，避免 Jackson 将数字/布尔隐式转换成字符串后放行。 */
    Prepared read(JsonNode request, boolean evaluating) {
        object(request, evaluating ? Set.of("table", "context") : Set.of("table"), REQUEST_INVALID);
        JsonNode table = request.get("table");
        object(table, Set.of("hitPolicy", "inputs", "outputs", "rules"), REQUEST_INVALID);
        String hitPolicy = text(table.get("hitPolicy"), HIT_POLICY_INVALID);
        if (!Set.of("UNIQUE", "FIRST").contains(hitPolicy)) throw HIT_POLICY_INVALID.invalid();
        Set<String> names = new HashSet<>();
        List<Column> inputs = columns(table.get("inputs"), names);
        List<Column> outputs = columns(table.get("outputs"), names);
        JsonNode rules = table.get("rules");
        if (rules == null || !rules.isArray() || rules.isEmpty() || rules.size() > MAX_RULES) throw TABLE_LIMIT.invalid();
        StringBuilder xml = new StringBuilder("""
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="https://www.omg.org/spec/DMN/20230324/MODEL/" id="workbench" name="DmnWorkbenchModel" namespace="urn:marketing:dmn:workbench">
                """);
        for (int i = 0; i < inputs.size(); i++) {
            Column column = inputs.get(i);
            xml.append("<inputData id=\"input_").append(i).append("\" name=\"").append(column.name())
                    .append("\"><variable id=\"input_var_").append(i).append("\" name=\"").append(column.name())
                    .append("\" typeRef=\"").append(column.type().code).append("\"/></inputData>");
        }
        xml.append("<decision id=\"decision\" name=\"").append(DECISION).append("\"><variable id=\"result\" name=\"")
                .append(DECISION).append("\"");
        if (outputs.size() == 1) xml.append(" typeRef=\"").append(outputs.getFirst().type().code).append("\"");
        xml.append("/>");
        for (int i = 0; i < inputs.size(); i++) xml.append("<informationRequirement><requiredInput href=\"#input_").append(i).append("\"/></informationRequirement>");
        xml.append("<decisionTable id=\"table\" hitPolicy=\"").append(hitPolicy).append("\">");
        for (int i = 0; i < inputs.size(); i++) {
            Column column = inputs.get(i);
            xml.append("<input id=\"clause_").append(i).append("\"><inputExpression id=\"expression_").append(i)
                    .append("\" typeRef=\"").append(column.type().code).append("\"><text>").append(column.name())
                    .append("</text></inputExpression></input>");
        }
        for (int i = 0; i < outputs.size(); i++) {
            xml.append("<output id=\"output_").append(i).append('"');
            // 单输出由 decision.variable 指定名称与类型；重复声明会触发 DMN 标准告警。
            if (outputs.size() > 1) xml.append(" name=\"").append(outputs.get(i).name())
                    .append("\" typeRef=\"").append(outputs.get(i).type().code).append('"');
            xml.append("/>");
        }
        int index = 0;
        for (JsonNode rule : rules) {
            object(rule, Set.of("inputs", "outputs"), RULE_INVALID);
            xml.append("<rule id=\"rule_").append(index++).append("\">");
            entries(xml, rule.get("inputs"), inputs, true);
            entries(xml, rule.get("outputs"), outputs, false);
            xml.append("</rule>");
        }
        xml.append("</decisionTable></decision></definitions>");
        return new Prepared(xml.toString(), evaluating ? context(request.get("context"), inputs) : Map.of());
    }

    private List<Column> columns(JsonNode values, Set<String> names) {
        if (values == null || !values.isArray() || values.isEmpty() || values.size() > MAX_COLUMNS) throw TABLE_LIMIT.invalid();
        List<Column> result = new ArrayList<>();
        for (JsonNode value : values) {
            object(value, Set.of("name", "type"), REQUEST_INVALID);
            String name = text(value.get("name"), NAME_INVALID);
            if (!NAME.matcher(name).matches() || RESERVED.contains(name) || !names.add(name)) throw NAME_INVALID.invalid();
            result.add(new Column(name, ValueType.parse(text(value.get("type"), TYPE_INVALID))));
        }
        return List.copyOf(result);
    }

    private void entries(StringBuilder xml, JsonNode values, List<Column> columns, boolean input) {
        if (values == null || !values.isArray() || values.size() != columns.size()) throw RULE_INVALID.invalid();
        for (int i = 0; i < columns.size(); i++) {
            String value = text(values.get(i), CELL_INVALID);
            if (value.length() > MAX_CELL) throw CELL_INVALID.invalid();
            String expression = literal(value.trim(), columns.get(i).type(), input);
            String tag = input ? "inputEntry" : "outputEntry";
            xml.append('<').append(tag).append("><text>").append(escape(expression)).append("</text></").append(tag).append('>');
        }
    }

    private String literal(String value, ValueType type, boolean input) {
        if (input && value.equals("-")) return value;
        if (type == ValueType.NUMBER && input) {
            var comparison = COMPARISON.matcher(value);
            if (!comparison.matches()) throw CELL_INVALID.invalid();
            // FEEL 单值测试本身表示等于，界面允许的“=”在生成阶段规范化掉。
            return (comparison.group(1) == null || comparison.group(1).equals("=") ? "" : comparison.group(1) + " ")
                    + number(comparison.group(2)).toPlainString();
        }
        if (input && value.startsWith("=")) value = value.substring(1).trim();
        return switch (type) {
            case NUMBER -> number(value).toPlainString();
            case BOOLEAN -> {
                if (!value.equals("true") && !value.equals("false")) throw CELL_INVALID.invalid();
                yield value;
            }
            case STRING -> {
                try {
                    JsonNode string = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(value);
                    if (string == null || !string.isString()) throw CELL_INVALID.invalid();
                    yield mapper.writeValueAsString(string.asString());
                } catch (tools.jackson.core.JacksonException invalid) {
                    throw CELL_INVALID.invalid();
                }
            }
        };
    }

    private Map<String, Object> context(JsonNode values, List<Column> inputs) {
        Set<String> expected = new HashSet<>(); inputs.forEach(column -> expected.add(column.name()));
        object(values, expected, CONTEXT_INVALID);
        Map<String, Object> context = new LinkedHashMap<>();
        for (Column input : inputs) {
            String value = text(values.get(input.name()), CONTEXT_INVALID);
            if (value.length() > MAX_CELL) throw CONTEXT_INVALID.invalid();
            Object parsed = switch (input.type()) {
                case NUMBER -> number(value);
                case STRING -> value;
                case BOOLEAN -> {
                    if (!value.equals("true") && !value.equals("false")) throw CONTEXT_INVALID.invalid();
                    yield Boolean.valueOf(value);
                }
            };
            context.put(input.name(), parsed);
        }
        return Map.copyOf(context);
    }

    private static BigDecimal number(String value) {
        if (!NUMBER.matcher(value).matches()) throw NUMBER_INVALID.invalid();
        int point = value.indexOf('.');
        int digits = value.length() - (value.startsWith("-") ? 1 : 0) - (point >= 0 ? 1 : 0);
        if (digits > 18 || point >= 0 && value.length() - point - 1 > 6) throw NUMBER_INVALID.invalid();
        return new BigDecimal(value);
    }

    private static String text(JsonNode value, DmnWorkbenchErrors error) {
        if (value == null || !value.isString()) throw error.invalid();
        return value.asString();
    }

    private static void object(JsonNode node, Set<String> fields, DmnWorkbenchErrors error) {
        if (node == null || !node.isObject() || node.size() != fields.size()) throw error.invalid();
        for (String field : fields) if (!node.has(field)) throw error.invalid();
    }

    private static String escape(String value) { return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"); }
    private record Column(String name, ValueType type) { }
    record Prepared(String xml, Map<String, Object> context) { }

    private enum ValueType {
        NUMBER("number"), STRING("string"), BOOLEAN("boolean");
        private final String code;
        ValueType(String code) { this.code = code; }
        static ValueType parse(String value) {
            for (ValueType type : values()) if (type.code.equals(value)) return type;
            throw TYPE_INVALID.invalid();
        }
    }
}
