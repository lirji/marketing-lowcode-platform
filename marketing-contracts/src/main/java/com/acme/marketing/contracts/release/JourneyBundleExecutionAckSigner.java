package com.acme.marketing.contracts.release;

import com.acme.marketing.platform.crypto.CanonicalMapCodec;
import com.acme.marketing.platform.crypto.Ed25519;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/** 真实Flink执行角色签名，Web安装ACK和Control探针签名均不能替代。 */
public final class JourneyBundleExecutionAckSigner {
    private static final String PURPOSE = "marketing.journey-bundle-execution-ack/v1";
    private JourneyBundleExecutionAckSigner() { }
    /** 覆盖原探针完整内容和签名，防止换挑战、拼接子任务或替换部署身份。 */
    public static JourneyBundleExecutionAck sign(PrivateKey key, JourneyBundleExecutionAck value) {
        String signature = Base64.getUrlEncoder().withoutPadding().encodeToString(Ed25519.sign(key, canonical(value)));
        return new JourneyBundleExecutionAck(value.probe(), value.executionProtocol(), value.jobId(), value.parallelism(), value.subtaskIndex(),
                value.attemptNumber(), value.buildDigest(), value.acknowledgedAt(), value.signatureKeyId(), signature);
    }
    /** 这里只验证执行角色；可信目标、源授权和时间由完整验证器检查。 */
    public static boolean verify(PublicKey key, JourneyBundleExecutionAck value) {
        try {
            return key != null && value != null && !value.signature().isEmpty()
                    && Ed25519.verify(key, canonical(value), Base64.getUrlDecoder().decode(value.signature()));
        } catch (IllegalArgumentException malformed) { return false; }
    }
    private static byte[] canonical(JourneyBundleExecutionAck value) {
        Map<String, String> fields = new LinkedHashMap<>(); fields.put("purpose", PURPOSE);
        fields.put("probe", Base64.getEncoder().encodeToString(JourneyBundleExecutionProbeSigner.canonical(value.probe())));
        fields.put("probeSignature", value.probe().signature()); fields.put("executionProtocol", value.executionProtocol());
        fields.put("jobId", value.jobId()); fields.put("parallelism", Integer.toString(value.parallelism()));
        fields.put("subtaskIndex", Integer.toString(value.subtaskIndex())); fields.put("attemptNumber", Integer.toString(value.attemptNumber()));
        fields.put("buildDigest", value.buildDigest()); fields.put("acknowledgedAt", value.acknowledgedAt().toString());
        fields.put("signatureKeyId", value.signatureKeyId()); return CanonicalMapCodec.encode(fields);
    }
}
