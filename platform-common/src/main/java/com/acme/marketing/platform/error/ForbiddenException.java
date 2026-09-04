package com.acme.marketing.platform.error;

public final class ForbiddenException extends DomainException {
    private static final long serialVersionUID = 1L;

    public ForbiddenException(String code, String message) {
        super(code, message);
    }
}
