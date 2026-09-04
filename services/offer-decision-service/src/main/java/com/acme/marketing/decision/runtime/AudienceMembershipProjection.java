package com.acme.marketing.decision.runtime;

import static com.acme.marketing.platform.time.SqlTime.format;

import com.acme.marketing.platform.crypto.Digests;
import jakarta.annotation.PostConstruct;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;

public final class AudienceMembershipProjection {
    private final ConcurrentHashMap<Key, Membership> memberships = new ConcurrentHashMap<>();
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private volatile Instant lastReload = Instant.EPOCH;

    public AudienceMembershipProjection() {
        this.jdbc = null;
        this.clock = Clock.systemUTC();
    }

    public AudienceMembershipProjection(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public void update(String tenantId, String snapshotId, String subjectToken, boolean member,
            long version, Instant expiresAt) {
        if (version < 1 || expiresAt == null) throw new IllegalArgumentException("membership update is invalid");
        if (jdbc != null) {
            String hash = Digests.sha256Hex(subjectToken);
            int updated = jdbc.update("update mk_audience_membership_projection set member_value=?,membership_version=?,expires_at=?,updated_at=? where tenant_id=? and audience_id=? and subject_hash=? and membership_version<?",
                    member, version, format(expiresAt), format(clock.instant()), tenantId, snapshotId, hash, version);
            if (updated == 0) {
                try {
                    jdbc.update("insert into mk_audience_membership_projection(tenant_id,audience_id,subject_hash,member_value,membership_version,expires_at,updated_at) values(?,?,?,?,?,?,?)",
                            tenantId, snapshotId, hash, member, version, format(expiresAt), format(clock.instant()));
                } catch (DuplicateKeyException concurrentOrStale) {
                    jdbc.update("update mk_audience_membership_projection set member_value=?,membership_version=?,expires_at=?,updated_at=? where tenant_id=? and audience_id=? and subject_hash=? and membership_version<?",
                            member, version, format(expiresAt), format(clock.instant()), tenantId, snapshotId,
                            hash, version);
                }
            }
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
        if (jdbc == null) return;
        Instant floor = lastReload;
        List<StoredMembership> changed = jdbc.query("select tenant_id,audience_id,subject_hash,member_value,membership_version,expires_at,updated_at from mk_audience_membership_projection where updated_at>=? order by updated_at",
                (rs, rowNum) -> new StoredMembership(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getBoolean(4), rs.getLong(5), Instant.parse(rs.getString(6)),
                        Instant.parse(rs.getString(7))), format(floor));
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
    private record StoredMembership(String tenantId, String audienceId, String subjectHash,
            boolean member, long version, Instant expiresAt, Instant updatedAt) { }
}
