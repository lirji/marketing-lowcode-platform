package com.acme.marketing.contracts.release;

import java.security.PublicKey;
import java.time.Instant;
import java.util.Map;

/** 校验当前可信激活授权；不读取或修改运行指针，也不宣称已有应用能力。 */
public final class PinnedReleaseBundleActivationVerifier {
    private static final long MAX_CLOCK_SKEW_SECONDS = 60;
    private final Map<String, PublicKey> keys;

    /** 控制面换签时内外层可以使用信任集合内的不同密钥。 */
    public PinnedReleaseBundleActivationVerifier(Map<String, PublicKey> keys) { this.keys = Map.copyOf(keys); }

    /** 验证通过后仍需当前安装状态、序号CAS和参与版本绑定，不能直接路由到latest。 */
    public void requireValid(String expectedTenant, ReleaseBundleActivationDirective directive, Instant now) {
        if (directive == null || now == null || !directive.bundle().tenantId().value().equals(expectedTenant)
                || !directive.bundle().expiresAt().isAfter(now)
                || directive.authorizedAt().isAfter(now.plusSeconds(MAX_CLOCK_SKEW_SECONDS))
                || !ReleaseBundleActivationSigner.verify(keys.get(directive.signatureKeyId()), directive)
                || !ReleaseBundleManifestSigner.verify(keys.get(directive.bundle().signatureKeyId()), directive.bundle())
                || directive.bundle().members().stream().anyMatch(member -> keys.values().stream()
                        .noneMatch(key -> new ReleaseManifestSigner().verify(member, key))))
            throw ReleaseBundleProtocolError.ACTIVATION_INVALID.conflict();
    }
}
