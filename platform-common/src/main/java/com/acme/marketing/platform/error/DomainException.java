package com.acme.marketing.platform.error;

public class DomainException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final String code;
    private final boolean retryable;

    public DomainException(String code, String message) {
        this(code, message, false);
    }

    public DomainException(String code, String message, boolean retryable) {
        super(message);
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("code is required");
        }
        this.code = code;
        this.retryable = retryable;
    }

    public String code() {
        return code;
    }

    public boolean retryable() {
        return retryable;
    }
}
