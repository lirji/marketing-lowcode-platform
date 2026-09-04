package com.acme.marketing.platform.crypto;

import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

public final class SigningKeyRing {
    private final ConcurrentHashMap<String, KeyPair> keys = new ConcurrentHashMap<>();
    private volatile String activeKeyId;

    public SigningKeyRing(String keyId, KeyPair keyPair) {
        rotate(keyId, keyPair, true);
    }

    public void rotate(String keyId, KeyPair keyPair, boolean activate) {
        if (keyId == null || !keyId.matches("[a-zA-Z0-9_-]{1,64}")) {
            throw new IllegalArgumentException("signing key id is invalid");
        }
        keys.put(keyId, Objects.requireNonNull(keyPair, "keyPair"));
        if (activate) {
            activeKeyId = keyId;
        }
    }

    public String activeKeyId() {
        return activeKeyId;
    }

    public PrivateKey activePrivateKey() {
        return keys.get(activeKeyId).getPrivate();
    }

    public PublicKey publicKey(String keyId) {
        KeyPair pair = keys.get(keyId);
        return pair == null ? null : pair.getPublic();
    }

    public Map<String, PublicKey> publicKeys() {
        return keys.entrySet().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                Map.Entry::getKey, entry -> entry.getValue().getPublic()));
    }
}
