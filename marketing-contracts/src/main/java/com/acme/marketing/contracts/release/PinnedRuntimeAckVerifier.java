package com.acme.marketing.contracts.release;

import java.security.PublicKey;
import java.util.Map;

public final class PinnedRuntimeAckVerifier {
    private final Map<String, PublicKey> trustedKeys;

    public PinnedRuntimeAckVerifier(Map<String, PublicKey> trustedKeys) {
        this.trustedKeys = Map.copyOf(trustedKeys);
    }

    public boolean verify(String tenantId, RuntimeAck ack) {
        PublicKey key = trustedKeys.get(ack.signatureKeyId());
        return key != null && RuntimeAckSigner.verify(key, tenantId, ack);
    }
}
