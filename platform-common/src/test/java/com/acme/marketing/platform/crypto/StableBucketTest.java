package com.acme.marketing.platform.crypto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class StableBucketTest {
    @Test
    void assignmentIsStableAndBounded() {
        byte[] secret = "release-secret".getBytes(StandardCharsets.UTF_8);
        int first = StableBucket.assign(secret, "tenant:user:experiment", 10_000);
        assertEquals(first, StableBucket.assign(secret, "tenant:user:experiment", 10_000));
        assertTrue(first >= 0 && first < 10_000);
    }
}
