package com.acme.marketing.journey.event;

import com.acme.marketing.journey.EnrollmentSnapshot;

/** Durable materialization contract emitted by the journey runtime after every accepted signal or timer. */
public record JourneyStateChangedEvent(
        String eventType,
        EnrollmentSnapshot snapshot,
        String sourceSignalId,
        boolean duplicate,
        long projectedAtEpochMillis,
        long expiresAtEpochMillis) {
    public JourneyStateChangedEvent {
        if (!"JOURNEY_STATE_CHANGED".equals(eventType) || snapshot == null
                || sourceSignalId == null || sourceSignalId.isBlank()
                || projectedAtEpochMillis < 1 || expiresAtEpochMillis <= projectedAtEpochMillis) {
            throw new IllegalArgumentException("journey state event is invalid");
        }
    }
}
