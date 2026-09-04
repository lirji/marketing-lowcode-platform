package com.acme.marketing.platform.crypto;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class StableBucket {
    private StableBucket() {
    }

    public static int assign(byte[] secret, String material, int bucketCount) {
        if (bucketCount <= 0) {
            throw new IllegalArgumentException("bucketCount must be positive");
        }
        try {
            Mac hmac = Mac.getInstance("HmacSHA256");
            hmac.init(new SecretKeySpec(secret.clone(), "HmacSHA256"));
            long hash = ByteBuffer.wrap(hmac.doFinal(material.getBytes(StandardCharsets.UTF_8))).getLong();
            return (int) Long.remainderUnsigned(hash, bucketCount);
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException("HmacSHA256 is required by the JDK", impossible);
        }
    }
}
