package com.acme.marketing.contracts.artifact;

import com.acme.marketing.contracts.release.ArtifactReference;
import com.acme.marketing.platform.crypto.Digests;
import java.time.Instant;
import java.util.Map;

public record ArtifactBundle(
        String artifactId,
        String tenantId,
        String definitionId,
        long definitionVersion,
        String type,
        String abi,
        byte[] payload,
        String checksum,
        String sourceDigest,
        String signatureKeyId,
        String signature,
        Map<String, String> metadata,
        Instant compiledAt) {
    public ArtifactBundle {
        if (artifactId == null || tenantId == null || definitionId == null || definitionVersion < 1
                || type == null || abi == null || payload == null || compiledAt == null) {
            throw new IllegalArgumentException("artifact bundle is incomplete");
        }
        payload = payload.clone();
        String actual = "sha256:" + Digests.sha256Hex(payload);
        if (!actual.equals(checksum)) {
            throw new IllegalArgumentException("artifact checksum does not match payload");
        }
        if (sourceDigest == null || !sourceDigest.matches("sha256:[a-f0-9]{64}")) {
            throw new IllegalArgumentException("artifact source digest is invalid");
        }
        if (signatureKeyId == null || signatureKeyId.isBlank() || signature == null || signature.isBlank()) {
            throw new IllegalArgumentException("artifact signature is required");
        }
        metadata = Map.copyOf(metadata == null ? Map.of() : metadata);
    }

    @Override
    public byte[] payload() {
        return payload.clone();
    }

    public ArtifactReference reference(String uri) {
        return new ArtifactReference(artifactId, type, uri, checksum, sourceDigest, signatureKeyId, signature, abi,
                definitionId, definitionVersion);
    }
}
