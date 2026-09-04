package com.acme.marketing.jobs.measurement;

import java.util.Set;

public record MeasurementProjectionState(
        String tenantId,
        String rootEventId,
        String activeEventId,
        long revision,
        FactContribution active,
        Set<String> processedEventIds,
        long expiresAtEpochMillis) {
    public MeasurementProjectionState {
        if (tenantId == null || tenantId.isBlank() || rootEventId == null || rootEventId.isBlank()
                || activeEventId == null || activeEventId.isBlank() || revision < 1 || active == null
                || expiresAtEpochMillis < 1) {
            throw new IllegalArgumentException("measurement projection state is invalid");
        }
        processedEventIds = Set.copyOf(processedEventIds);
    }

    public MeasurementProjectionState expiringAt(long timestamp) {
        return new MeasurementProjectionState(tenantId, rootEventId, activeEventId, revision,
                active, processedEventIds, timestamp);
    }
}
