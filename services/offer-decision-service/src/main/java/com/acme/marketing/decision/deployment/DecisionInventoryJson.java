package com.acme.marketing.decision.deployment;

import com.acme.marketing.contracts.release.DecisionDeploymentInventory;
import com.acme.marketing.contracts.release.ReleaseBundleProtocolError;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.json.JsonMapper;

/** 两个接收入口共享严格的文件格式，拒绝歧义字段和数字时戳。 */
public final class DecisionInventoryJson {
    public static final int MAX_DOCUMENT_BYTES = 64 * 1024;
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                    DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES, DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).build();

    private DecisionInventoryJson() { }

    public static DecisionDeploymentInventory read(byte[] bytes) {
        if (bytes.length > MAX_DOCUMENT_BYTES) throw ReleaseBundleProtocolError.DECISION_INVENTORY_INVALID.invalid();
        var root = JSON.readTree(bytes);
        if (root == null || !root.isObject() || !root.path("observedAt").isString() || !root.path("expiresAt").isString() || !root.path("sequence").isIntegralNumber())
            throw ReleaseBundleProtocolError.DECISION_INVENTORY_INVALID.invalid();
        return JSON.treeToValue(root, DecisionDeploymentInventory.class);
    }

    public static String write(DecisionDeploymentInventory inventory) { return JSON.writeValueAsString(inventory); }
}
