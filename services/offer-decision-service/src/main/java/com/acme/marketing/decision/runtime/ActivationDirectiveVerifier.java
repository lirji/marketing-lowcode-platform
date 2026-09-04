package com.acme.marketing.decision.runtime;

import com.acme.marketing.contracts.release.ActivationDirective;

@FunctionalInterface
public interface ActivationDirectiveVerifier {
    boolean verify(String keyId, ActivationDirective directive);
}
