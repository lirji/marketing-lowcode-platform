package com.acme.marketing.contracts.artifact;

import com.acme.marketing.contracts.release.ArtifactReference;
import java.security.PublicKey;
import java.util.Map;

public final class PinnedArtifactVerifier {
    private final Map<String, PublicKey> keys;

    public PinnedArtifactVerifier(Map<String, PublicKey> keys) {
        this.keys = Map.copyOf(keys);
    }

    public boolean verify(String tenantId, ArtifactReference reference) {
        PublicKey key = keys.get(reference.signatureKeyId());
        return key != null && ArtifactAttestation.verify(key, tenantId, reference);
    }
}
