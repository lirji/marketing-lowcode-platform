package com.acme.marketing.audience.application;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.acme.marketing.audience.application.AudienceService.Condition;
import com.acme.marketing.audience.application.AudienceService.Operator;
import com.acme.marketing.audience.application.AudienceService.ValueType;
import org.junit.jupiter.api.Test;

class AudienceRuleEvaluatorTest {
    @Test
    void comparesInstantsAndNumericEqualityByType() {
        var after = new Condition("at", Operator.GT, "2026-01-01T00:00:00Z");
        AudienceRuleEvaluator.validate(ValueType.INSTANT, after);
        assertTrue(AudienceRuleEvaluator.matches(ValueType.INSTANT, after, "2026-02-01T00:00:00Z"));
        assertFalse(AudienceRuleEvaluator.matches(ValueType.INSTANT, after, "2025-01-01T00:00:00Z"));
        assertTrue(AudienceRuleEvaluator.matches(ValueType.DECIMAL, new Condition("n", Operator.EQ, "1.0"), "1.00"));
        assertTrue(AudienceRuleEvaluator.matches(ValueType.BOOLEAN, new Condition("b", Operator.EQ, "TRUE"), "true"));
    }

    @Test
    void rejectsInvalidOperatorsAndLiteralMembersBeforeEvaluation() {
        assertThrows(IllegalArgumentException.class, () -> AudienceRuleEvaluator.validate(ValueType.STRING,
                new Condition("name", Operator.GT, "a")));
        assertThrows(IllegalArgumentException.class, () -> AudienceRuleEvaluator.validate(ValueType.BOOLEAN,
                new Condition("flag", Operator.GTE, "true")));
        assertThrows(IllegalArgumentException.class, () -> AudienceRuleEvaluator.validate(ValueType.DECIMAL,
                new Condition("n", Operator.IN, "1,invalid")));
        assertThrows(IllegalArgumentException.class, () -> AudienceRuleEvaluator.validate(ValueType.INSTANT,
                new Condition("at", Operator.EQ, "not-a-date")));
    }

    @Test
    void handlesTypedInDuplicatesAndMalformedFactsWithoutGrantingMembership() {
        var condition = new Condition("n", Operator.IN, "1, 1,2.0");
        AudienceRuleEvaluator.validate(ValueType.DECIMAL, condition);
        assertTrue(AudienceRuleEvaluator.matches(ValueType.DECIMAL, condition, "2.00"));
        assertFalse(AudienceRuleEvaluator.matches(ValueType.DECIMAL, condition, "garbage"));
        assertFalse(AudienceRuleEvaluator.matches(ValueType.DECIMAL, condition, null));
        assertFalse(AudienceRuleEvaluator.matches(ValueType.BOOLEAN,
                new Condition("flag", Operator.NE, "true"), "not-a-boolean"));
    }
}
