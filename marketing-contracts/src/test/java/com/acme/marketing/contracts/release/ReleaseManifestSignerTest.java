package com.acme.marketing.contracts.release;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.acme.marketing.platform.crypto.Ed25519;
import com.acme.marketing.platform.identity.TenantId;
import java.security.KeyPair;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ReleaseManifestSignerTest {
    @Test
    void signatureCoversGenerationAndArtifactClosure() {
        KeyPair keys = Ed25519.generateKeyPair();
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        ArtifactReference artifact = new ArtifactReference("a1", "OFFER", "s3://bucket/a1",
                "sha256:" + "a".repeat(64), "sha256:" + "b".repeat(64),
                "compiler-key", "artifact-signature", "offer-abi/1",
                "offer-definition", 3);
        ReleaseManifest unsigned = new ReleaseManifest("m1", new TenantId("tenant-a"), "prod", "cell-a",
                "decision", "default", 2, 1, List.of(2L), List.of(1L), List.of(artifact),
                Map.of("graph", "1"), 500, now, now.plusSeconds(3600), "operator", List.of("case-1"), now, "");
        ReleaseManifest signed = new ReleaseManifestSigner().sign(unsigned, keys.getPrivate());

        assertTrue(new ReleaseManifestSigner().verify(signed, keys.getPublic()));
        ReleaseManifest changed = new ReleaseManifest(signed.manifestId(), signed.tenantId(), signed.environment(),
                signed.cell(), signed.runtime(), signed.namespace(), 3, signed.stableGeneration(),
                signed.canaryGenerations(), signed.retainedGenerations(), signed.artifacts(), signed.schemaVersions(),
                signed.canaryBasisPoints(), signed.activationAt(), signed.expiresAt(), signed.createdBy(),
                signed.approvalCaseIds(), signed.createdAt(), signed.signature());
        assertFalse(new ReleaseManifestSigner().verify(changed, keys.getPublic()));
    }
}
