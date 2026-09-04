package com.acme.marketing.platform.crypto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.security.KeyPair;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class TrustedPublicKeysTest {
    @Test
    void parsesPrimaryAndRolloverKeys() {
        KeyPair first = Ed25519.generateKeyPair();
        KeyPair second = Ed25519.generateKeyPair();

        var keys = TrustedPublicKeys.parse("current", encoded(first), "next=" + encoded(second));

        assertEquals(first.getPublic(), keys.get("current"));
        assertEquals(second.getPublic(), keys.get("next"));
    }

    @Test
    void rejectsPartialOrDuplicateConfiguration() {
        KeyPair key = Ed25519.generateKeyPair();
        assertThrows(IllegalArgumentException.class, () -> TrustedPublicKeys.parse("current", "", ""));
        assertThrows(IllegalArgumentException.class,
                () -> TrustedPublicKeys.parse("current", encoded(key), "current=" + encoded(key)));
    }

    private static String encoded(KeyPair pair) {
        return Base64.getEncoder().encodeToString(pair.getPublic().getEncoded());
    }
}
