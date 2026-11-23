package com.acme.marketing.contracts.release;

import static org.junit.jupiter.api.Assertions.*;

import com.acme.marketing.contracts.release.DecisionDeploymentInventory.Replica;
import com.acme.marketing.platform.crypto.Ed25519;
import com.acme.marketing.platform.error.ConflictException;
import java.security.KeyPair;
import java.security.PublicKey;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DecisionDeploymentInventoryTest {
    private static final Instant NOW = Instant.parse("2026-11-23T07:00:00Z");
    private static final String DEPLOY = "pipeline";
    private final KeyPair keys = Ed25519.generateKeyPair();
    private final Map<String, PublicKey> trust = Map.of(DEPLOY, keys.getPublic());
    private final Replica first = new Replica("pod-1", "sha256:" + "a".repeat(64), "exec1");
    private final Replica second = new Replica("container-2", "sha256:" + "b".repeat(64), "exec2");

    @Test void signsBothPlatformsWithStableMemberOrderAndAcceptsAnExplicitDrain() {
        var one = inventory(List.of(first, second)).sign(keys.getPrivate());
        var two = inventory(List.of(second, first)).sign(keys.getPrivate());
        assertEquals(one, two); assertEquals(one.contentDigest(), two.contentDigest());
        verify(one, one.contentDigest(), NOW);
        var drained = inventory(List.of()).sign(keys.getPrivate());
        verify(drained, drained.contentDigest(), NOW.plusSeconds(59));
        assertNotEquals(one.contentDigest(), drained.contentDigest());
    }

    @Test void pinScopeClockAndCurrentTrustMustAllMatch() {
        var value = inventory(List.of(first)).sign(keys.getPrivate());
        for (String[] scope : List.of(new String[]{"prod","cell-a","main"}, new String[]{"test","cell-b","main"},
                new String[]{"test","cell-a","other"})) {
            assertThrows(ConflictException.class, () -> value.requireCurrent(trust, scope[0], scope[1], scope[2], 7, value.contentDigest(), NOW));
        }
        assertThrows(ConflictException.class, () -> verify(value, value.contentDigest(), NOW.minusNanos(1)));
        assertThrows(ConflictException.class, () -> verify(value, value.contentDigest(), NOW.plusSeconds(60)));
        assertThrows(ConflictException.class, () -> value.requireCurrent(Map.of(), "test","cell-a","main",7, value.contentDigest(), NOW));
        assertThrows(ConflictException.class, () -> value.requireCurrent(trust, "test","cell-a","main",8, value.contentDigest(), NOW));
        assertThrows(ConflictException.class, () -> verify(value, inventory(List.of(second)).contentDigest(), NOW));
    }

    @Test void changingMemberIdentityImageOrExecutionKeyInvalidatesTheSignatureEvenWithAChangedPin() {
        var valid = inventory(List.of(first)).sign(keys.getPrivate());
        for (var member : List.of(new Replica("replacement", first.imageDigest(), first.executionKeyId()),
                new Replica(first.instanceId(), second.imageDigest(), first.executionKeyId()),
                new Replica(first.instanceId(), first.imageDigest(), second.executionKeyId()))) {
            var copy = new DecisionDeploymentInventory("test", "cell-a", "main", 7, NOW, NOW.plusSeconds(60),
                    List.of(member), DEPLOY, valid.signature());
            assertThrows(ConflictException.class, () -> verify(copy, copy.contentDigest(), NOW));
        }
        var wrongKey = inventory(List.of(first)).sign(Ed25519.generateKeyPair().getPrivate());
        assertThrows(ConflictException.class, () -> verify(wrongKey, wrongKey.contentDigest(), NOW));
    }

    @Test void rejectsAmbiguousOrUnboundedMembershipAndMutableTags() {
        assertThrows(IllegalArgumentException.class, () -> inventory(List.of(first, first)));
        assertThrows(IllegalArgumentException.class, () -> inventory(List.of(first, new Replica("other", first.imageDigest(), "exec1"))));
        assertThrows(IllegalArgumentException.class, () -> inventory(List.of(new Replica("other", first.imageDigest(), DEPLOY))));
        assertThrows(IllegalArgumentException.class, () -> new Replica("pod", "release-1", "exec"));
        assertThrows(IllegalArgumentException.class, () -> inventory(java.util.Collections.nCopies(129, first)));
        assertThrows(IllegalArgumentException.class, () -> new DecisionDeploymentInventory("test","cell-a","main",7,
                NOW, NOW.plusSeconds(301), List.of(), DEPLOY, ""));
    }

    private DecisionDeploymentInventory inventory(List<Replica> replicas) {
        return new DecisionDeploymentInventory("test", "cell-a", "main", 7, NOW, NOW.plusSeconds(60), replicas, DEPLOY, "");
    }
    private void verify(DecisionDeploymentInventory value, String digest, Instant time) {
        value.requireCurrent(trust, "test", "cell-a", "main", 7, digest, time);
    }
}
