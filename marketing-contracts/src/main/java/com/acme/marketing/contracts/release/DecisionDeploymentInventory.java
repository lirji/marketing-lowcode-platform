package com.acme.marketing.contracts.release;

import com.acme.marketing.platform.crypto.CanonicalMapCodec;
import com.acme.marketing.platform.crypto.Digests;
import com.acme.marketing.platform.crypto.Ed25519;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/** 部署方对实际接流量副本的短期声明，使用独立签名用途；空集合可表示已全部排空。 */
public record DecisionDeploymentInventory(String environment, String cell, String namespace, long sequence,
        Instant observedAt, Instant expiresAt, List<Replica> replicas, String signatureKeyId, String signature) {
    public static final String PURPOSE = "marketing.decision-serving-inventory/v1";
    public static final int MAX_REPLICAS = 128;
    private static final Duration MAX_AGE = Duration.ofMinutes(5);
    private static final String KEY_ID = "[a-zA-Z0-9_-]{1,64}";

    /** 顺序没有路由含义；拒绝同实例或同执行身份重复计数。 */
    public DecisionDeploymentInventory {
        if (!name(environment, 32) || !name(cell, 64) || !name(namespace, 128) || sequence < 1
                || observedAt == null || expiresAt == null || !expiresAt.isAfter(observedAt)
                || Duration.between(observedAt, expiresAt).compareTo(MAX_AGE) > 0
                || replicas == null || replicas.size() > MAX_REPLICAS
                || signatureKeyId == null || !signatureKeyId.matches(KEY_ID) || signature == null
                || (!signature.isEmpty() && !JourneyBundleExecutionProbe.signed(signature))) throw invalid();
        var instances = new HashSet<String>();
        var signers = new HashSet<String>();
        for (Replica replica : replicas) {
            if (replica == null || !instances.add(replica.instanceId()) || !signers.add(replica.executionKeyId())
                    || signatureKeyId.equals(replica.executionKeyId())) throw invalid();
        }
        replicas = replicas.stream().sorted(Comparator.comparing(Replica::instanceId)).toList();
    }

    /** instanceId 取容器实例身份或 Pod UID，imageDigest 取不可变 OCI 摘要。 */
    public record Replica(String instanceId, String imageDigest, String executionKeyId) {
        public Replica {
            if (!name(instanceId, 128) || !JourneyBundleExecutionProbe.buildDigest(imageDigest)
                    || executionKeyId == null || !executionKeyId.matches(KEY_ID)) throw invalid();
        }
    }

    /** 仅供部署流水线对未签名草稿签发；业务运行端仅接收公钥。 */
    public DecisionDeploymentInventory sign(PrivateKey key) {
        if (!signature.isEmpty()) throw invalid();
        String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(Ed25519.sign(key, canonical()));
        return new DecisionDeploymentInventory(environment, cell, namespace, sequence,
                observedAt, expiresAt, replicas, signatureKeyId, encoded);
    }

    /** 必须由独立的当前部署记录给出位置、序号和内容摘要，旧清单即使仍在有效期内也不能复用。 */
    public void requireCurrent(Map<String, PublicKey> deploymentKeys, String expectedEnvironment, String expectedCell,
            String expectedNamespace, long currentSequence, String currentDigest, Instant now) {
        if (sequence != currentSequence || !contentDigest().equals(currentDigest))
            throw ReleaseBundleProtocolError.DECISION_INVENTORY_INVALID.conflict();
        requireAuthentic(deploymentKeys, expectedEnvironment, expectedCell, expectedNamespace, now);
    }

    /** 登记前仅检查来源、作用域和时限，序号仲裁由本位置的数据库事务完成。 */
    public void requireAuthentic(Map<String, PublicKey> deploymentKeys, String expectedEnvironment,
            String expectedCell, String expectedNamespace, Instant now) {
        PublicKey key = deploymentKeys.get(signatureKeyId);
        if (!environment.equals(expectedEnvironment) || !cell.equals(expectedCell) || !namespace.equals(expectedNamespace)
                || now == null || now.isBefore(observedAt) || !now.isBefore(expiresAt) || key == null || signature.isEmpty()
                || !Ed25519.verify(key, canonical(), Base64.getUrlDecoder().decode(signature)))
            throw ReleaseBundleProtocolError.DECISION_INVENTORY_INVALID.conflict();
    }

    /** 发布方记录该摘要用于比较同序号冲突；成员顺序和 JSON 排版不会改变它。 */
    public String contentDigest() { return digest(canonical()); }

    private byte[] canonical() {
        var fields = new HashMap<String, String>();
        fields.put("purpose", PURPOSE);
        fields.put("environment", environment); fields.put("cell", cell); fields.put("namespace", namespace);
        fields.put("sequence", Long.toString(sequence));
        fields.put("observedAt", observedAt.toString()); fields.put("expiresAt", expiresAt.toString());
        fields.put("signatureKeyId", signatureKeyId); fields.put("replicaCount", Integer.toString(replicas.size()));
        for (int i = 0; i < replicas.size(); i++) {
            Replica replica = replicas.get(i);
            String prefix = "replica." + i + ".";
            fields.put(prefix + "instanceId", replica.instanceId());
            fields.put(prefix + "imageDigest", replica.imageDigest());
            fields.put(prefix + "executionKeyId", replica.executionKeyId());
        }
        return CanonicalMapCodec.encode(fields);
    }

    private static String digest(byte[] content) { return "sha256:" + Digests.sha256Hex(content); }
    private static boolean name(String value, int limit) {
        return value != null && value.length() <= limit && value.matches("[A-Za-z0-9_.:-]+");
    }
    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException(ReleaseBundleProtocolError.DECISION_INVENTORY_INVALID.message());
    }
}
