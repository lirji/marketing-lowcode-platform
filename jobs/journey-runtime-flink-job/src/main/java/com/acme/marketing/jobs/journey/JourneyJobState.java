package com.acme.marketing.jobs.journey;

import com.acme.marketing.journey.EnrollmentSnapshot;
public record JourneyJobState(
        JourneyJobInput.PlanReference planReference,
        EnrollmentSnapshot snapshot,
        String activeTimerKey,
        long activeTimerAtEpochMillis,
        int timerRetryCount,
        long expiresAtEpochMillis) {
    public JourneyJobState {
        if (planReference == null || snapshot == null || timerRetryCount < 0 || expiresAtEpochMillis < 1) {
            throw new IllegalArgumentException("journey job state is invalid");
        }
        activeTimerKey = activeTimerKey == null ? "" : activeTimerKey;
        if (activeTimerKey.isBlank() != (activeTimerAtEpochMillis == 0)) {
            throw new IllegalArgumentException("journey timer key and due time must be consistent");
        }
    }
}
