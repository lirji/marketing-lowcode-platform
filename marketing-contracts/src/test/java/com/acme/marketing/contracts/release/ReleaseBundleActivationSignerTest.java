package com.acme.marketing.contracts.release;

import static org.junit.jupiter.api.Assertions.*;

import com.acme.marketing.platform.crypto.Ed25519;
import com.acme.marketing.platform.identity.TenantId;
import java.security.KeyPair;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** 激活授权与准备证明不能混用，完整内外内容、范围和时间都必须可验证。 */
class ReleaseBundleActivationSignerTest {
    private final KeyPair original = Ed25519.generateKeyPair();
    private final KeyPair rotated = Ed25519.generateKeyPair();
    private final Instant now = Instant.parse("2026-11-17T00:00:00Z");
    private final ObjectMapper mapper = new ObjectMapper();
    private final PinnedReleaseBundleActivationVerifier verifier = new PinnedReleaseBundleActivationVerifier(
            Map.of("original", original.getPublic(), "rotated", rotated.getPublic()));

    @Test void rotationAcceptsTrustedInnerAndOuterKeysWithStableWireActions() {
        var directive = signed();
        assertDoesNotThrow(() -> verifier.requireValid("tenant", directive, now));
        assertEquals(directive, mapper.readValue(mapper.writeValueAsString(directive), ReleaseBundleActivationDirective.class));
        assertEquals("ACTIVATE", directive.action());
        var rollback = tree(directive); rollback.put("action", "ROLLBACK"); rollback.put("activationSequence", 3);
        var signedRollback = ReleaseBundleActivationSigner.sign(rotated.getPrivate(), read(rollback));
        assertDoesNotThrow(() -> verifier.requireValid("tenant", signedRollback, now));
        assertEquals(ReleaseBundleActivationDirective.Action.ROLLBACK.code(), signedRollback.action());
        // 回滚使用较新的序号，不要求目标组合版本随序号一起增加。
        assertEquals(1, signedRollback.bundle().version());
    }

    @Test void everyOuterFieldAndScopeIsCommittedByTheSignature() {
        var directive = signed();
        for (var change : Map.of("directiveId", "changed", "action", "ROLLBACK", "organizationId", "org-b",
                "shopId", "shop-b", "authorizedAt", now.plusSeconds(1).toString(), "authorizedBy", "other",
                "signatureKeyId", "original").entrySet()) {
            var forged = tree(directive); forged.put(change.getKey(), change.getValue());
            assertFalse(ReleaseBundleActivationSigner.verify(rotated.getPublic(), read(forged)), change.getKey());
        }
        var sequence = tree(directive); sequence.put("activationSequence", 2);
        assertFalse(ReleaseBundleActivationSigner.verify(rotated.getPublic(), read(sequence)));
        for (String field : List.of("bundleId", "signatureKeyId", "signature")) {
            var forged = tree(directive); ((ObjectNode) forged.path("bundle")).put(field, "changed");
            assertFalse(ReleaseBundleActivationSigner.verify(rotated.getPublic(), read(forged)), field);
        }
        var version = tree(directive); ((ObjectNode) version.path("bundle")).put("version", 2);
        assertFalse(ReleaseBundleActivationSigner.verify(rotated.getPublic(), read(version)));
    }

    @Test void wholeMemberMutationCannotHideBehindOriginalContentHashOrOuterResigning() {
        var directive = signed(); var altered = tree(directive);
        ((ObjectNode) altered.path("bundle").path("members").get(0)).put("createdBy", "altered");
        var forged = read(altered);
        assertEquals(directive.bundle().contentHash(), forged.bundle().contentHash());
        assertFalse(ReleaseBundleActivationSigner.verify(rotated.getPublic(), forged));
        // 即使外层被重新签名，接收边界仍拒绝已损坏的内层组合证明。
        var resigned = ReleaseBundleActivationSigner.sign(rotated.getPrivate(), forged);
        assertTrue(ReleaseBundleActivationSigner.verify(rotated.getPublic(), resigned));
        assertRejected(resigned, now);
    }

    @Test void preparationMemberAndWrongKeySignaturesCannotBecomeActivationAuthorization() {
        var directive = signed();
        for (String signature : List.of(directive.bundle().signature(), directive.bundle().members().getFirst().signature(), "%invalid", "")) {
            var forged = tree(directive); forged.put("signature", signature); assertRejected(read(forged), now);
        }
        assertRejected(ReleaseBundleActivationSigner.sign(original.getPrivate(), directive), now);
        assertFalse(ReleaseBundleActivationSigner.verify(null, directive));
        assertThrows(com.acme.marketing.platform.error.ConflictException.class, () ->
                new PinnedReleaseBundleActivationVerifier(Map.of("rotated", rotated.getPublic())).requireValid("tenant", directive, now));
    }

