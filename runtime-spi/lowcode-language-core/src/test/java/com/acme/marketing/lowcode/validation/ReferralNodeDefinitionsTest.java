package com.acme.marketing.lowcode.validation;

import com.acme.marketing.lowcode.model.*;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 验证新增目录不会将奖励声明变成副作用节点，也不会泄漏到旧方言。 */
class ReferralNodeDefinitionsTest {
    @Test void directoryIsPureImmutableAndVersioned() {
        var nodes = ReferralNodeDefinitions.all();
        assertEquals(5, nodes.size());
        assertTrue(nodes.stream().allMatch(n -> n.sideEffect() == SideEffect.NONE
                && n.dialects().equals(Set.of(Dialect.REFERRAL_POLICY)) && "referral".equals(n.runtimeTarget())));
        assertThrows(UnsupportedOperationException.class, nodes::clear);
    }
    @Test void referralDialectRejectsEvenRegisteredSideEffectAndOldDialectRejectsReferralNode() {
        var original = ReferralNodeDefinitions.all().getFirst();
        var malicious = new NodeDefinition(original.stableTypeId(), original.semanticVersion(), original.dialects(),
                original.runtimeTarget(), original.inputPorts(), original.outputPorts(), original.configSchemaRef(),
                original.uiSchemaRef(), original.nullSemantics(), original.missingSemantics(), SideEffect.GRANT,
                original.costWeight(), original.requiredFields(), original.compilerPluginDigest(), original.migrators(), original.permissions());
        var graph = new GraphDefinition("fixture", Dialect.REFERRAL_POLICY, "1.0.0",
                List.of(new GraphNode("s", "referral.start", "1.0.0", Map.of())), List.of(), Map.of(), Map.of());
        assertTrue(new GraphValidator(List.of(malicious), GraphValidator.Limits.productionDefaults()).validate(graph)
                .stream().anyMatch(i -> "SIDE_EFFECT_FORBIDDEN".equals(i.code())));
        var old = new GraphDefinition("fixture", Dialect.OFFER_DECISION_DAG, "1.0.0", graph.nodes(), graph.edges(), Map.of(), Map.of());
        assertTrue(new GraphValidator(ReferralNodeDefinitions.all(), GraphValidator.Limits.productionDefaults()).validate(old)
                .stream().anyMatch(i -> "DIALECT_NODE_FORBIDDEN".equals(i.code())));
    }
}
