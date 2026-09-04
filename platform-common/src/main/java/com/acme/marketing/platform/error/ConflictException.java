package com.acme.marketing.platform.error;

public class ConflictException extends DomainException {
    private static final long serialVersionUID = 1L;

    public ConflictException(String code, String message) {
        super(code, message);
    }
}
