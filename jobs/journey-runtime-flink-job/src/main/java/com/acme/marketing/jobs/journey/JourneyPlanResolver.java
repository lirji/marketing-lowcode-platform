package com.acme.marketing.jobs.journey;

import com.acme.marketing.journey.JourneyPlan;
import java.io.Serializable;
import java.time.Instant;

@FunctionalInterface
interface JourneyPlanResolver extends Serializable {
    JourneyPlan resolve(String tenantId, JourneyJobInput.PlanReference reference, Instant now,
            boolean requireCurrentActivation);

    /** 旧解析器默认拒绝组合凭证，不能在未实现校验时静默降级到单清单路径。 */
    default JourneyPlan resolveBound(String tenantId, String enrollmentId, String subjectToken,
            JourneyJobInput.PlanReference reference,
            com.acme.marketing.contracts.release.ReleaseBundleParticipation participation,
            Instant now, boolean requireCurrentActivation) {
        if (participation != null) throw com.acme.marketing.contracts.release.ReleaseBundleProtocolError.PARTICIPATION_INVALID.conflict();
        return resolve(tenantId, reference, now, requireCurrentActivation);
    }
}
