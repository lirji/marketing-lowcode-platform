package com.acme.marketing.platform.isolation;

import com.acme.marketing.platform.error.DomainException;
import com.acme.marketing.platform.identity.TenantId;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

public final class TenantBulkhead {
    private final int permitsPerTenant;
    private final Duration acquisitionTimeout;
    private final ConcurrentHashMap<TenantId, Semaphore> semaphores = new ConcurrentHashMap<>();

    public TenantBulkhead(int permitsPerTenant, Duration acquisitionTimeout) {
        if (permitsPerTenant < 1 || acquisitionTimeout == null || acquisitionTimeout.isNegative()) {
            throw new IllegalArgumentException("invalid bulkhead configuration");
        }
        this.permitsPerTenant = permitsPerTenant;
        this.acquisitionTimeout = acquisitionTimeout;
    }

    public <T> T execute(TenantId tenantId, Supplier<T> action) {
        Objects.requireNonNull(tenantId, "tenantId");
        Semaphore semaphore = semaphores.computeIfAbsent(tenantId, ignored -> new Semaphore(permitsPerTenant));
        boolean acquired;
        try {
            acquired = semaphore.tryAcquire(acquisitionTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new DomainException("BULKHEAD_INTERRUPTED", "request interrupted while waiting for capacity", true);
        }
        if (!acquired) {
            throw new DomainException("TENANT_CAPACITY_EXCEEDED", "tenant concurrency quota exceeded", true);
        }
        try {
            return action.get();
        } finally {
            semaphore.release();
        }
    }
}
