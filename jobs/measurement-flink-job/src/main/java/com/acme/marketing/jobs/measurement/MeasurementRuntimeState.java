package com.acme.marketing.jobs.measurement;

import java.util.Map;

public record MeasurementRuntimeState(
        MeasurementProjectionState projection,
        Map<String, MeasurementFactMessage> pending,
        long expiresAtEpochMillis) {
    public MeasurementRuntimeState {
        pending = Map.copyOf(pending == null ? Map.of() : pending);
        if (projection == null && pending.isEmpty()) {
            throw new IllegalArgumentException("measurement runtime state cannot be empty");
        }
        if (expiresAtEpochMillis < 1) {
            throw new IllegalArgumentException("measurement runtime expiry is invalid");
        }
    }
}
