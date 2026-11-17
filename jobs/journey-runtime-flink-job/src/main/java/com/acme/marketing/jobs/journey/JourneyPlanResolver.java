package com.acme.marketing.jobs.journey;

import com.acme.marketing.contracts.release.JourneyBundleExecutionProbe;
import com.acme.marketing.contracts.release.ReleaseBundleProtocolError;

import com.acme.marketing.journey.JourneyPlan;
import java.io.Serializable;
import java.time.Instant;

@FunctionalInterface
interface JourneyPlanResolver extends Serializable {
    JourneyPlan resolve(String tenantId, JourneyJobInput.PlanReference reference, Instant now,
            boolean requireCurrentActivation);

    /** 未实现真实探测的解析器默认拒绝，不能用空实现宣称执行就绪。 */
    default void verifyExecutionProbe(JourneyBundleExecutionProbe probe,
            String jobId, int parallelism, String buildDigest, Instant now) {
        throw ReleaseBundleProtocolError.EXECUTION_PROBE_INVALID.conflict();
    }

    /** 旧解析器默认拒绝组合凭证，不能在未实现校验时静默降级到单清单路径。 */
    default JourneyPlan resolveBound(String tenantId, String enrollmentId, String subjectToken,
            JourneyJobInput.PlanReference reference,
            com.acme.marketing.contracts.release.ReleaseBundleParticipation participation,
            Instant now, boolean requireCurrentActivation) {
        if (participation != null) throw ReleaseBundleProtocolError.PARTICIPATION_INVALID.conflict();
        return resolve(tenantId, reference, now, requireCurrentActivation);
    }
}
