package com.acme.marketing.contracts.release;

import com.acme.marketing.platform.error.ConflictException;
import com.acme.marketing.platform.identity.TenantId;
import java.security.PublicKey;
import java.time.Instant;
import java.util.Map;

/** 把探针和子任务回执绑定到可信部署登记；不把输入自报身份当预期值。 */
public final class PinnedJourneyBundleExecutionVerifier {
    private static final String JOURNEY_RUNTIME = "journey";
    private static final long MAX_CLOCK_SKEW_SECONDS = 60;
    private final Map<String, PublicKey> controlKeys;
    private final Map<String, PublicKey> executionKeys;
    private final PinnedReleaseBundleActivationVerifier activations;
    /** 两类钥匙明确分离，缺少任一信任时相应验证默认拒绝。 */
    public PinnedJourneyBundleExecutionVerifier(Map<String, PublicKey> controlKeys, Map<String, PublicKey> executionKeys) {
        this.controlKeys = Map.copyOf(controlKeys); this.executionKeys = Map.copyOf(executionKeys);
        this.activations = new PinnedReleaseBundleActivationVerifier(controlKeys);
    }
    /** 返回精确源成员供实际运行端继续核对本地安装字节，绝不切换运行指针。 */
    public ReleaseManifest requireProbe(ExpectedDeployment expected, JourneyBundleExecutionProbe probe,
            ReleaseBundleActivationDirective source, Instant now) {
        if (expected == null || probe == null || source == null || now == null
                || !JourneyBundleExecutionProbeSigner.verify(controlKeys.get(probe.signatureKeyId()), probe)
                || !expected.tenantId().equals(probe.tenantId()) || !expected.campaignId().equals(probe.campaignId())
                || !expected.environment().equals(probe.environment()) || !expected.cell().equals(probe.cell())
                || !expected.namespace().equals(probe.namespace()) || !expected.jobId().equals(probe.targetJobId())
                || expected.parallelism() != probe.targetParallelism() || !expected.participationKeyId().equals(probe.participationKeyId())
                || now.isBefore(probe.issuedAt()) || !now.isBefore(probe.expiresAt())
                || probe.issuedAt().isBefore(source.authorizedAt()) || probe.expiresAt().isAfter(source.bundle().expiresAt())
                || !probe.tenantId().equals(source.bundle().tenantId()) || !probe.campaignId().equals(source.bundle().campaignId())
                || !probe.environment().equals(source.bundle().environment()) || !probe.cell().equals(source.bundle().cell())
                || probe.activationSequence() != source.activationSequence() || !probe.activationSignature().equals(source.signature())) throw probeInvalid();
        try { activations.requireValid(expected.tenantId().value(), source, now); }
        catch (ConflictException untrustedSource) { throw probeInvalid(); }
        return source.bundle().members().stream().filter(member -> JOURNEY_RUNTIME.equals(member.runtime())
                && expected.namespace().equals(member.namespace()) && probe.memberManifestId().equals(member.manifestId()))
                .findFirst().orElseThrow(PinnedJourneyBundleExecutionVerifier::probeInvalid);
    }
    /** 校验单份执行证据，调用方仍必须收齐全部子任务、检查当前部署及持久应用门禁。 */
    public void requireAck(ExpectedDeployment expected, JourneyBundleExecutionProbe probe,
            ReleaseBundleActivationDirective source, JourneyBundleExecutionAck ack, Instant now) {
        requireProbe(expected, probe, source, now);
        if (ack == null || !probe.equals(ack.probe()) || !expected.jobId().equals(ack.jobId())
                || expected.parallelism() != ack.parallelism() || !expected.buildDigest().equals(ack.buildDigest())
                || ack.acknowledgedAt().isAfter(now.plusSeconds(MAX_CLOCK_SKEW_SECONDS))
                || !JourneyBundleExecutionAckSigner.verify(executionKeys.get(ack.signatureKeyId()), ack))
            throw ReleaseBundleProtocolError.EXECUTION_ACK_INVALID.conflict();
    }
    private static ConflictException probeInvalid() { return ReleaseBundleProtocolError.EXECUTION_PROBE_INVALID.conflict(); }

    /** 来源为可信本地运行身份或部署登记；禁止直接拷贝待验ACK字段构造。 */
    public record ExpectedDeployment(TenantId tenantId, String campaignId, String environment, String cell, String namespace,
            String jobId, int parallelism, String buildDigest, String participationKeyId) {
        public ExpectedDeployment {
            if (tenantId == null || campaignId == null || environment == null || cell == null || namespace == null
                    || !JourneyBundleExecutionProbe.jobId(jobId) || parallelism < 1 || parallelism > JourneyBundleExecutionProbe.MAX_PARALLELISM
                    || !JourneyBundleExecutionProbe.buildDigest(buildDigest) || !JourneyBundleExecutionProbe.keyId(participationKeyId))
                throw new IllegalArgumentException(ReleaseBundleProtocolError.EXECUTION_PROBE_INVALID.message());
        }
    }
}
