package com.acme.marketing.control.application;

import com.acme.marketing.platform.error.ConflictException;

/** 优惠策略入口共用稳定错误码，沿用控制面的 ConflictException 响应契约。 */
enum OfferPolicyError {
    PREVIEW_CONTEXT_REQUIRED("OFFER_PREVIEW_CONTEXT_REQUIRED", "范围受限优惠需要完整购物车和资格上下文，当前金额试算不支持"),
    RECOMPILE_REQUIRED("OFFER_RECOMPILE_REQUIRED", "比例优惠制品来自旧编译语义，请重新编译后发布");

    private final String code;
    private final String message;

    OfferPolicyError(String code, String message) {
        this.code = code;
        this.message = message;
    }

    /** 保留既有 HTTP 冲突语义，不把发布阻断转换成内部错误。 */
    ConflictException exception() {
        return new ConflictException(code, message);
    }
}
