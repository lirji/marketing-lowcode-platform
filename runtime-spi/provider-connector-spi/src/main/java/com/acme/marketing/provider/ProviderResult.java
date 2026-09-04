package com.acme.marketing.provider;

import java.time.Instant;
import java.util.Map;

public record ProviderResult(
        Status status,
        String providerRequestId,
        String code,
        int attempts,
        Instant completedAt,
        Map<String, String> metadata) {
    public ProviderResult {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public enum Status {
        ACCEPTED,
        RETRY_EXHAUSTED,
        PERMANENT_FAILURE,
        RATE_LIMITED,
        CIRCUIT_OPEN
    }
}
