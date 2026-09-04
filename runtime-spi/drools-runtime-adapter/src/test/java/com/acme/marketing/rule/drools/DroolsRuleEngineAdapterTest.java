package com.acme.marketing.rule.drools;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.acme.marketing.decision.rule.RuleEngineAdapter;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DroolsRuleEngineAdapterTest {
    private static final String RULE = """
            package offer
            import com.acme.marketing.decision.rule.RuleFacts
            global java.util.List resultCodes
            rule "amount threshold"
            when
              $facts : RuleFacts( has("amount"), longValue("amount") >= 50000L )
            then
              resultCodes.add("AMOUNT_MATCHED");
            end
            """;

    @Test
    void compilesToSerializedKieBaseAndEvaluatesWithLimits() {
        DroolsRuleEngineAdapter adapter = new DroolsRuleEngineAdapter();
        RuleEngineAdapter.CompilationResult compilation = adapter.compile("offer", RULE, Duration.ofSeconds(5));

        assertTrue(compilation.valid(), () -> String.join("\n", compilation.messages()));
        assertTrue(adapter.evaluate(compilation.rules(), Map.of("amount", 50_000L), 10, Duration.ofSeconds(1)).matched());
        assertFalse(adapter.evaluate(compilation.rules(), Map.of("amount", 49_999L), 10, Duration.ofSeconds(1)).matched());
    }

    @Test
    void rejectsInvalidDrl() {
        RuleEngineAdapter.CompilationResult result = new DroolsRuleEngineAdapter()
                .compile("bad", "rule missing", Duration.ofSeconds(5));
        assertFalse(result.valid());
    }
}
