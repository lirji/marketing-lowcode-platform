package com.acme.marketing.platform.crypto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.security.KeyPair;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SignedTokenCodecTest {
    @Test
    void detectsAnyPayloadMutation() {
        KeyPair keyPair = Ed25519.generateKeyPair();
        String token = SignedTokenCodec.encode("k1", keyPair.getPrivate(), Map.of("tenant", "租户一", "amount", "100"));

        assertEquals("100", SignedTokenCodec.decodeAndVerify(token, ignored -> keyPair.getPublic()).claims().get("amount"));

        char replacement = token.charAt(token.length() - 2) == 'A' ? 'B' : 'A';
        String mutated = token.substring(0, token.length() - 2) + replacement + token.substring(token.length() - 1);
        assertThrows(IllegalArgumentException.class,
                () -> SignedTokenCodec.decodeAndVerify(mutated, ignored -> keyPair.getPublic()));
    }
}
