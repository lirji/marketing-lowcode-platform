package com.acme.marketing.contracts.release;

import static org.junit.jupiter.api.Assertions.*;

import com.acme.marketing.platform.crypto.Ed25519;
import com.acme.marketing.platform.identity.TenantId;
import java.security.KeyPair;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 旧ACK、跨租户及组合上下文重放都不能成为新组合就绪证明。 */
class ReleaseBundleAckSignerTest {
    private final KeyPair keys = Ed25519.generateKeyPair();
    private final Instant now = Instant.parse("2026-11-17T00:00:00Z");

    @Test void bothSignaturesAndAllBundleFieldsMustMatch() {
        RuntimeAck inner = inner(100, "");
        var unsigned = new ReleaseBundleAck("bundle", "campaign", 1, "a".repeat(64), "member-signature", inner, "");
        var signed = ReleaseBundleAckSigner.sign(keys.getPrivate(), "tenant", unsigned);
        assertTrue(ReleaseBundleAckSigner.verify(keys.getPublic(), "tenant", signed));
        assertFalse(ReleaseBundleAckSigner.verify(keys.getPublic(), "other-tenant", signed));
        assertFalse(ReleaseBundleAckSigner.verify(Ed25519.generateKeyPair().getPublic(), "tenant", signed));
        for (var changed : List.of(
                new ReleaseBundleAck("other-bundle", "campaign", 1, unsigned.contentHash(), unsigned.memberSignature(), inner, signed.signature()),
                new ReleaseBundleAck("bundle", "other-campaign", 1, unsigned.contentHash(), unsigned.memberSignature(), inner, signed.signature()),
                new ReleaseBundleAck("bundle", "campaign", 2, unsigned.contentHash(), unsigned.memberSignature(), inner, signed.signature()),
                new ReleaseBundleAck("bundle", "campaign", 1, "b".repeat(64), unsigned.memberSignature(), inner, signed.signature()),
                new ReleaseBundleAck("bundle", "campaign", 1, unsigned.contentHash(), "other-member", inner, signed.signature()),
                new ReleaseBundleAck("bundle", "campaign", 1, unsigned.contentHash(), unsigned.memberSignature(), inner(200, ""), signed.signature()),
                new ReleaseBundleAck("bundle", "campaign", 1, unsigned.contentHash(), unsigned.memberSignature(), inner, inner.signature()),
                new ReleaseBundleAck("bundle", "campaign", 1, unsigned.contentHash(), unsigned.memberSignature(), inner, "%invalid"))) {
            assertFalse(ReleaseBundleAckSigner.verify(keys.getPublic(), "tenant", changed));
        }
        // 即使外层是可信密钥签发，内层被伪造仍必须拒绝。
        var forged = new ReleaseBundleAck("bundle", "campaign", 1, unsigned.contentHash(), unsigned.memberSignature(), inner(100, "forged"), "");
        assertFalse(ReleaseBundleAckSigner.verify(keys.getPublic(), "tenant", ReleaseBundleAckSigner.sign(keys.getPrivate(), "tenant", forged)));
    }

    @Test void pinnedProofRejectsTimeTenantAndUnknownKeysBeforeRuntimeLookup() {
        var member = member("a", "decision"); var other = member("b", "journey");
        var members = List.of(member, other);
        var proof = ReleaseBundleManifestSigner.sign(keys.getPrivate(), new ReleaseBundleManifest("bundle", new TenantId("tenant"),
                "campaign", 1, "local", "cell-a", ReleaseBundleManifest.contentHash("tenant", "campaign", members), members,
                now, now.plusSeconds(600), "key", ""));
        var verifier = new PinnedReleaseBundleVerifier(Map.of("key", keys.getPublic()));
        assertDoesNotThrow(() -> verifier.requireValid("tenant", proof, now));
        assertThrows(com.acme.marketing.platform.error.ConflictException.class, () -> verifier.requireValid("tenant", proof, now.plusSeconds(600)));
        assertThrows(com.acme.marketing.platform.error.ConflictException.class, () -> verifier.requireValid("other", proof, now));
        assertThrows(com.acme.marketing.platform.error.ConflictException.class, () -> verifier.requireValid("tenant", proof, now.minusSeconds(61)));
        assertThrows(com.acme.marketing.platform.error.ConflictException.class, () -> new PinnedReleaseBundleVerifier(Map.of()).requireValid("tenant", proof, now));
        assertThrows(IllegalArgumentException.class, () -> ReleaseBundleAck.unsigned(proof, member, inner(100, "")));
    }

    private RuntimeAck inner(long capacity, String signature) {
        var ack = new RuntimeAck("manifest", 1, "runtime-1", "cell-a", RuntimeAck.Status.READY,
                "build", Set.of("abi"), Set.of("artifact"), capacity, now, "key", signature);
        return signature.isEmpty() ? RuntimeAckSigner.sign(keys.getPrivate(), "tenant", ack) : ack;
    }
    private ReleaseManifest member(String id, String runtime) {
        var reference = new ArtifactReference("artifact-" + id, "OFFER", "s3://bucket/" + id, "sha256:" + "a".repeat(64),
                "sha256:" + "b".repeat(64), "compiler", "signature", "abi", "def", 1);
        return new ReleaseManifestSigner().sign(new ReleaseManifest(id, new TenantId("tenant"), "local", "cell-a", runtime,
                "main", 1, 0, List.of(), List.of(), List.of(reference), Map.of(), 0, now, now.plusSeconds(600),
                "author", List.of("case"), now, ""), keys.getPrivate());
    }
}
