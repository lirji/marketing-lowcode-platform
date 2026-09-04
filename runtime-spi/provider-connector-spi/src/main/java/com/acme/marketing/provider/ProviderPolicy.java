package com.acme.marketing.provider;

import java.time.Duration;
import java.util.Set;

public record ProviderPolicy(
        Duration timeout,
        int maxAttempts,
        Duration initialBackoff,
        Set<Integer> retryableStatusCodes,
        int requestsPerSecond,
        int circuitFailureThreshold) {
    public ProviderPolicy {
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        if (maxAttempts < 1 || initialBackoff == null || initialBackoff.isNegative()) {
            throw new IllegalArgumentException("invalid retry policy");
        }
        retryableStatusCodes = Set.copyOf(retryableStatusCodes);
        if (requestsPerSecond < 1 || circuitFailureThreshold < 1) {
            throw new IllegalArgumentException("rate and circuit threshold must be positive");
        }
    }

    public static ProviderPolicy defaults() {
        return new ProviderPolicy(Duration.ofSeconds(2), 3, Duration.ofMillis(100),
                Set.of(408, 425, 429, 500, 502, 503, 504), 100, 5);
    }
}
