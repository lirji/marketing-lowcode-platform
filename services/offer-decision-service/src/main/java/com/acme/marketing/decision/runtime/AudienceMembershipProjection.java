package com.acme.marketing.decision.runtime;

import com.acme.marketing.platform.crypto.Digests;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** 数据库为权威、按需读取的有界成员投影；跨副本缓存最多保留一秒，失效后不使用旧资格。 */
public final class AudienceMembershipProjection {
    private static final int DEFAULT_MAX_ENTRIES = 10_000;
    private static final Duration MAX_CACHE_AGE = Duration.ofSeconds(1);
    private static final int LOAD_STRIPES = 64;
    private final Map<Key, CachedMembership> memberships = new LinkedHashMap<>(16, 0.75f, true);
    private final Object[] stripes = new Object[LOAD_STRIPES];
    private final AudienceMembershipStore store;
    private final Clock clock;
    private final int maxEntries;
    private final Duration cacheAge;

    /** 仅供纯内存领域测试使用；生产装配必须注入持久化端口。 */
    public AudienceMembershipProjection() {
        this(null, Clock.systemUTC(), DEFAULT_MAX_ENTRIES, MAX_CACHE_AGE);
    }

    /** 保持既有装配兼容，默认缓存上限与陈旧窗口明确有界。 */
    public AudienceMembershipProjection(AudienceMembershipStore store, Clock clock) {
        this(store, clock, DEFAULT_MAX_ENTRIES, MAX_CACHE_AGE);
    }

    /** 参数只能缩短缓存窗口，不能通过配置无界延长撤销生效时间。 */
    public AudienceMembershipProjection(AudienceMembershipStore store, Clock clock, int maxEntries, Duration cacheAge) {
        if (maxEntries < 1 || cacheAge == null || cacheAge.isNegative() || cacheAge.isZero()
                || cacheAge.compareTo(MAX_CACHE_AGE) > 0) throw new IllegalArgumentException("invalid membership cache limits");
        this.store = store;
        this.clock = Objects.requireNonNull(clock);
        this.maxEntries = maxEntries;
        this.cacheAge = cacheAge;
        for (int i = 0; i < stripes.length; i++) stripes[i] = new Object();
    }

    /** 只信任数据库中的最新版本；乱序事件不能把本地缓存回填成旧的成员资格。 */
    public void update(String tenantId, String snapshotId, String subjectToken, boolean member,
            long version, Instant expiresAt) {
        if (version < 1 || expiresAt == null) throw new IllegalArgumentException("membership update is invalid");
        Key key = key(tenantId, snapshotId, subjectToken);
        synchronized (stripe(key)) {
            if (store != null) {
                store.saveIfNewer(tenantId, snapshotId, key.subjectHash(), member, version, expiresAt, clock.instant());
                synchronized (memberships) { memberships.remove(key); }
            } else {
                synchronized (memberships) {
                    var old = memberships.get(key);
                    if (old == null || version > old.membership().version()) {
                        put(key, new CachedMembership(new Membership(member, version, expiresAt), clock.instant()));
                    }
                }
            }
        }
    }

    /** 同一键读写共用固定条带锁，防止旧查询在本地撤销后回填，并合并并发回源。 */
    public boolean isMember(String tenantId, String snapshotId, String subjectToken, Instant now) {
        Key key = key(tenantId, snapshotId, subjectToken);
        synchronized (stripe(key)) {
            Instant readAt = clock.instant();
            CachedMembership cached;
            synchronized (memberships) { cached = memberships.get(key); }
            if (store != null && (cached == null || readAt.isBefore(cached.loadedAt())
                    || !readAt.isBefore(cached.loadedAt().plus(cacheAge)))) {
                // 不捕获数据库错误返回旧值；缺记录也短暂缓存，避免不存在的成员反复穿透。
                Membership membership = store.find(tenantId, snapshotId, key.subjectHash())
                        .map(row -> new Membership(row.member(), row.version(), row.expiresAt())).orElse(null);
                cached = new CachedMembership(membership, readAt);
                synchronized (memberships) { put(key, cached); }
            }
            Membership membership = cached == null ? null : cached.membership();
            return membership != null && membership.member() && membership.expiresAt().isAfter(now);
        }
    }

    private void put(Key key, CachedMembership value) {
        memberships.put(key, value);
        if (memberships.size() > maxEntries) memberships.remove(memberships.keySet().iterator().next());
    }

    private Object stripe(Key key) { return stripes[Math.floorMod(key.hashCode(), stripes.length)]; }
    private static Key key(String tenantId, String snapshotId, String subjectToken) {
        return new Key(tenantId, snapshotId, Digests.sha256Hex(subjectToken));
    }
    private record Key(String tenantId, String snapshotId, String subjectHash) { }
    private record Membership(boolean member, long version, Instant expiresAt) { }
    private record CachedMembership(Membership membership, Instant loadedAt) { }
}
