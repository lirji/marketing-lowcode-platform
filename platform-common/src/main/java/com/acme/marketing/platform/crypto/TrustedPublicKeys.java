package com.acme.marketing.platform.crypto;

import java.security.PublicKey;
import java.util.LinkedHashMap;
import java.util.Map;

/** Parses a bounded Ed25519 verification key set used during zero-downtime key rotation. */
public final class TrustedPublicKeys {
    private static final int MAX_KEYS = 32;
    private static final int MAX_CONFIGURATION_LENGTH = 32_768;

    private TrustedPublicKeys() {
    }

    /**
     * Additional keys use {@code key-id=base64-public-key} entries separated by comma,
     * semicolon, or a newline. The primary pair remains compatible with the original
     * single-key configuration.
     */
    public static Map<String, PublicKey> parse(String primaryKeyId, String primaryPublicKey, String additional) {
        String keyId = normalized(primaryKeyId);
        String publicKey = normalized(primaryPublicKey);
        if (keyId.isEmpty() != publicKey.isEmpty()) {
            throw new IllegalArgumentException("primary key id and public key must be configured together");
        }
        String configured = additional == null ? "" : additional.trim();
        if (configured.length() > MAX_CONFIGURATION_LENGTH) {
            throw new IllegalArgumentException("trusted public key configuration is too large");
        }

        Map<String, PublicKey> keys = new LinkedHashMap<>();
        if (!keyId.isEmpty()) {
            add(keys, keyId, publicKey);
        }
        if (!configured.isEmpty()) {
            for (String rawEntry : configured.split("[,;\\n\\r]+")) {
                String entry = rawEntry.trim();
                if (entry.isEmpty()) continue;
                int separator = entry.indexOf('=');
                if (separator < 1 || separator == entry.length() - 1) {
                    throw new IllegalArgumentException("trusted public key entry must use key-id=base64 format");
                }
                add(keys, entry.substring(0, separator).trim(), entry.substring(separator + 1).trim());
            }
        }
        return Map.copyOf(keys);
    }

    private static void add(Map<String, PublicKey> keys, String keyId, String encoded) {
        if (!keyId.matches("[a-zA-Z0-9_-]{1,64}")) {
            throw new IllegalArgumentException("trusted public key id is invalid");
        }
        if (keys.containsKey(keyId)) {
            throw new IllegalArgumentException("duplicate trusted public key id: " + keyId);
        }
        if (keys.size() >= MAX_KEYS) {
            throw new IllegalArgumentException("too many trusted public keys");
        }
        keys.put(keyId, Ed25519KeyPairCodec.decodePublic(encoded));
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
