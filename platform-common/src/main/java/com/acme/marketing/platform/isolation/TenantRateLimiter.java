package com.acme.marketing.platform.isolation;

import com.acme.marketing.platform.error.DomainException;
import com.acme.marketing.platform.identity.TenantId;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

public final class TenantRateLimiter {
    private final Clock clock;
    private final int limitPerSecond;
    private final ConcurrentHashMap<TenantId, Window> windows = new ConcurrentHashMap<>();

    public TenantRateLimiter(Clock clock, int limitPerSecond) {
        this.clock = Objects.requireNonNull(clock, "clock");
        if (limitPerSecond < 1) throw new IllegalArgumentException("rate limit must be positive");
        this.limitPerSecond = limitPerSecond;
    }

    public void acquire(TenantId tenantId) {
        long second = clock.instant().getEpochSecond();
        Window updated = windows.compute(tenantId, (ignored, current) -> {
            if (current == null || current.epochSecond() != second) return new Window(second, 1);
            return new Window(second, current.count() + 1);
        });
        if (updated.count() > limitPerSecond) {
            throw new DomainException("TENANT_RATE_LIMITED", "tenant request rate exceeded", true);
        }
    }

    private record Window(long epochSecond, int count) { }
}
