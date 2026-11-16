package com.acme.marketing.benefit.application;

/** 履约查询稳定错误码与对外提示；诊断不得包含原始响应或收件人。 */
public final class AwardFulfillmentErrors {
    private AwardFulfillmentErrors() { }
    public static final String AWARD_CENTER_IDENTITY_MISMATCH = "AWARD_CENTER_IDENTITY_MISMATCH";
    public static final String AWARD_CENTER_ORDER_NOT_FOUND = "AWARD_CENTER_ORDER_NOT_FOUND";
    public static final String AWARD_CENTER_QUERY_CAPACITY = "AWARD_CENTER_QUERY_CAPACITY";
    public static final String AWARD_CENTER_QUERY_HTTP_ = "AWARD_CENTER_QUERY_HTTP_";
    public static final String AWARD_CENTER_QUERY_INTERRUPTED = "AWARD_CENTER_QUERY_INTERRUPTED";
    public static final String AWARD_CENTER_QUERY_INVALID = "AWARD_CENTER_QUERY_INVALID";
    public static final String AWARD_CENTER_QUERY_TIMEOUT = "AWARD_CENTER_QUERY_TIMEOUT";
    public static final String AWARD_CENTER_QUERY_UNAVAILABLE = "AWARD_CENTER_QUERY_UNAVAILABLE";
    public static final String AWARD_CENTER_STATUS_INVALID = "AWARD_CENTER_STATUS_INVALID";
    public static final String AWARD_FULFILLMENT_CHECK_FAILED = "AWARD_FULFILLMENT_CHECK_FAILED";
    public static final String AWARD_FULFILLMENT_MODE_UNSUPPORTED = "AWARD_FULFILLMENT_MODE_UNSUPPORTED";
    public static final String AWARD_FULFILLMENT_SYNC_DISABLED = "AWARD_FULFILLMENT_SYNC_DISABLED";
    public static final String AWARD_INTENT_NOT_FOUND = "AWARD_INTENT_NOT_FOUND";
    public static final String DISABLED_MESSAGE = "履约查询尚未启用，请先配置权益中心读取凭据";
    public static final String NOT_FOUND_MESSAGE = "发奖意图不存在";
    public static final String MODE_MESSAGE = "只有权益中心模式支持履约查询";
}
