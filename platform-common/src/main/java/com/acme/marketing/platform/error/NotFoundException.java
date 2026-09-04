package com.acme.marketing.platform.error;

public final class NotFoundException extends DomainException {
    private static final long serialVersionUID = 1L;

    public NotFoundException(String code, String message) {
        super(code, message);
    }
}
