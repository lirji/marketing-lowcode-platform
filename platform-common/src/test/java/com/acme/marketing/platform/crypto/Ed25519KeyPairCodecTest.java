package com.acme.marketing.platform.crypto;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class Ed25519KeyPairCodecTest {
    @Test
    void decodesAndVerifiesMatchingPair() {
        var pair = Ed25519.generateKeyPair();
        var decoded = Ed25519KeyPairCodec.decode(
                Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()),
                Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()));
        byte[] message = "payload".getBytes(StandardCharsets.UTF_8);

        assertTrue(Ed25519.verify(decoded.getPublic(), message, Ed25519.sign(decoded.getPrivate(), message)));
    }

    @Test
    void rejectsMismatchedPair() {
        var privatePair = Ed25519.generateKeyPair();
        var publicPair = Ed25519.generateKeyPair();

        assertThrows(IllegalArgumentException.class, () -> Ed25519KeyPairCodec.decode(
                Base64.getEncoder().encodeToString(privatePair.getPrivate().getEncoded()),
                Base64.getEncoder().encodeToString(publicPair.getPublic().getEncoded())));
    }
}
