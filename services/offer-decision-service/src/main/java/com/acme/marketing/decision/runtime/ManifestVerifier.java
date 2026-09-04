package com.acme.marketing.decision.runtime;

import com.acme.marketing.contracts.release.ReleaseManifest;

public interface ManifestVerifier {
    boolean verify(String keyId, ReleaseManifest manifest);
}
