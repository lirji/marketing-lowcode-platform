package com.acme.marketing.contracts.release;

import com.acme.marketing.platform.crypto.CanonicalMapCodec;
import com.acme.marketing.platform.crypto.Ed25519;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/** 探针用途独立于整体激活和参与，不能用普通清单签名授权探测。 */
public final class JourneyBundleExecutionProbeSigner {
    private static final String PURPOSE = "marketing.journey-bundle-execution-probe/v1";
    private JourneyBundleExecutionProbeSigner() { }
    /** Control只签固定目标与精确来源，签名本身不证明目标作业已经处理。 */
    public static JourneyBundleExecutionProbe sign(PrivateKey key, JourneyBundleExecutionProbe value) {
        String signature = Base64.getUrlEncoder().withoutPadding().encodeToString(Ed25519.sign(key, canonical(value)));
        return new JourneyBundleExecutionProbe(value.probeId(), value.tenantId(), value.campaignId(), value.environment(), value.cell(), value.namespace(),
                value.memberManifestId(), value.activationSequence(), value.activationSignature(), value.targetJobId(), value.targetParallelism(),
                value.participationKeyId(), value.issuedAt(), value.expiresAt(), value.signatureKeyId(), signature);
    }
    /** 这里只验证探针用途签名；消费边界仍必须核对真实部署和完整源授权。 */
    public static boolean verify(PublicKey key, JourneyBundleExecutionProbe value) {
        try {
            return key != null && value != null && !value.signature().isEmpty()
                    && Ed25519.verify(key, canonical(value), Base64.getUrlDecoder().decode(value.signature()));
        } catch (IllegalArgumentException malformed) { return false; }
    }
    static byte[] canonical(JourneyBundleExecutionProbe value) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("purpose", PURPOSE); fields.put("probeId", value.probeId()); fields.put("tenantId", value.tenantId().value());
        fields.put("campaignId", value.campaignId()); fields.put("environment", value.environment()); fields.put("cell", value.cell());
        fields.put("namespace", value.namespace()); fields.put("memberManifestId", value.memberManifestId());
        fields.put("activationSequence", Long.toString(value.activationSequence())); fields.put("activationSignature", value.activationSignature());
        fields.put("targetJobId", value.targetJobId()); fields.put("targetParallelism", Integer.toString(value.targetParallelism()));
        fields.put("participationKeyId", value.participationKeyId()); fields.put("issuedAt", value.issuedAt().toString());
        fields.put("expiresAt", value.expiresAt().toString()); fields.put("signatureKeyId", value.signatureKeyId());
        return CanonicalMapCodec.encode(fields);
    }
}
