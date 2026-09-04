package com.acme.marketing.provider;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class HmacRequestSigner {
    private final byte[] secret;

    public HmacRequestSigner(byte[] secret) {
        this.secret = secret.clone();
    }

    public String sign(String contactKey, Instant timestamp, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            mac.update(contactKey.getBytes(StandardCharsets.UTF_8));
            mac.update((byte) '\n');
            mac.update(timestamp.toString().getBytes(StandardCharsets.UTF_8));
            mac.update((byte) '\n');
            return HexFormat.of().formatHex(mac.doFinal(body));
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", impossible);
        }
    }

    public boolean verify(String signature, String contactKey, Instant timestamp, byte[] body) {
        byte[] expected = HexFormat.of().parseHex(sign(contactKey, timestamp, body));
        byte[] actual;
        try {
            actual = HexFormat.of().parseHex(signature);
        } catch (IllegalArgumentException invalidHex) {
            return false;
        }
        return java.security.MessageDigest.isEqual(expected, actual);
    }
}
