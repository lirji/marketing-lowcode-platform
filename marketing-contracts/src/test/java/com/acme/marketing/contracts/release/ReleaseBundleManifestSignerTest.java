package com.acme.marketing.contracts.release;

import static org.junit.jupiter.api.Assertions.*;

import com.acme.marketing.platform.crypto.Ed25519;
import com.acme.marketing.platform.identity.TenantId;
import java.security.KeyPair;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 组合签名必须承诺完整成员内容，不能把仍带旧签名的变造清单当作原文。 */
class ReleaseBundleManifestSignerTest {
    private final KeyPair keys = Ed25519.generateKeyPair();
    private final Instant now = Instant.parse("2026-11-17T00:00:00Z");

    @Test void canonicalOrderIsStableAndDifferentKeysCannotVerify() {
        var members = List.of(member("m-b", "journey"), member("m-a", "decision"));
        var signed = ReleaseBundleManifestSigner.sign(keys.getPrivate(), bundle(members, ""));
        var reordered = ReleaseBundleManifestSigner.sign(keys.getPrivate(), bundle(members.reversed(), ""));
        assertEquals(signed, reordered);
        assertTrue(ReleaseBundleManifestSigner.verify(keys.getPublic(), signed));
        assertFalse(ReleaseBundleManifestSigner.verify(Ed25519.generateKeyPair().getPublic(), signed));
        assertFalse(ReleaseBundleManifestSigner.verify(null, signed));
        assertFalse(ReleaseBundleManifestSigner.verify(keys.getPublic(), bundle(members, "%malformed")));
        assertFalse(ReleaseBundleManifestSigner.verify(keys.getPublic(), bundle(members, members.getFirst().signature())));
    }

    @Test void memberMutationWithUnchangedSignatureAndContentHashIsRejected() {
        var first = member("m-a", "decision"); var second = member("m-b", "journey");
        var signed = ReleaseBundleManifestSigner.sign(keys.getPrivate(), bundle(List.of(first, second), ""));
        var changed = new ReleaseManifest(first.manifestId(), first.tenantId(), first.environment(), first.cell(),
                first.runtime(), first.namespace(), first.generation(), first.stableGeneration(),
                first.canaryGenerations(), first.retainedGenerations(), first.artifacts(), Map.of("terms", "altered"),
                first.canaryBasisPoints(), first.activationAt(), first.expiresAt(), "changed-author",
                first.approvalCaseIds(), first.createdAt(), first.signature());
        var forged = bundle(List.of(changed, second), signed.signature());
        assertEquals(signed.contentHash(), forged.contentHash());
        assertFalse(ReleaseBundleManifestSigner.verify(keys.getPublic(), forged));
    }

    @Test void everyOuterIdentityAndTimestampIsBound() {
        var signed = ReleaseBundleManifestSigner.sign(keys.getPrivate(), bundle(List.of(member("a", "decision"), member("b", "journey")), ""));
        for (var forged : List.of(
                copy(signed, "different-bundle", signed.campaignId(), signed.version(), signed.createdAt(), signed.signatureKeyId()),
                copy(signed, signed.bundleId(), "different-campaign", signed.version(), signed.createdAt(), signed.signatureKeyId()),
                copy(signed, signed.bundleId(), signed.campaignId(), 2, signed.createdAt(), signed.signatureKeyId()),
                copy(signed, signed.bundleId(), signed.campaignId(), 1, now.plusSeconds(1), signed.signatureKeyId()),
                copy(signed, signed.bundleId(), signed.campaignId(), 1, now, "other-key"))) {
            assertFalse(ReleaseBundleManifestSigner.verify(keys.getPublic(), forged));
        }
    }

    @Test void rejectsMissingDuplicateAndMismatchedMembers() {
        var first = member("a", "decision");
        assertThrows(IllegalArgumentException.class, () -> bundle(List.of(first), ""));
        assertThrows(IllegalArgumentException.class, () -> bundle(List.of(first, first), ""));
        assertThrows(IllegalArgumentException.class, () -> bundle(List.of(first, member("b", "decision")), ""));
        var valid = bundle(List.of(first, member("b", "journey")), "");
        assertThrows(IllegalArgumentException.class, () -> new ReleaseBundleManifest(valid.bundleId(), new TenantId("other"),
                valid.campaignId(), 1, valid.environment(), valid.cell(), valid.contentHash(), valid.members(), now,
                valid.expiresAt(), "release-key", ""));
        assertThrows(IllegalArgumentException.class, () -> new ReleaseBundleManifest(valid.bundleId(), valid.tenantId(),
                valid.campaignId(), 1, valid.environment(), valid.cell(), valid.contentHash(), valid.members(), now,
                valid.expiresAt().plusSeconds(1), "release-key", ""));
    }

    private ReleaseBundleManifest copy(ReleaseBundleManifest m, String id, String campaign, long version, Instant created, String key) {
        return new ReleaseBundleManifest(id, m.tenantId(), campaign, version, m.environment(), m.cell(),
                ReleaseBundleManifest.contentHash(m.tenantId().value(), campaign, m.members()), m.members(), created,
                m.expiresAt(), key, m.signature());
    }
    private ReleaseBundleManifest bundle(List<ReleaseManifest> members, String signature) {
        return new ReleaseBundleManifest("bundle", new TenantId("tenant"), "campaign", 1, "local", "cell-a",
                ReleaseBundleManifest.contentHash("tenant", "campaign", members), members, now, now.plusSeconds(600), "release-key", signature);
    }
    private ReleaseManifest member(String id, String runtime) {
        var artifact = new ArtifactReference("a-" + id, "OFFER", "s3://bucket/" + id,
                "sha256:" + "a".repeat(64), "sha256:" + "b".repeat(64), "compiler", "attestation", "abi/1", "def-" + id, 1);
        return new ReleaseManifestSigner().sign(new ReleaseManifest(id, new TenantId("tenant"), "local", "cell-a",
                runtime, "main", 1, 0, List.of(), List.of(), List.of(artifact), Map.of(), 0, now, now.plusSeconds(600),
                "author", List.of("case"), now.minusSeconds(1), ""), keys.getPrivate());
    }
}
