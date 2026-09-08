package com.acme.marketing.lowcode.validation;

import java.util.List;
import java.util.Map;

/** 原始JSON图的共享形状检查；使用普通Map保留值类型，不依赖Jackson或进行业务资格运算。 */
public final class ReferralGraphInputGuard {
    private ReferralGraphInputGuard() { }

    /**
     * 在GraphDefinition绑定前调用。所有方言标识须原字符串；裂变配置值须原字符串，拒绝自动强制转换。
     * 旧合法字符串方言的配置转换维持适配器既有语义；未知裂变字段由共享编译parser进一步拒绝。
     */
    public static void validate(Object rawGraph) {
        if (!(rawGraph instanceof Map<?, ?> graph) || !(graph.get("dialect") instanceof String dialect))
            throw new IllegalArgumentException("GRAPH_DIALECT_STRING_REQUIRED");
        if (!"REFERRAL_POLICY".equals(dialect)) return;
        if (!(graph.get("nodes") instanceof List<?> nodes))
            throw new IllegalArgumentException("REFERRAL_NODES_ARRAY_REQUIRED");
        for (var item : nodes) {
            if (!(item instanceof Map<?, ?> node) || !(node.get("config") instanceof Map<?, ?> config))
                throw new IllegalArgumentException("REFERRAL_CONFIG_OBJECT_REQUIRED");
            for (var value : config.values()) {
                if (!(value instanceof String)) throw new IllegalArgumentException("REFERRAL_CONFIG_STRING_REQUIRED");
            }
        }
    }
}
