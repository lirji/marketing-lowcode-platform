package com.acme.marketing.contracts.release;

import com.acme.marketing.platform.crypto.CanonicalMapCodec;
import com.acme.marketing.platform.crypto.Ed25519;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/** 参与事实使用独立用途，防止Offer、发布授权或READY签名被提升为版本绑定凭证。 */
public final class ReleaseBundleParticipationSigner {
    private static final String PURPOSE = "marketing.activity-bundle-participation/v1";
    private ReleaseBundleParticipationSigner() { }

    /** 仅签署调用者已持久确定的绑定；此纯函数不能代替准入指针和数据库唯一约束。 */
    public static ReleaseBundleParticipation sign(PrivateKey key, ReleaseBundleParticipation value) {
        String signature = Base64.getUrlEncoder().withoutPadding().encodeToString(Ed25519.sign(key, canonical(value)));
        return new ReleaseBundleParticipation(value.bindingId(), value.tenantId(), value.campaignId(), value.environment(),
                value.cell(), value.participationId(), value.subjectHash(), value.directiveId(), value.activationSequence(),
                value.activationSignature(), value.bundleId(), value.bundleVersion(), value.contentHash(), value.admittedAt(),
                value.signatureKeyId(), signature);
    }

    /** 这里只验证参与签名；消费边界必须通过PinnedReleaseBundleParticipationVerifier核对来源和可信范围。 */
    public static boolean verify(PublicKey key, ReleaseBundleParticipation value) {
        try {
            return key != null && value != null && !value.signature().isEmpty()
                    && Ed25519.verify(key, canonical(value), Base64.getUrlDecoder().decode(value.signature()));
        } catch (IllegalArgumentException malformed) { return false; }
    }

    private static byte[] canonical(ReleaseBundleParticipation value) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("purpose", PURPOSE); values.put("bindingId", value.bindingId());
        values.put("tenantId", value.tenantId().value()); values.put("campaignId", value.campaignId());
        values.put("environment", value.environment()); values.put("cell", value.cell());
        values.put("participationId", value.participationId()); values.put("subjectHash", value.subjectHash());
        values.put("directiveId", value.directiveId()); values.put("activationSequence", Long.toString(value.activationSequence()));
        values.put("activationSignature", value.activationSignature()); values.put("bundleId", value.bundleId());
        values.put("bundleVersion", Long.toString(value.bundleVersion())); values.put("contentHash", value.contentHash());
        values.put("admittedAt", value.admittedAt().toString()); values.put("signatureKeyId", value.signatureKeyId());
        return CanonicalMapCodec.encode(values);
    }
}
