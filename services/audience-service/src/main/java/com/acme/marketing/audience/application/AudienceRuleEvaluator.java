package com.acme.marketing.audience.application;

import com.acme.marketing.audience.application.AudienceService.Condition;
import com.acme.marketing.audience.application.AudienceService.Operator;
import com.acme.marketing.audience.application.AudienceService.ValueType;
import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/** 字段类型同时约束规则录入和求值，避免合法日期落入数字比较或非法组合延迟到运行时失败。 */
final class AudienceRuleEvaluator {
    private static final Set<Operator> ORDERED_OPERATORS = Set.of(Operator.GT, Operator.GTE, Operator.LT, Operator.LTE);
    private static final String INVALID_COMBINATION = "人群字段类型不支持该运算符";
    private static final String INVALID_LITERAL = "人群条件值与字段类型不匹配";
    private static final String EMPTY_SET = "IN 条件必须包含非空成员";
    private static final String INVALID_BOOLEAN = "布尔字段只接受 true 或 false";

    private AudienceRuleEvaluator() { }

    /** 保存及预览均校验规则，旧存量非法规则也返回受控校验错误。 */
    static void validate(ValueType type, Condition condition) {
        if (type == null || condition.operator() == null
                || (ORDERED_OPERATORS.contains(condition.operator()) && type != ValueType.DECIMAL && type != ValueType.INSTANT)) {
            throw new IllegalArgumentException(INVALID_COMBINATION);
        }
        try {
            for (String value : literals(condition)) compare(type, value, value);
        } catch (IllegalArgumentException | DateTimeException invalid) {
            throw new IllegalArgumentException(INVALID_LITERAL, invalid);
        }
    }

    /** 缺失或格式错误的事实不能授予人群资格；不会把单个坏样本升级为整批执行异常。 */
    static boolean matches(ValueType type, Condition condition, String actual) {
        if (actual == null) return false;
        try {
            if (condition.operator() == Operator.IN) {
                return literals(condition).stream().anyMatch(value -> compare(type, actual, value) == 0);
            }
            int comparison = compare(type, actual, condition.value());
            return switch (condition.operator()) {
                case EQ -> comparison == 0;
                case NE -> comparison != 0;
                case GT -> comparison > 0;
                case GTE -> comparison >= 0;
                case LT -> comparison < 0;
                case LTE -> comparison <= 0;
                case IN -> throw new IllegalStateException(INVALID_COMBINATION);
            };
        } catch (IllegalArgumentException | DateTimeException invalidFact) {
            return false;
        }
    }

    private static List<String> literals(Condition condition) {
        if (condition.value() == null) throw new IllegalArgumentException(INVALID_LITERAL);
        if (condition.operator() != Operator.IN) return List.of(condition.value());
        List<String> values = Arrays.stream(condition.value().split(",", -1)).map(String::trim).toList();
        if (values.stream().anyMatch(String::isEmpty)) throw new IllegalArgumentException(EMPTY_SET);
        return values;
    }

    private static int compare(ValueType type, String left, String right) {
        return switch (type) {
            case STRING -> left.compareTo(right);
            case DECIMAL -> new BigDecimal(left).compareTo(new BigDecimal(right));
            case INSTANT -> Instant.parse(left).compareTo(Instant.parse(right));
            case BOOLEAN -> Boolean.compare(booleanValue(left), booleanValue(right));
        };
    }

    private static boolean booleanValue(String value) {
        if ("true".equalsIgnoreCase(value)) return true;
        if ("false".equalsIgnoreCase(value)) return false;
        throw new IllegalArgumentException(INVALID_BOOLEAN);
    }
}
