package com.acme.marketing.platform.error;

/** 外部依赖暂时不可用；默认映射 502，少数契约化错误码（如 RISK_UNAVAILABLE）可映射 503。 */
public final class DependencyUnavailableException extends DomainException {
    private static final long serialVersionUID = 1L;

    public DependencyUnavailableException(String code, String message) {
        super(code, message, true);
    }
}
