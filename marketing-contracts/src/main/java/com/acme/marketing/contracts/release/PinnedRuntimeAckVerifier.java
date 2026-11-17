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

    /** 组合ACK使用原运行端信任集合，但必须通过独立协议的双层验签。 */
    public boolean verify(String tenantId, ReleaseBundleAck ack) {
        PublicKey key = trustedKeys.get(ack.runtimeAck().signatureKeyId());
        return key != null && ReleaseBundleAckSigner.verify(key, tenantId, ack);
    }
}
