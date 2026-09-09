package com.acme.marketing.referral;

import static org.junit.jupiter.api.Assertions.*;
import static com.acme.marketing.referral.ReferralReleaseVerifierTest.*;
import com.acme.marketing.contracts.release.*;
import com.acme.marketing.platform.identity.TenantId;
import java.time.Duration;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.*;
import tools.jackson.databind.node.ObjectNode;

/** 真实签名覆盖指令字段与有效期；不提供 READY 替代物。 */
class ReferralRuntimeDirectiveVerifierTest {
    final ReferralReleaseVerifierTest fixture = new ReferralReleaseVerifierTest();
    final ReleaseManifest manifest = fixture.manifest(fixture.artifact(fixture.bytes(BODY)));
    final ReferralRuntimeDirectiveVerifier verifier = new ReferralRuntimeDirectiveVerifier(Map.of("release", RELEASE.getPublic()), "test", "cell", "default");
    ActivationDirective activation() {
        return ActivationDirectiveSigner.sign(RELEASE.getPrivate(), new ActivationDirective("activation", new TenantId("tenant-a"),
                manifest.manifestId(), "test", "cell", "referral", "default", 3, 2, 2, 0,
                manifest.signature(), NOW, NOW.plusSeconds(300), "admin", "release", ""));
    }
    KillSwitchDirective kill(boolean enabled) {
        return KillSwitchDirectiveSigner.sign(RELEASE.getPrivate(), new KillSwitchDirective("kill", new TenantId("tenant-a"),
                "default", 4, enabled, "test", NOW, "admin", "release", ""));
    }
    @Test void validActivationAndStickyKill() {
        assertDoesNotThrow(() -> verifier.activation("tenant-a", "release", manifest, activation(), NOW));
        assertDoesNotThrow(() -> verifier.kill("tenant-a", kill(true), NOW.plusSeconds(100000)));
        assertNull(ReferralRuntimeDirectiveVerifier.clearUntil(kill(true), Duration.ofSeconds(10), NOW));
    }
    @TestFactory Stream<DynamicTest> invalidSignedActivation() {
        Map<String, Consumer<ObjectNode>> edits = Map.ofEntries(
            Map.entry("tenant", n -> n.putObject("tenantId").put("value", "tenant-b")),
            Map.entry("environment", n -> n.put("environment", "prod")),
            Map.entry("cell", n -> n.put("cell", "wrong")),
            Map.entry("namespace", n -> n.put("namespace", "wrong")),
            Map.entry("runtime", n -> n.put("runtime", "journey")),
            Map.entry("manifest id", n -> n.put("manifestId", "wrong")),
            Map.entry("manifest signature", n -> n.put("manifestSignature", "wrong")),
            Map.entry("generation", n -> n.put("generation", 1)),
            Map.entry("stable generation", n -> n.put("stableGeneration", 1)),
            Map.entry("canary", n -> n.put("canaryBasisPoints", 1)),
            Map.entry("future", n -> n.put("activatedAt", NOW.plusSeconds(1).toString())),
            Map.entry("before scheduled activation", n -> n.put("activatedAt", NOW.minusSeconds(1).toString())),
            Map.entry("extends manifest", n -> n.put("expiresAt", NOW.plusSeconds(601).toString())),
            Map.entry("unknown key", n -> n.put("signatureKeyId", "unknown")));
        return edits.entrySet().stream().map(e -> DynamicTest.dynamicTest(e.getKey(), () -> {
            ObjectNode n = JSON.valueToTree(activation()); e.getValue().accept(n);
            var a = ActivationDirectiveSigner.sign(RELEASE.getPrivate(), JSON.treeToValue(n, ActivationDirective.class));
            assertThrows(IllegalArgumentException.class, () -> verifier.activation("tenant-a", "release", manifest, a, NOW));
        }));
    }
    @Test void tamperedOrExpiredActivationAndManifestRejected() {
        var a = activation(); ObjectNode n = JSON.valueToTree(a); n.put("activationSequence", 5);
        assertThrows(IllegalArgumentException.class, () -> verifier.activation("tenant-a", "release", manifest, JSON.treeToValue(n, ActivationDirective.class), NOW));
        assertThrows(IllegalArgumentException.class, () -> verifier.activation("tenant-a", "release", manifest, a, a.expiresAt()));
        assertThrows(IllegalArgumentException.class, () -> verifier.activation("tenant-a", "unknown", manifest, a, NOW));
        var changed = fixture.change(manifest, tree -> tree.put("expiresAt", NOW.plusSeconds(999).toString()));
        assertThrows(IllegalArgumentException.class, () -> verifier.activation("tenant-a", "release", changed, a, NOW));
    }
    @TestFactory Stream<DynamicTest> signedKillScopeAndTimeRejected() {
        return Map.<String, Consumer<ObjectNode>>of(
            "tenant", n -> n.putObject("tenantId").put("value", "tenant-b"),
            "namespace", n -> n.put("namespace", "other"),
            "future", n -> n.put("activatedAt", NOW.plusNanos(1).toString()),
            "key", n -> n.put("signatureKeyId", "unknown"))
            .entrySet().stream().map(e -> DynamicTest.dynamicTest(e.getKey(), () -> {
                ObjectNode n = JSON.valueToTree(kill(false)); e.getValue().accept(n);
                var k = KillSwitchDirectiveSigner.sign(RELEASE.getPrivate(), JSON.treeToValue(n, KillSwitchDirective.class));
                assertThrows(IllegalArgumentException.class, () -> verifier.kill("tenant-a", k, NOW));
            }));
    }
    @Test void clearFreshnessBoundariesUseOriginalSignedTime() {
        var k = kill(false); var lifetime = Duration.ofSeconds(10);
        assertEquals(NOW.plusSeconds(10), ReferralRuntimeDirectiveVerifier.clearUntil(k, lifetime, NOW));
        assertEquals(NOW.plusSeconds(10), ReferralRuntimeDirectiveVerifier.clearUntil(k, lifetime, NOW.plusSeconds(9)));
        assertNull(ReferralRuntimeDirectiveVerifier.clearUntil(k, lifetime, NOW.plusSeconds(10)));
        assertNull(ReferralRuntimeDirectiveVerifier.clearUntil(k, lifetime, NOW.minusNanos(1)));
        assertThrows(IllegalArgumentException.class, () -> ReferralRuntimeDirectiveVerifier.clearUntil(k, Duration.ZERO, NOW));
        assertThrows(IllegalArgumentException.class, () -> ReferralRuntimeDirectiveVerifier.clearUntil(k, Duration.ofSeconds(11), NOW));
        ObjectNode n = JSON.valueToTree(k); n.put("enabled", true);
        assertThrows(IllegalArgumentException.class, () -> verifier.kill("tenant-a", JSON.treeToValue(n, KillSwitchDirective.class), NOW));
    }
}
