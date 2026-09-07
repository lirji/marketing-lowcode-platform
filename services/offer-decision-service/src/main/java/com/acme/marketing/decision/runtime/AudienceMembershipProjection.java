package com.acme.marketing.decision.runtime;

import com.acme.marketing.decision.runtime.AudienceMembershipStore.StoredMembership;
import com.acme.marketing.platform.crypto.Digests;
import jakarta.annotation.PostConstruct;
import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.scheduling.annotation.Scheduled;

public final class AudienceMembershipProjection {
    private final ConcurrentHashMap<Key, Membership> memberships = new ConcurrentHashMap<>();
    private final AudienceMembershipStore store;
    private final Clock clock;
    private volatile Instant lastReload = Instant.EPOCH;

    public AudienceMembershipProjection() {
        this.store = null;
        this.clock = Clock.systemUTC();
    }

    public AudienceMembershipProjection(AudienceMembershipStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    public void update(String tenantId, String snapshotId, String subjectToken, boolean member,
            long version, Instant expiresAt) {
        if (version < 1 || expiresAt == null) throw new IllegalArgumentException("membership update is invalid");
        if (store != null) {
            String hash = Digests.sha256Hex(subjectToken);
            store.saveIfNewer(tenantId, snapshotId, hash, member, version, expiresAt, clock.instant());
        }
        Key key = new Key(tenantId, snapshotId, Digests.sha256Hex(subjectToken));
        memberships.compute(key, (ignored, current) -> current == null || version > current.version()
                ? new Membership(member, version, expiresAt) : current);
    }

    public boolean isMember(String tenantId, String snapshotId, String subjectToken, Instant now) {
        Membership membership = memberships.get(new Key(tenantId, snapshotId, Digests.sha256Hex(subjectToken)));
        return membership != null && membership.member() && membership.expiresAt().isAfter(now);
    }

    @PostConstruct
    @Scheduled(fixedDelayString = "${marketing.audience-projection.reconcile-interval-ms:1000}")
    public void reload() {
        if (store == null) return;
        Instant floor = lastReload;
        var changed = store.findChangedSince(floor);
        Instant newest = floor;
        for (StoredMembership row : changed) {
            memberships.compute(new Key(row.tenantId(), row.audienceId(), row.subjectHash()),
                    (ignored, current) -> current == null || row.version() > current.version()
                            ? new Membership(row.member(), row.version(), row.expiresAt()) : current);
            if (row.updatedAt().isAfter(newest)) newest = row.updatedAt();
        }
        lastReload = newest;
        Instant now = clock.instant();
        memberships.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(now));
    }

    private record Key(String tenantId, String snapshotId, String subjectHash) { }
    private record Membership(boolean member, long version, Instant expiresAt) { }
}
