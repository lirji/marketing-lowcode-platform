package com.acme.marketing.compiler.application;

import tools.jackson.databind.JsonNode;
import java.util.Map;
import tools.jackson.databind.ObjectMapper;

/** 在 JSON 转 Map 前保护裂变字符串配置合同；旧格式保持既有 ObjectMapper 转换语义。 */
public final class ReferralCompileRequestReader {
    private final ObjectMapper mapper;

    /** 使用与 HTTP 入口相同的 mapper；重复 JSON 属性必须由解析器严格拒绝。 */
    public ReferralCompileRequestReader(ObjectMapper mapper) { this.mapper = mapper; }

    /**
     * 裂变图配置只接收原 JSON 字符串，数字/布尔值/对象不得自动转成字符串后签名。
     * 校验发生在类型转换前；纯 Java 调用方已经由 GraphNode 的 Map<String,String> 合同约束。
     */
    public RuleCompilerService.CompileRequest read(JsonNode input) {
        if (input == null || !input.isObject()) throw new IllegalArgumentException("COMPILE_REQUEST_OBJECT_REQUIRED");
        // Jackson 可把数字 ordinal 转成 enum；必须先守住原始标识类型，不能跳过裂变专用检查。
        if (!input.path("format").isString()) throw new IllegalArgumentException("COMPILE_FORMAT_STRING_REQUIRED");
        var graph = input.path("graph");
        if (!graph.isMissingNode() && !graph.isNull() && (!graph.isObject() || !graph.path("dialect").isString()))
            throw new IllegalArgumentException("GRAPH_DIALECT_STRING_REQUIRED");
        boolean referral = "REFERRAL_PLAN".equals(input.path("format").asString(""))
                || "REFERRAL_POLICY".equals(input.path("graph").path("dialect").asString(""));
        if (referral)
            com.acme.marketing.lowcode.validation.ReferralGraphInputGuard.validate(mapper.convertValue(graph, Map.class));
        return mapper.treeToValue(input, RuleCompilerService.CompileRequest.class);
    }
}
