package com.acme.marketing.engagement.application;

import com.acme.marketing.platform.error.ConflictException;

/** 触达恢复的稳定问题码；复用平台冲突响应，不另建错误响应格式。 */
enum ContactDeliveryProblem {
    RETRY_UNVERIFIABLE("CONTACT_RETRY_UNVERIFIABLE", "历史触达缺少完整请求指纹，请先核查渠道结果，不能自动重发");

    private final String code;
    private final String message;

    ContactDeliveryProblem(String code, String message) {
        this.code = code;
        this.message = message;
    }

    /** 保留原触达与频控名额，要求核实未知投递结果后再人工处理。 */
    ConflictException exception() {
        return new ConflictException(code, message);
    }
}
