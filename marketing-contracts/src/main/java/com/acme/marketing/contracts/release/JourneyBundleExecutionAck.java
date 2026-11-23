package com.acme.marketing.contracts.release;

import java.time.Instant;

/** 单个真实Flink子任务的执行能力证据；一份回执不能代表整个作业或组合已应用。 */
public record JourneyBundleExecutionAck(JourneyBundleExecutionProbe probe, String executionProtocol,
        String jobId, int parallelism, int subtaskIndex, int attemptNumber, String buildDigest,
        Instant acknowledgedAt, String signatureKeyId, String signature) {
    public static final String EXECUTION_PROTOCOL = "marketing.activity-bundle-execution/v1";
    /** v2 表示作业在读取旧激活前检查组合管理标志，历史 v1 回执仍可读取。 */
    public static final String BOUND_START_PROTOCOL = "marketing.activity-bundle-execution/v2";
    public JourneyBundleExecutionAck {
        if (probe == null || probe.signature().isEmpty() || !(EXECUTION_PROTOCOL.equals(executionProtocol) || BOUND_START_PROTOCOL.equals(executionProtocol))
                || !JourneyBundleExecutionProbe.jobId(jobId) || parallelism < 1 || parallelism > JourneyBundleExecutionProbe.MAX_PARALLELISM
                || subtaskIndex < 0 || subtaskIndex >= parallelism || attemptNumber < 0 || !JourneyBundleExecutionProbe.buildDigest(buildDigest)
                || acknowledgedAt == null || acknowledgedAt.isBefore(probe.issuedAt()) || !acknowledgedAt.isBefore(probe.expiresAt())
                || !JourneyBundleExecutionProbe.keyId(signatureKeyId)) throw invalid();
        signature = signature == null ? "" : signature;
        if (!signature.isEmpty() && !JourneyBundleExecutionProbe.signed(signature)) throw invalid();
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException(ReleaseBundleProtocolError.EXECUTION_ACK_INVALID.message()); }
}
