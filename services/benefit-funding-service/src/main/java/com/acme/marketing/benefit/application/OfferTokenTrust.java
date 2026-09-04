package com.acme.marketing.benefit.application;

import java.security.PublicKey;

@FunctionalInterface
public interface OfferTokenTrust {
    PublicKey resolve(String keyId);
}
