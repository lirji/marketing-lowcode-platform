package com.acme.marketing.contracts.release;

import java.security.PublicKey;
import java.time.Instant;
import java.util.Map;

/** 按控制面既有信任集合核验组合；旧单清单签名不能充当组合签名。 */
public final class PinnedReleaseBundleVerifier {
    private static final long MAX_CLOCK_SKEW_SECONDS = 60;
    private final Map<String, PublicKey> keys;

    /** 防止调用方在验签期间修改受信任公钥集合。 */
    public PinnedReleaseBundleVerifier(Map<String, PublicKey> keys) { this.keys = Map.copyOf(keys); }

    /** 先核对租户、时间和签名，调用方随后才能读取本站持久化成员。 */
    public void requireValid(String expectedTenant, ReleaseBundleManifest bundle, Instant now) {
        if (!expectedTenant.equals(bundle.tenantId().value()) || !bundle.expiresAt().isAfter(now)
                || bundle.createdAt().isAfter(now.plusSeconds(MAX_CLOCK_SKEW_SECONDS))
                || !ReleaseBundleManifestSigner.verify(keys.get(bundle.signatureKeyId()), bundle))
            throw ReleaseBundleProtocolError.PROOF_INVALID.conflict();
    }
}
