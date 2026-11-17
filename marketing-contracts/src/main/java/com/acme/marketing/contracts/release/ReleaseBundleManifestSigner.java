package com.acme.marketing.contracts.release;

import com.acme.marketing.platform.crypto.CanonicalMapCodec;
import com.acme.marketing.platform.crypto.Ed25519;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/** 用独立用途的签名覆盖整个组合，防止成员保留旧签名却篡改字段。 */
public final class ReleaseBundleManifestSigner {
    private static final String PURPOSE = "marketing.activity-release-manifest/v1";
    private ReleaseBundleManifestSigner() { }

    /** 外层签名仅证明内容来源；激活必须由另一份受门禁保护的命令授权。 */
    public static ReleaseBundleManifest sign(PrivateKey key, ReleaseBundleManifest unsigned) {
        String signature = Base64.getUrlEncoder().withoutPadding().encodeToString(Ed25519.sign(key, canonical(unsigned)));
        return new ReleaseBundleManifest(unsigned.bundleId(), unsigned.tenantId(), unsigned.campaignId(),
                unsigned.version(), unsigned.environment(), unsigned.cell(), unsigned.contentHash(), unsigned.members(),
                unsigned.createdAt(), unsigned.expiresAt(), unsigned.signatureKeyId(), signature);
    }

    /** 格式错误与未知签名均为不可采信；业务时间和租户范围由调用边界校验。 */
    public static boolean verify(PublicKey key, ReleaseBundleManifest manifest) {
        try {
            return key != null && !manifest.signature().isBlank() && Ed25519.verify(key, canonical(manifest),
                    Base64.getUrlDecoder().decode(manifest.signature()));
        } catch (IllegalArgumentException malformed) { return false; }
    }

    static byte[] canonical(ReleaseBundleManifest manifest) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("purpose", PURPOSE);
        values.put("bundleId", manifest.bundleId()); values.put("tenantId", manifest.tenantId().value());
        values.put("campaignId", manifest.campaignId()); values.put("version", Long.toString(manifest.version()));
        values.put("environment", manifest.environment()); values.put("cell", manifest.cell());
        values.put("contentHash", manifest.contentHash()); values.put("createdAt", manifest.createdAt().toString());
        values.put("expiresAt", manifest.expiresAt().toString()); values.put("signatureKeyId", manifest.signatureKeyId());
        for (int i = 0; i < manifest.members().size(); i++) {
            ReleaseManifest member = manifest.members().get(i);
            values.put("member." + i + ".content", Base64.getEncoder().encodeToString(ReleaseManifestSigner.canonical(member)));
            values.put("member." + i + ".signature", member.signature());
        }
        return CanonicalMapCodec.encode(values);
    }
}
