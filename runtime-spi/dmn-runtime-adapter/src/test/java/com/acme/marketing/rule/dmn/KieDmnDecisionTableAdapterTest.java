package com.acme.marketing.rule.dmn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.acme.marketing.decision.rule.DecisionTableAdapter;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;

class KieDmnDecisionTableAdapterTest {
    private static final String NAMESPACE = "https://acme.example/marketing/discount";
    private static final String DMN = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="https://www.omg.org/spec/DMN/20230324/MODEL/"
              id="definitions_discount" name="DiscountModel" namespace="https://acme.example/marketing/discount">
              <inputData id="input_amount" name="amount">
                <variable id="var_amount" name="amount" typeRef="number"/>
              </inputData>
              <decision id="decision_tier" name="DiscountTier">
                <variable id="var_tier" name="DiscountTier" typeRef="string"/>
                <informationRequirement><requiredInput href="#input_amount"/></informationRequirement>
                <decisionTable id="table_tier" hitPolicy="UNIQUE">
                  <input id="clause_amount"><inputExpression id="expr_amount" typeRef="number"><text>amount</text></inputExpression></input>
                  <output id="clause_tier" name="DiscountTier" typeRef="string"/>
                  <rule id="rule_low"><inputEntry id="input_low"><text>&lt; 500</text></inputEntry><outputEntry id="output_low"><text>"NONE"</text></outputEntry></rule>
                  <rule id="rule_high"><inputEntry id="input_high"><text>&gt;= 500</text></inputEntry><outputEntry id="output_high"><text>"VIP"</text></outputEntry></rule>
                </decisionTable>
              </decision>
            </definitions>
            """;

    @Test
    void analyzesCompilesAndEvaluatesDmn() {
        KieDmnDecisionTableAdapter adapter = new KieDmnDecisionTableAdapter();
        assertTrue(adapter.analyze(NAMESPACE, DMN).valid());
        DecisionTableAdapter.Compilation compilation = adapter.compile(NAMESPACE, "DiscountModel", DMN);
        assertTrue(compilation.valid(), () -> String.join("\n", compilation.messages()));

        DecisionTableAdapter.Evaluation result = adapter.evaluate(
                compilation.table(), "DiscountTier", Map.of("amount", BigDecimal.valueOf(800)));
        assertTrue(result.success(), () -> String.join("\n", result.messages()));
        assertEquals("VIP", result.results().get("DiscountTier"));
    }
}
