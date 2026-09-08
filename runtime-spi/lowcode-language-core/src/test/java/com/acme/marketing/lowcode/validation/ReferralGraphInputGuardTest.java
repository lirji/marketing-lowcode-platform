package com.acme.marketing.lowcode.validation;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 原始Map由JSON适配器提供，确保共享guard在类型绑定前失败关闭。 */
class ReferralGraphInputGuardTest {
    @Test void rejectsAllNonStringReferralConfigValuesAndOrdinalDialect() {
        for (Object value : Arrays.asList(1, true, null, List.of(), Map.of())) {
            var config = new HashMap<String, Object>(); config.put("quantity", value);
            var graph = Map.of("dialect", "REFERRAL_POLICY", "nodes", List.of(Map.of("config", config)));
            assertThrows(IllegalArgumentException.class, () -> ReferralGraphInputGuard.validate(graph));
        }
        for (Object value : Arrays.asList(5, true, null)) {
            var graph = new HashMap<String, Object>(); graph.put("dialect", value); graph.put("nodes", List.of());
            assertThrows(IllegalArgumentException.class, () -> ReferralGraphInputGuard.validate(graph));
        }
    }
    @Test void acceptsStringReferralAndLeavesLegacyValueConversionToAdapter() {
        assertDoesNotThrow(() -> ReferralGraphInputGuard.validate(Map.of("dialect", "REFERRAL_POLICY",
                "nodes", List.of(Map.of("config", Map.of("quantity", "1"))))));
        assertDoesNotThrow(() -> ReferralGraphInputGuard.validate(Map.of("dialect", "OFFER_DECISION_DAG",
                "nodes", List.of(Map.of("config", Map.of("amountMinor", 1))))));
    }
}
