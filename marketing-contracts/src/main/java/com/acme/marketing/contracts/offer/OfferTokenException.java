package com.acme.marketing.contracts.offer;

import com.acme.marketing.platform.error.DomainException;

public final class OfferTokenException extends DomainException {
    private static final long serialVersionUID = 1L;

    public OfferTokenException(String code, String message) {
        super(code, message);
    }
}
