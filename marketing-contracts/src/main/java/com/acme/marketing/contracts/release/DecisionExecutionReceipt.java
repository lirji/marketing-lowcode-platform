package com.acme.marketing.contracts.release;

import com.acme.marketing.platform.crypto.CanonicalMapCodec;
import com.acme.marketing.platform.crypto.Ed25519;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;

/** 实例仅在本机完成准入签名和精确成员执行探测后出具此回执。 */
public record DecisionExecutionReceipt(String directiveSignature, long deploymentSequence, String deploymentDigest,
        DecisionDeploymentInventory.Replica replica, String participationKeyId, Instant observedAt, String signature) {
    public static final String PURPOSE = "marketing.decision-participation-and-evaluation/v1";
    public DecisionExecutionReceipt {
        if (!JourneyBundleExecutionProbe.signed(directiveSignature) || deploymentSequence < 1
                || !JourneyBundleExecutionProbe.buildDigest(deploymentDigest) || replica == null
                || !JourneyBundleExecutionProbe.keyId(participationKeyId) || observedAt == null
                || signature == null || (!signature.isEmpty() && !JourneyBundleExecutionProbe.signed(signature)))
            throw new IllegalArgumentException(ReleaseBundleProtocolError.EXECUTION_ACK_INVALID.message());
    }

    /** 独立用途及专属密钥固定进程身份，部署清单摘要已包含物理位置与完整接流量集合。 */
    public DecisionExecutionReceipt sign(PrivateKey key) {
        return new DecisionExecutionReceipt(directiveSignature, deploymentSequence, deploymentDigest, replica,
                participationKeyId, observedAt, Base64.getUrlEncoder().withoutPadding().encodeToString(Ed25519.sign(key, bytes())));
    }

    /** 外层分别验过部署与授权后，再核对具体副本与签发时限。 */
    public void requireValid(DecisionDeploymentInventory inventory, DecisionDeploymentInventory.Replica expected,
            ReleaseBundleActivationDirective source, String admissionKey, PublicKey executionKey, Instant now) {
        if (!directiveSignature.equals(source.signature()) || deploymentSequence != inventory.sequence()
                || !deploymentDigest.equals(inventory.contentDigest()) || !replica.equals(expected)
                || !participationKeyId.equals(admissionKey) || observedAt.isBefore(source.authorizedAt())
                || observedAt.isBefore(inventory.observedAt()) || observedAt.isAfter(now)
                || !now.isBefore(inventory.expiresAt()) || !now.isBefore(source.bundle().expiresAt())
                || executionKey == null || signature.isEmpty()
                || !Ed25519.verify(executionKey, bytes(), Base64.getUrlDecoder().decode(signature)))
            throw ReleaseBundleProtocolError.EXECUTION_ACK_INVALID.conflict();
    }

    private byte[] bytes() {
        return CanonicalMapCodec.encode(Map.of("purpose", PURPOSE, "source", directiveSignature,
                "sequence", Long.toString(deploymentSequence), "inventory", deploymentDigest,
                "instance", replica.instanceId(), "image", replica.imageDigest(), "key", replica.executionKeyId(),
                "admissionKey", participationKeyId, "observedAt", observedAt.toString()));
    }
}
