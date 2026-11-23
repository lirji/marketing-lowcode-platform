package com.acme.marketing.jobs.journey;

import com.acme.marketing.contracts.release.JourneyBundleExecutionAck;
import com.acme.marketing.contracts.release.JourneyBundleExecutionAckSigner;
import com.acme.marketing.contracts.release.JourneyBundleExecutionProbe;
import com.acme.marketing.contracts.release.ReleaseBundleProtocolError;
import com.acme.marketing.jobs.support.JobEnvironment;
import com.acme.marketing.platform.crypto.Ed25519KeyPairCodec;
import java.io.Serializable;
import java.security.PrivateKey;
import java.time.Instant;
import org.apache.flink.api.common.functions.RuntimeContext;

/** 执行签名只存在于真实处理函数，Web安装路径没有此能力。 */
final class JourneyExecutionProbeHandler implements Serializable {
    private static final long serialVersionUID = 1L;
    private final String keyId;
    private final String privateKey;
    private final String publicKey;
    private final String buildDigest;
    private transient PrivateKey signer;
    private transient String jobId;
    private transient int parallelism;
    private transient int subtask;
    private transient int attempt;

    /** 仅主入口从真实JAR计算摘要；全部配置为空时保持旧作业可运行。 */
    static JourneyExecutionProbeHandler configured() {
        String id = JobEnvironment.value("JOURNEY_EXECUTION_ACK_KEY_ID", "");
        String privateValue = JobEnvironment.value("JOURNEY_EXECUTION_ACK_PRIVATE_KEY_BASE64", "");
        String publicValue = JobEnvironment.value("JOURNEY_EXECUTION_ACK_PUBLIC_KEY_BASE64", "");
        if (id.isBlank() && privateValue.isBlank() && publicValue.isBlank()) return disabled();
        validateKeys(id, privateValue, publicValue);
        return new JourneyExecutionProbeHandler(id, privateValue, publicValue, JourneySubmittedArtifact.currentDigest());
    }
    static JourneyExecutionProbeHandler disabled() { return new JourneyExecutionProbeHandler("", "", "", ""); }
    /** 包内构造用于图组装与隔离测试，线上不提供摘要覆盖配置。 */
    JourneyExecutionProbeHandler(String keyId, String privateKey, String publicKey, String buildDigest) {
        this.keyId = keyId; this.privateKey = privateKey; this.publicKey = publicKey; this.buildDigest = buildDigest;
        if (!keyId.isEmpty() || !privateKey.isEmpty() || !publicKey.isEmpty() || !buildDigest.isEmpty()) {
            validateKeys(keyId, privateKey, publicKey);
            if (!buildDigest.matches("sha256:[0-9a-f]{64}")) throw unavailable();
        }
    }
    /** 运行身份不从探针或提交参数拷贝，重启/重新缩放后由实际Context重新读取。 */
    void open(RuntimeContext context) {
        if (keyId.isEmpty()) return;
        signer = Ed25519KeyPairCodec.decode(privateKey, publicKey).getPrivate();
        jobId = context.getJobInfo().getJobId().toHexString();
        parallelism = context.getTaskInfo().getNumberOfParallelSubtasks();
        subtask = context.getTaskInfo().getIndexOfThisSubtask();
        attempt = context.getTaskInfo().getAttemptNumber();
    }
    /** 每次重复探针仍输出回执；不以内存去重丢弃尚未checkpoint提交的ACK。 */
    JourneyBundleExecutionAck acknowledge(JourneyPlanResolver plans, JourneyBundleExecutionProbe probe, Instant now) {
        if (signer == null) throw unavailable();
        plans.verifyExecutionProbe(probe, jobId, parallelism, buildDigest, now);
        return JourneyBundleExecutionAckSigner.sign(signer, new JourneyBundleExecutionAck(probe,
                JourneyBundleExecutionAck.BOUND_START_PROTOCOL, jobId, parallelism, subtask, attempt, buildDigest, now, keyId, ""));
    }
    private static void validateKeys(String id, String privateValue, String publicValue) {
        if (!id.matches("[A-Za-z0-9_.:@-]{1,64}") || privateValue.isBlank() || publicValue.isBlank()) throw unavailable();
        try { Ed25519KeyPairCodec.decode(privateValue, publicValue); }
        catch (IllegalArgumentException invalid) { throw unavailable(); }
    }
    private static IllegalStateException unavailable() { return new IllegalStateException(ReleaseBundleProtocolError.EXECUTION_SIGNING_UNAVAILABLE.message()); }
}
