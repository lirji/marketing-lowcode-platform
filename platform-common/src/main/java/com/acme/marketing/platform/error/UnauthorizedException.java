package com.acme.marketing.platform.error;

/** 依赖身份缺失或失效；与已经认证但无权访问的 403 明确区分。 */
public final class UnauthorizedException extends DomainException {
    private static final long serialVersionUID = 1L;

    public UnauthorizedException(String code, String message) {
        super(code, message);
    }
}
