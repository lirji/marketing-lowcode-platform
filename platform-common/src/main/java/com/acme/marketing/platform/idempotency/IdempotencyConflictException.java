package com.acme.marketing.platform.idempotency;

import com.acme.marketing.platform.error.ConflictException;

public final class IdempotencyConflictException extends ConflictException {
    private static final long serialVersionUID = 1L;

    public IdempotencyConflictException() {
        super("IDEMPOTENCY_PAYLOAD_CONFLICT", "idempotency key was already used with a different payload");
    }
}
