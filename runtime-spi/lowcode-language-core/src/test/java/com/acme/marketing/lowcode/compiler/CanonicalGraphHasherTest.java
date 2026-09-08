package com.acme.marketing.lowcode.compiler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.acme.marketing.lowcode.model.Dialect;
import com.acme.marketing.lowcode.model.GraphDefinition;
import com.acme.marketing.lowcode.model.GraphNode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CanonicalGraphHasherTest {
    @Test void preservesLegacyDigestBytes() {
        assertEquals("sha256:b5c54cf261c7efc01f9e7b706c3c6f913e74da3ae63b81fe1f17e2e21d0f1175", new CanonicalGraphHasher().semanticHash(graph(Map.of("threshold", "500"))));
    }
    @Test void referralFieldsCannotEscapeTheirLengthPrefix() {
        var hasher = new CanonicalGraphHasher();
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> hasher.semanticHash(referral(Map.of("ruleId", String.valueOf((char) 0xD800)))));
        var left = referral(Map.of("ruleId", "a;skuVersion=b", "skuVersion", "c"));
        var right = referral(Map.of("ruleId", "a", "skuVersion", "b;skuVersion=c"));
        assertNotEquals(hasher.semanticHash(left), hasher.semanticHash(right));
        assertNotEquals(hasher.semanticHash(referral(Map.of("ruleId", "é"))),
                hasher.semanticHash(referral(Map.of("ruleId", "e\u0301"))));
    }
    private static GraphDefinition referral(Map<String, String> config) {
        return new GraphDefinition("referral", Dialect.REFERRAL_POLICY, "1.0.0",
                List.of(new GraphNode("reward", "referral.reward", "1.0.0", config)), List.of(), Map.of(), Map.of());
    }
    @Test
    void ignoresPresentationButNotSemanticChanges() {
        CanonicalGraphHasher hasher = new CanonicalGraphHasher();
        GraphDefinition left = graph(Map.of("threshold", "500", "ui.x", "10"));
        GraphDefinition moved = graph(Map.of("ui.x", "200", "threshold", "500"));
        GraphDefinition changed = graph(Map.of("ui.x", "200", "threshold", "501"));

        assertEquals(hasher.semanticHash(left), hasher.semanticHash(moved));
        assertNotEquals(hasher.semanticHash(left), hasher.semanticHash(changed));
    }

    private static GraphDefinition graph(Map<String, String> config) {
        return new GraphDefinition("offer-1", Dialect.OFFER_DECISION_DAG, "1.0.0",
                List.of(new GraphNode("node-1", "condition.amount", "1.0.0", config)),
                List.of(), Map.of(), Map.of());
    }
}
