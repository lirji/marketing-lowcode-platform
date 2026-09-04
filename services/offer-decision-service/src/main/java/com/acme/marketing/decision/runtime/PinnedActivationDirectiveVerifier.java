package com.acme.marketing.decision.runtime;

import com.acme.marketing.contracts.release.ActivationDirective;
import com.acme.marketing.contracts.release.ActivationDirectiveSigner;
import java.security.PublicKey;
import java.util.Map;

public final class PinnedActivationDirectiveVerifier implements ActivationDirectiveVerifier {
    private final Map<String, PublicKey> trustedKeys;

    public PinnedActivationDirectiveVerifier(Map<String, PublicKey> trustedKeys) {
        this.trustedKeys = Map.copyOf(trustedKeys);
    }

    @Override
    public boolean verify(String keyId, ActivationDirective directive) {
        PublicKey key = trustedKeys.get(keyId);
        return key != null && ActivationDirectiveSigner.verify(key, directive);
    }
}
