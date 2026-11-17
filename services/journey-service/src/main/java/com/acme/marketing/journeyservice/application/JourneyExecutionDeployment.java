package com.acme.marketing.journeyservice.application;

import com.acme.marketing.contracts.release.PinnedJourneyBundleExecutionVerifier;
import com.acme.marketing.contracts.release.ReleaseBundleProtocolError;
import com.acme.marketing.platform.crypto.CanonicalMapCodec;
import com.acme.marketing.platform.crypto.Digests;
import com.acme.marketing.platform.identity.TenantId;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 可信目标只来自受控部署配置，不能用探针或ACK自报信息建立预期。 */
@Component
public final class JourneyExecutionDeployment {
    private final String environment;
    private final String cell;
    private final String namespace;
    private final String jobId;
    private final int parallelism;
    private final String buildDigest;
    private final String participationKeyId;
    /** 默认全空关闭；部分目标配置在服务启动时拒绝。 */
    public JourneyExecutionDeployment(@Value("${marketing.runtime.environment:local}") String environment,
            @Value("${marketing.runtime.cell:cell-a}") String cell, @Value("${marketing.runtime.namespace:main}") String namespace,
            @Value("${marketing.journey.execution-target.job-id:}") String jobId,
            @Value("${marketing.journey.execution-target.parallelism:0}") int parallelism,
            @Value("${marketing.journey.execution-target.build-digest:}") String buildDigest,
            @Value("${marketing.journey.execution-target.participation-key-id:}") String participationKeyId) {
        this.environment = environment; this.cell = cell; this.namespace = namespace; this.jobId = jobId;
        this.parallelism = parallelism; this.buildDigest = buildDigest; this.participationKeyId = participationKeyId;
        if (jobId.isEmpty() && parallelism == 0 && buildDigest.isEmpty() && participationKeyId.isEmpty()) return;
        try { expected(new TenantId("configuration-check"), "configuration-check"); }
        catch (IllegalArgumentException | com.acme.marketing.platform.error.ConflictException invalid) { throw new IllegalStateException(ReleaseBundleProtocolError.EXECUTION_DEPLOYMENT_UNAVAILABLE.message()); }
    }
    /** 未启用时拒绝登记/收集，不能把Web健康或development摘要当实际目标。 */
    public PinnedJourneyBundleExecutionVerifier.ExpectedDeployment expected(TenantId tenant, String campaign) {
        if (jobId.isEmpty()) throw ReleaseBundleProtocolError.EXECUTION_DEPLOYMENT_UNAVAILABLE.conflict();
        return new PinnedJourneyBundleExecutionVerifier.ExpectedDeployment(tenant, campaign, environment, cell, namespace,
                jobId, parallelism, buildDigest, participationKeyId);
    }
    /** 作用域取服务器位置与可信租户，不允许请求选择其他namespace。 */
    public JourneyExecutionProbeRepository.Key key(String tenant, String campaign, String probeId) {
        if (campaign == null || !campaign.matches("[A-Za-z0-9_.:@-]{1,64}") || probeId == null || !probeId.matches("[A-Za-z0-9_.:@-]{1,64}"))
            throw ReleaseBundleProtocolError.EXECUTION_REQUEST_INVALID.invalid();
        return new JourneyExecutionProbeRepository.Key(tenant, environment, cell, namespace, campaign, probeId);
    }
    /** 后台领取限定本站位置；租户来自已授权的持久任务。 */
    public JourneyProbeDeliveryRepository.Slot slot() { return new JourneyProbeDeliveryRepository.Slot(environment, cell, namespace); }
    /** 绑定登记时的完整目标，配置变化必须重新签挑战，不能沿用旧READY。 */
    public String fingerprint() {
        return Digests.sha256Hex(CanonicalMapCodec.encode(Map.of("purpose", "marketing.journey-execution-target/v1",
                "environment", environment, "cell", cell, "namespace", namespace, "jobId", jobId,
                "parallelism", Integer.toString(parallelism), "buildDigest", buildDigest, "participationKeyId", participationKeyId)));
    }
}
