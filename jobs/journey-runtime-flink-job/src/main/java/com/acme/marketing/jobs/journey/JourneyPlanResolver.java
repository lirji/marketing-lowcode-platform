package com.acme.marketing.jobs.journey;

import com.acme.marketing.journey.JourneyPlan;
import java.io.Serializable;
import java.time.Instant;

@FunctionalInterface
interface JourneyPlanResolver extends Serializable {
    JourneyPlan resolve(String tenantId, JourneyJobInput.PlanReference reference, Instant now,
            boolean requireCurrentActivation);
}
