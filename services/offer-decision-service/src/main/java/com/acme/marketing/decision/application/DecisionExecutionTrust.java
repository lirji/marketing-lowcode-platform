package com.acme.marketing.decision.application;

import com.acme.marketing.contracts.release.*;
import com.acme.marketing.platform.crypto.Ed25519KeyPairCodec;
import com.acme.marketing.platform.crypto.TrustedPublicKeys;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** 配置的执行身份、流水线身份和 Journey 作业目标分别校验，探针输入不能充当信任根。 */
@Component
public class DecisionExecutionTrust {
    private final Map<String, PublicKey> replicaKeys;
    private final Map<String, PublicKey> pipelineKeys;
    private final Map<String, PublicKey> participationKeys;
    private final PinnedJourneyBundleExecutionVerifier journeyVerifier;
    private final Map<String, Target> targets;
    private final DecisionDeploymentInventory.Replica local;
    private final PrivateKey signingKey;
    record Target(String jobId, int parallelism, String buildDigest) { }

    public DecisionExecutionTrust(ObjectMapper mapper,
            @Value("${marketing.execution.instance-id:}") String instance,
            @Value("${marketing.execution.image-digest:}") String image,
            @Value("${marketing.execution.key-id:}") String keyId,
            @Value("${marketing.execution.private-key:}") String secret,
            @Value("${marketing.execution.trusted-public-keys:}") String replicas,
            @Value("${marketing.deployment-inventory.trusted-public-keys:}") String pipelines,
            @Value("${marketing.release.trusted-key-id:}") String releaseId,
            @Value("${marketing.release.public-key-base64:}") String release,
            @Value("${marketing.release.trusted-public-keys:}") String releases,
            @Value("${marketing.participation.trusted-key-id:}") String participationId,
            @Value("${marketing.participation.public-key-base64:}") String participation,
            @Value("${marketing.participation.trusted-public-keys:}") String participants,
            @Value("${marketing.execution.journey-public-keys:}") String journeyKeys,
            @Value("${marketing.execution.journey-targets:}") String deploymentTargets) {
        replicaKeys = replicaTrust(replicas);
        pipelineKeys = TrustedPublicKeys.parse("", "", pipelines);
        participationKeys = TrustedPublicKeys.parse(participationId, participation, participants);
        var control = TrustedPublicKeys.parse(releaseId, release, releases);
        var journey = TrustedPublicKeys.parse("", "", journeyKeys);
        var occupied = new HashSet<PublicKey>(); occupied.addAll(pipelineKeys.values()); occupied.addAll(participationKeys.values());
        occupied.addAll(control.values()); occupied.addAll(journey.values());
        if (!replicaKeys.values().stream().allMatch(occupied::add))
            throw new IllegalArgumentException(ReleaseBundleProtocolError.EXECUTION_ACK_INVALID.message());
        journeyVerifier = new PinnedJourneyBundleExecutionVerifier(control, journey);
        targets = deploymentTargets.isBlank() ? Map.of() : Map.copyOf(mapper.readValue(deploymentTargets, new TypeReference<Map<String, Target>>() {}));
        if (instance.isBlank() && image.isBlank() && keyId.isBlank() && secret.isBlank()) { local = null; signingKey = null; return; }
        local = new DecisionDeploymentInventory.Replica(instance, image, keyId);
        var publicKey = replicaKeys.get(keyId);
        if (publicKey == null) throw ReleaseBundleProtocolError.EXECUTION_ACK_INVALID.invalid();
        signingKey = Ed25519KeyPairCodec.decode(secret, Base64.getEncoder().encodeToString(publicKey.getEncoded())).getPrivate();
    }

    /** 每个接流量副本而非每个服务各持一把钥匙，单个活动最多覆盖八份 128 副本清单。 */
    static Map<String, PublicKey> replicaTrust(String configuration) {
        if (configuration.isBlank()) return Map.of();
        int maximum = DecisionDeploymentInventory.MAX_REPLICAS * ReleaseBundleManifest.MAX_MEMBERS;
        // 每项最多 64 字符标识及 Ed25519 公钥编码，留有分隔符与空白预算。
        if (configuration.length() > maximum * 160) throw ReleaseBundleProtocolError.EXECUTION_ACK_INVALID.invalid();
        String[] entries = configuration.split("[,;\n]", -1);
        if (entries.length > maximum) throw ReleaseBundleProtocolError.EXECUTION_ACK_INVALID.invalid();
        var result = new HashMap<String, PublicKey>();
        for (String entry : entries) {
            if (entry.isBlank()) throw ReleaseBundleProtocolError.EXECUTION_ACK_INVALID.invalid();
            // 沿用平台对 key-id 格式及公钥编码的严格解析；独立检验整个副本集合的唯一性。
            TrustedPublicKeys.parse("", "", entry).forEach((id, key) -> {
                if (result.putIfAbsent(id, key) != null) throw ReleaseBundleProtocolError.EXECUTION_ACK_INVALID.invalid();
            });
        }
        return Map.copyOf(result);
    }

