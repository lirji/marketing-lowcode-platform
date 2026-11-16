package com.acme.marketing.rule.dmn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;

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

    @Test
    void compiledBytesRemainUsableAfterGeneratedModuleIsRemoved() {
        var adapter = new KieDmnDecisionTableAdapter();
        var compilation = adapter.compile(NAMESPACE, "DiscountModel", DMN);
        var services = org.kie.api.KieServices.Factory.get();
        String digest = com.acme.marketing.platform.crypto.Digests.sha256Hex(DMN).substring(0, 16);
        var release = services.newReleaseId("com.acme.marketing.dmn", "dmn-" + digest, "1.0.0");
        assertNull(services.getRepository().getKieModule(release));
        for (int i = 0; i < 3; i++) {
            assertTrue(adapter.evaluate(compilation.table(), "DiscountTier", Map.of("amount", BigDecimal.ONE)).success());
            assertNull(services.getRepository().getKieModule(release));
        }
    }

    @Test
    void concurrentAdaptersDoNotRemoveAnotherEvaluationModule() throws Exception {
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var requests = java.util.stream.IntStream.range(0, 2).mapToObj(index -> pool.submit(() -> {
                var adapter = new KieDmnDecisionTableAdapter();
                for (int attempt = 0; attempt < 3; attempt++) {
                    var compiled = adapter.compile(NAMESPACE, "DiscountModel", DMN);
                    assertTrue(compiled.valid(), compiled.messages().toString());
                    var evaluated = adapter.evaluate(compiled.table(), "DiscountTier", Map.of("amount", BigDecimal.valueOf(800)));
                    assertTrue(evaluated.success(), evaluated.messages().toString());
                    assertEquals("VIP", evaluated.results().get("DiscountTier"));
                }
                return true;
            })).toList();
            for (var request : requests) assertTrue(request.get(30, java.util.concurrent.TimeUnit.SECONDS));
        }
    }
}