    @Test void tenantExpiryAndFutureClockBoundariesFailClosed() {
        var directive = signed();
        assertThrows(com.acme.marketing.platform.error.ConflictException.class, () -> verifier.requireValid("other", directive, now));
        assertDoesNotThrow(() -> verifier.requireValid("tenant", directive, now.minusSeconds(60)));
        assertRejected(directive, now.minusSeconds(61));
        assertDoesNotThrow(() -> verifier.requireValid("tenant", directive, directive.bundle().expiresAt().minusNanos(1)));
        assertRejected(directive, directive.bundle().expiresAt());
        assertRejected(directive, null);
        assertRejected(null, now);
    }

    @Test void recordRejectsInvalidActionsScopeAndAuthorizationBeforeMemberActivation() {
        var directive = signed();
        for (var change : Map.of("action", "UNKNOWN", "organizationId", "*", "shopId", "*",
                "authorizedAt", now.minusSeconds(1).toString()).entrySet()) {
            var invalid = tree(directive); invalid.put(change.getKey(), change.getValue());
            assertThrows(RuntimeException.class, () -> read(invalid), change.getKey());
        }
        var missingShop = tree(directive); missingShop.remove("shopId"); assertThrows(RuntimeException.class, () -> read(missingShop));
        var futureMember = tree(directive);
        ((ObjectNode) futureMember.path("bundle").path("members").get(0)).put("activationAt", now.plusSeconds(1).toString());
        assertThrows(RuntimeException.class, () -> read(futureMember));
        var zero = tree(directive); zero.put("activationSequence", 0); assertThrows(RuntimeException.class, () -> read(zero));
        var expiry = tree(directive); expiry.put("authorizedAt", directive.bundle().expiresAt().toString());
        assertThrows(RuntimeException.class, () -> read(expiry));
        var unicode = tree(directive); unicode.put("organizationId", "门店/区域"); unicode.put("authorizedBy", "https://idp.example/user/1"); unicode.put("shopId", "");
        assertEquals("", read(unicode).shopId());
    }

    @Test void trustedNewOuterLayersCannotRestoreRevokedMemberSigningKey() {
        var directive = signed();
        var old = directive.bundle();
        var currentInner = ReleaseBundleManifestSigner.sign(rotated.getPrivate(), new ReleaseBundleManifest(old.bundleId(), old.tenantId(),
                old.campaignId(), old.version(), old.environment(), old.cell(), old.contentHash(), old.members(), old.createdAt(), old.expiresAt(), "rotated", ""));
        var renewed = ReleaseBundleActivationSigner.sign(rotated.getPrivate(), new ReleaseBundleActivationDirective(directive.directiveId(), currentInner,
                directive.activationSequence(), directive.action(), directive.organizationId(), directive.shopId(), now, "author", "rotated", ""));
        assertThrows(com.acme.marketing.platform.error.ConflictException.class, () ->
                new PinnedReleaseBundleActivationVerifier(Map.of("rotated", rotated.getPublic())).requireValid("tenant", renewed, now));
    }

    private void assertRejected(ReleaseBundleActivationDirective directive, Instant at) {
        var failure = assertThrows(com.acme.marketing.platform.error.ConflictException.class, () -> verifier.requireValid("tenant", directive, at));
        assertEquals("RELEASE_BUNDLE_ACTIVATION_INVALID", failure.code());
    }
    private ObjectNode tree(ReleaseBundleActivationDirective directive) { return (ObjectNode) mapper.readTree(mapper.writeValueAsString(directive)); }
    private ReleaseBundleActivationDirective read(ObjectNode tree) { return mapper.readValue(tree.toString(), ReleaseBundleActivationDirective.class); }
    private ReleaseBundleActivationDirective signed() {
        var members = List.of(member("decision"), member("journey"));
        var bundle = ReleaseBundleManifestSigner.sign(original.getPrivate(), new ReleaseBundleManifest("bundle", new TenantId("tenant"), "campaign", 1,
                "local", "cell", ReleaseBundleManifest.contentHash("tenant", "campaign", members), members, now, now.plusSeconds(600), "original", ""));
        return ReleaseBundleActivationSigner.sign(rotated.getPrivate(), new ReleaseBundleActivationDirective("directive", bundle, 1,
                ReleaseBundleActivationDirective.Action.ACTIVATE.code(), "org", "shop", now, "author", "rotated", ""));
    }
    private ReleaseManifest member(String runtime) {
        var artifact = new ArtifactReference("a-" + runtime, "OFFER", "memory:test", "sha256:" + "a".repeat(64),
                "sha256:" + "b".repeat(64), "compiler", "attestation", "abi/1", "def-" + runtime, 1);
        return new ReleaseManifestSigner().sign(new ReleaseManifest("m-" + runtime, new TenantId("tenant"), "local", "cell", runtime,
                "main", 1, 0, List.of(), List.of(), List.of(artifact), Map.of(), 0, now, now.plusSeconds(600), "author", List.of("approval"), now, ""), original.getPrivate());
    }
}