    /** 每次签发和应用均重验权威部署记录，包括已排空的位置。 */
    void requireInventory(DecisionInventoryRepository.Slot slot, BundleApplicationRepository.Inventory row,
            DecisionDeploymentInventory inventory, Instant now) {
        inventory.requireCurrent(pipelineKeys, slot.environment(), slot.cell(), slot.namespace(), row.sequence(), row.digest(), now);
        if (inventory.replicas().isEmpty()) throw ReleaseBundleProtocolError.EXECUTION_COVERAGE_INCOMPLETE.conflict();
    }

    /** 确认真实参与签发与消费信任同意同一份事实，探测结果不能作为业务参与对外返回。 */
    void requireAdmission(ReleaseBundleParticipation proof) {
        if (!ReleaseBundleParticipationSigner.verify(participationKeys.get(proof.signatureKeyId()), proof))
            throw ReleaseBundleProtocolError.PARTICIPATION_SIGNING_UNAVAILABLE.conflict();
    }

    DecisionExecutionReceipt sign(DecisionDeploymentInventory inventory, ReleaseBundleActivationDirective source,
            String admissionKey, Instant now) {
        if (local == null || !inventory.replicas().contains(local)) throw ReleaseBundleProtocolError.EXECUTION_COVERAGE_INCOMPLETE.conflict();
        var receipt = new DecisionExecutionReceipt(source.signature(), inventory.sequence(), inventory.contentDigest(), local,
                admissionKey, now, "").sign(signingKey);
        receipt.requireValid(inventory, local, source, admissionKey, replicaKeys.get(local.executionKeyId()), now);
        return receipt;
    }

    /** 重试从不可变事实恢复，不能用新 ACK 掩盖已损坏的原回执。 */
    void requireLocal(DecisionDeploymentInventory inventory, DecisionExecutionReceipt receipt,
            ReleaseBundleActivationDirective source, String admissionKey, Instant now) {
        if (local == null || !inventory.replicas().contains(local)) throw ReleaseBundleProtocolError.EXECUTION_COVERAGE_INCOMPLETE.conflict();
        receipt.requireValid(inventory, local, source, admissionKey, replicaKeys.get(local.executionKeyId()), now);
    }

    void requireDecision(DecisionDeploymentInventory inventory, List<DecisionExecutionReceipt> receipts,
            ReleaseBundleActivationDirective source, String admissionKey, Instant now) {
        var missing = inventory.replicas().stream().collect(Collectors.toMap(DecisionDeploymentInventory.Replica::instanceId, Function.identity()));
        for (var ack : receipts) {
            var expected = missing.remove(ack.replica().instanceId());
            ack.requireValid(inventory, expected, source, admissionKey, replicaKeys.get(ack.replica().executionKeyId()), now);
        }
        if (!missing.isEmpty()) throw ReleaseBundleProtocolError.EXECUTION_COVERAGE_INCOMPLETE.conflict();
    }

    /** 探针来源和签名按每个目标独立复核，拒绝不同挑战拼凑、重复下标和未知成员。 */
    void requireJourney(ReleaseBundleActivationDirective source, List<JourneyBundleExecutionAck> receipts, String admissionKey, Instant now) {
        if (receipts.stream().anyMatch(ack -> !JourneyBundleExecutionAck.BOUND_START_PROTOCOL.equals(ack.executionProtocol())))
            throw ReleaseBundleProtocolError.EXECUTION_ACK_INVALID.conflict();
        var groups = receipts.stream().collect(Collectors.groupingBy(ack -> ack.probe().memberManifestId()));
        for (var member : source.bundle().members()) {
            if ("decision".equals(member.runtime())) continue;
            if (!"journey".equals(member.runtime())) throw ReleaseBundleProtocolError.MEMBER_MISMATCH.conflict();
            var target = targets.get(member.namespace());
            if (target == null) throw ReleaseBundleProtocolError.EXECUTION_DEPLOYMENT_UNAVAILABLE.conflict();
            var expected = new PinnedJourneyBundleExecutionVerifier.ExpectedDeployment(source.bundle().tenantId(), source.bundle().campaignId(),
                    member.environment(), member.cell(), member.namespace(), target.jobId(), target.parallelism(), target.buildDigest(), admissionKey);
            journeyVerifier.requireCoverage(expected, source, groups.remove(member.manifestId()), now);
        }
        if (!groups.isEmpty()) throw ReleaseBundleProtocolError.EXECUTION_ACK_INVALID.conflict();
    }
}
