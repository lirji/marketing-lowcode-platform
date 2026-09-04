package com.acme.marketing.decision.runtime;

import com.acme.marketing.contracts.release.ReleaseManifest;
import com.acme.marketing.contracts.release.ReleaseManifestSigner;
import java.security.PublicKey;
import java.util.Map;

public final class PinnedManifestVerifier implements ManifestVerifier {
    private final Map<String, PublicKey> trustedKeys;

    public PinnedManifestVerifier(Map<String, PublicKey> trustedKeys) {
        this.trustedKeys = Map.copyOf(trustedKeys);
    }

    @Override
    public boolean verify(String keyId, ReleaseManifest manifest) {
        PublicKey key = trustedKeys.get(keyId);
        return key != null && new ReleaseManifestSigner().verify(manifest, key);
    }
}
