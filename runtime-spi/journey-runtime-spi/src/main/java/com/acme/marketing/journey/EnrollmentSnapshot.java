package com.acme.marketing.journey;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

public record EnrollmentSnapshot(
        String tenantId,
        String enrollmentId,
        String subjectToken,
        String journeyId,
        long journeyVersion,
        String currentNodeId,
        Status status,
        Map<String, String> variables,
        Set<String> processedSignalIds,
        Map<String, Integer> iterations,
        Map<String, Integer> nodeExecutions,
        Instant updatedAt) {
    public EnrollmentSnapshot {
        variables = Map.copyOf(variables == null ? Map.of() : variables);
        processedSignalIds = Set.copyOf(processedSignalIds == null ? Set.of() : processedSignalIds);
        iterations = Map.copyOf(iterations == null ? Map.of() : iterations);
        nodeExecutions = Map.copyOf(nodeExecutions == null ? Map.of() : nodeExecutions);
    }

    public static EnrollmentSnapshot start(
            String tenantId, String enrollmentId, String subjectToken, JourneyPlan plan, Instant now) {
        return new EnrollmentSnapshot(tenantId, enrollmentId, subjectToken, plan.journeyId(), plan.version(),
                plan.startNodeId(), Status.RUNNING, Map.of(), Set.of(), Map.of(), Map.of(), now);
    }

    public enum Status {
        RUNNING, WAITING, COMPLETED, FAILED
    }
}
