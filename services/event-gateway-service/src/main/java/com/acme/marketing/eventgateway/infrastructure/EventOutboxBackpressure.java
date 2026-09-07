package com.acme.marketing.eventgateway.infrastructure;

import com.acme.marketing.eventgateway.application.EventAdmissionControl;
import com.acme.marketing.eventgateway.application.EventOutboxDepthRepository;
import com.acme.marketing.eventgateway.application.EventOutboxRepository;
import com.acme.marketing.platform.error.DependencyUnavailableException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 以数据库中的未发布深度和最老事件年龄作为集群共享背压信号。
 * 结果短时缓存，避免在正常的高 QPS 接入路径上为每个事件执行 count 查询。
 */
@Component
public final class EventOutboxBackpressure implements EventAdmissionControl {
    private static final String ERROR_CODE = "EVENT_OUTBOX_BACKPRESSURE";

    private final EventOutboxRepository outbox;
    private final EventOutboxDepthRepository outboxDepth;
    private final Clock clock;
    private final long maxPending;
    private final long maxPendingPerTenant;
    private final Duration maxOldestAge;
    private final Duration refreshInterval;
    private final int maxCachedTenants;
    private final AtomicLong observedPending = new AtomicLong();
    private final AtomicLong observedOldestAgeSeconds = new AtomicLong();
    private final AtomicLong active = new AtomicLong();
    private final Counter rejected;
    private final ConcurrentHashMap<String, TenantSnapshot> tenantSnapshots = new ConcurrentHashMap<>();
    private volatile GlobalSnapshot globalSnapshot = GlobalSnapshot.expired();

    public EventOutboxBackpressure(EventOutboxRepository outbox, EventOutboxDepthRepository outboxDepth,
            Clock clock, MeterRegistry meters,
            @Value("${marketing.outbox.backpressure.max-pending:1000000}") long maxPending,
            @Value("${marketing.outbox.backpressure.max-pending-per-tenant:100000}") long maxPendingPerTenant,
            @Value("${marketing.outbox.backpressure.max-oldest-age-seconds:300}") long maxOldestAgeSeconds,
            @Value("${marketing.outbox.backpressure.refresh-ms:250}") long refreshMillis,
            @Value("${marketing.outbox.backpressure.max-cached-tenants:10000}") int maxCachedTenants) {
        if (maxPending < 1 || maxPendingPerTenant < 1 || maxOldestAgeSeconds < 1
                || refreshMillis < 0 || refreshMillis > 60_000 || maxCachedTenants < 1) {
            throw new IllegalArgumentException("event outbox backpressure policy is invalid");
        }
        this.outbox = outbox;
        this.outboxDepth = outboxDepth;
        this.clock = clock;
        this.maxPending = maxPending;
        this.maxPendingPerTenant = maxPendingPerTenant;
        this.maxOldestAge = Duration.ofSeconds(maxOldestAgeSeconds);
        this.refreshInterval = Duration.ofMillis(refreshMillis);
        this.maxCachedTenants = maxCachedTenants;
        this.rejected = Counter.builder("marketing.outbox.backpressure.rejected")
                .tag("outbox", "events").register(meters);
        Gauge.builder("marketing.outbox.pending", observedPending, AtomicLong::get)
                .tag("outbox", "events").register(meters);
        Gauge.builder("marketing.outbox.oldest.age", observedOldestAgeSeconds, AtomicLong::get)
                .baseUnit("seconds").tag("outbox", "events").register(meters);
        Gauge.builder("marketing.outbox.backpressure.active", active, AtomicLong::get)
                .tag("outbox", "events").register(meters);
    }

    /** 新事件落库前检查共享 backlog；已持久化事件的重复请求应在调用本方法前直接重放。 */
    @Override
    public void assertWritable(String tenantId) {
        Instant now = clock.instant();
        GlobalSnapshot global = global(now);
        TenantSnapshot tenant = tenant(tenantId, now);
        boolean overloaded = global.pending() >= maxPending
                || tenant.pending() >= maxPendingPerTenant
                || global.oldestCreatedAt() != null
                && Duration.between(global.oldestCreatedAt(), now).compareTo(maxOldestAge) >= 0;
        active.set(overloaded ? 1 : 0);
        if (overloaded) {
            rejected.increment();
            throw new DependencyUnavailableException(ERROR_CODE,
                    "event outbox backlog exceeded its bounded admission policy");
        }
    }

    /** Relay 完成状态回写后使下一次接入刷新视图，加快故障恢复后的重新放流。 */
    public void invalidate() {
        globalSnapshot = GlobalSnapshot.expired();
        tenantSnapshots.clear();
    }

    private GlobalSnapshot global(Instant now) {
        GlobalSnapshot current = globalSnapshot;
        if (current.validUntil().isAfter(now)) return current;
        synchronized (this) {
            current = globalSnapshot;
            if (current.validUntil().isAfter(now)) return current;
            Instant oldest = outbox.findOldestPendingCreatedAt();
            long pending = outboxDepth.globalPending();
            current = new GlobalSnapshot(pending, oldest, now.plus(refreshInterval));
            globalSnapshot = current;
            observedPending.set(pending);
            observedOldestAgeSeconds.set(oldest == null ? 0
                    : Math.max(0, Duration.between(oldest, now).toSeconds()));
            return current;
        }
    }

    private TenantSnapshot tenant(String tenantId, Instant now) {
        // Kafka 长故障期间租户集合可能持续增长；到达上限后仍执行精确查询，但不再扩大进程内缓存。
        if (tenantSnapshots.size() >= maxCachedTenants && !tenantSnapshots.containsKey(tenantId)) {
            return loadTenant(tenantId, now);
        }
        return tenantSnapshots.compute(tenantId, (ignored, current) -> {
            if (current != null && current.validUntil().isAfter(now)) return current;
            return loadTenant(tenantId, now);
        });
    }

    private TenantSnapshot loadTenant(String tenantId, Instant now) {
        return new TenantSnapshot(outboxDepth.tenantPending(tenantId), now.plus(refreshInterval));
    }

    private record GlobalSnapshot(long pending, Instant oldestCreatedAt, Instant validUntil) {
        private static GlobalSnapshot expired() {
            return new GlobalSnapshot(0, null, Instant.EPOCH);
        }
    }
    private record TenantSnapshot(long pending, Instant validUntil) { }
}
