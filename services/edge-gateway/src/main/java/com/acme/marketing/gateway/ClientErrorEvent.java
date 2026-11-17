package com.acme.marketing.gateway;

import java.util.Arrays;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/** 仅允许低基数诊断分类；任意文本、身份和业务标识都不属于采集合同。 */
record ClientErrorEvent(Source source, Route route, int httpStatus, String backendTraceId) {
    private static final int SCHEMA_VERSION = 1;
    private static final int NO_HTTP_STATUS = 0;
    private static final int MIN_HTTP_STATUS = 100;
    private static final int MAX_HTTP_STATUS = 599;
    private static final Set<String> REQUIRED = Set.of("schemaVersion", "source", "route", "httpStatus");
    private static final Set<String> ALLOWED = Set.of("schemaVersion", "source", "route", "httpStatus", "backendTraceId");

    /** 严格检查类型与字段全集，避免 Jackson 的类型强制转换扩大隐私输入面。 */
    static ClientErrorEvent read(JsonNode node) {
        if (node == null || !node.isObject() || !node.propertyNames().containsAll(REQUIRED)
                || !ALLOWED.containsAll(node.propertyNames()) || !node.get("schemaVersion").isIntegralNumber()
                || !node.get("schemaVersion").canConvertToInt() || node.get("schemaVersion").asInt() != SCHEMA_VERSION
                || !node.get("source").isString() || !node.get("route").isString()
                || !node.get("httpStatus").isIntegralNumber() || !node.get("httpStatus").canConvertToInt()) {
            throw ClientErrorCode.INPUT_INVALID.invalid();
        }
        int status = node.get("httpStatus").asInt();
        if (status != NO_HTTP_STATUS && (status < MIN_HTTP_STATUS || status > MAX_HTTP_STATUS)) throw ClientErrorCode.INPUT_INVALID.invalid();
        String trace = null;
        if (node.has("backendTraceId")) {
            JsonNode value = node.get("backendTraceId");
            if (!value.isString() || !value.asString().matches("[0-9a-fA-F]{32}")) throw ClientErrorCode.INPUT_INVALID.invalid();
            trace = value.asString().toLowerCase(java.util.Locale.ROOT);
        }
        return new ClientErrorEvent(Source.read(node.get("source").asString()), Route.read(node.get("route").asString()), status, trace);
    }

    /** 指标仅保留状态大类，未知状态使用固定标记。 */
    String statusClass() { return httpStatus == NO_HTTP_STATUS ? "none" : httpStatus / 100 + "xx"; }

    /** 稳定 code 独立于枚举名称；计数器只使用该封闭集合。 */
    enum Source {
        API("API"), WINDOW("WINDOW"), PROMISE("PROMISE"), REACT("REACT"), UNKNOWN("UNKNOWN");
        private final String code;
        Source(String code) { this.code = code; }
        String code() { return code; }
        static Source read(String code) { return Arrays.stream(values()).filter(v -> v.code.equals(code)).findFirst().orElseThrow(ClientErrorCode.INPUT_INVALID::invalid); }
    }

    /** 模块分类永不包含动态路径段、查询串或用户输入。 */
    enum Route {
        HOME("HOME"), CAMPAIGNS("CAMPAIGNS"), DESIGNERS("DESIGNERS"), ASSETS("ASSETS"), RELEASES("RELEASES"),
        GOVERNANCE("GOVERNANCE"), ANALYTICS("ANALYTICS"), REFERRALS("REFERRALS"), AUTH("AUTH"),
        OPERATIONS("OPERATIONS"), API("API"), OTHER("OTHER");
        private final String code;
        Route(String code) { this.code = code; }
        String code() { return code; }
        static Route read(String code) { return Arrays.stream(values()).filter(v -> v.code.equals(code)).findFirst().orElseThrow(ClientErrorCode.INPUT_INVALID::invalid); }
    }
}
