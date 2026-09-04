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
