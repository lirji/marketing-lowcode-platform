package com.acme.marketing.audience.application;

import com.acme.marketing.audience.application.AudienceService.SegmentRule;
import com.acme.marketing.platform.web.PersistentIdempotentCommandExecutor;
import com.acme.marketing.platform.web.TenantContextHolder;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/** 真实圈选只用服务端配置的画像来源和已保存的规则版本。 */
@Service
public final class CommerceAudienceService {
    private static final String MEMBER_LEVEL = "member.level";
    private static final String SUBJECT_PREFIX = "commerce-member:";
    private static final Map<String, AudienceService.ValueType> SUPPORTED_FIELDS = Map.of(MEMBER_LEVEL, AudienceService.ValueType.STRING);
    private static final int MAX_LEVELS = 1_000;
    private static final Pattern MEMBER_ID = Pattern.compile("[A-Za-z0-9_.:-]{1,64}");
    private static final Duration MAX_SOURCE_AGE = Duration.ofSeconds(30);
    private final AudienceService audiences;
    private final CommerceProfileSource source;
    private final PersistentIdempotentCommandExecutor commands;
    private final Clock clock;

    public CommerceAudienceService(AudienceService audiences, CommerceProfileSource source,
            PersistentIdempotentCommandExecutor commands, Clock clock) {
        this.audiences = audiences;
        this.source = source;
        this.commands = commands;
        this.clock = clock;
    }

    /** 仅公布当前租户可配置的字段，不返回凭据和上游租户信息。 */
    public SourceStatus status() {
        var scope = TenantContextHolder.requireCurrent();
        scope.requirePermission("audience:read");
        boolean enabled = source.supports(scope.tenantId().value());
        return new SourceStatus(enabled, enabled ? List.of(MEMBER_LEVEL) : List.of());
    }

    /** 预估回执只含人数和源观测时间，避免控制台收到会员标识。 */
    public Estimate estimate(String segmentId, long version, boolean allowLargePopulation) {
        var scope = TenantContextHolder.requireCurrent();
        scope.requirePermission("audience:preview");
        String tenant = scope.tenantId().value();
        var rule = rule(tenant, segmentId, version);
        // 旧调用方沿用原同步人群上限，控制台显式请求支持大人群的预估。
        var observation = allowLargePopulation ? source.readEstimate(tenant) : source.read(tenant);
        if (observation == null) throw CommerceProfileError.SOURCE_UNAVAILABLE.unavailable();
        requireFresh(observation.asOf());
        int count = switch (observation) {
            case CommerceProfileSource.LevelSummary summary -> countMatches(rule, summary);
            case CommerceProfileSource.SourceSnapshot snapshot -> select(rule, snapshot).size();
        };
        return new Estimate(count, observation.asOf());
    }

    /** 保留同键首次快照。只有命令键在远端读取期间持锁，且读取限制为四秒、两个并发。 */
    public AudienceService.SnapshotView snapshot(String segmentId, long version, String idempotencyKey) {
        var scope = TenantContextHolder.requireCurrent();
        scope.requirePermission("audience:snapshot");
        String tenant = scope.tenantId().value();
        requireEnabled(tenant);
        return commands.execute(scope.tenantId(), "audience.commerce.snapshot", idempotencyKey,
                new SnapshotCommand(segmentId, version), AudienceService.SnapshotView.class, () -> {
                    var rule = rule(tenant, segmentId, version);
                    var snapshot = source.read(tenant);
                    if (snapshot == null) throw CommerceProfileError.SOURCE_UNAVAILABLE.unavailable();
                    requireFresh(snapshot.asOf());
                    return audiences.createCommerceSnapshot(segmentId, version, select(rule, snapshot), snapshot.asOf());
                });
    }

    /** 预估和快照使用同一字段约束和已保存的规则版本。 */
    private SegmentRule rule(String tenant, String segmentId, long version) {
        requireEnabled(tenant);
        var segment = audiences.segment(tenant, segmentId, version);
        if (!audiences.validateRule(tenant, segment.rule()).equals(SUPPORTED_FIELDS)) {
            throw CommerceProfileError.FIELD_UNSUPPORTED.conflict();
        }
        return segment.rule();
    }

    private void requireFresh(Instant observedAt) {
        Instant now = clock.instant();
        if (observedAt == null || observedAt.isAfter(now) || observedAt.isBefore(now.minus(MAX_SOURCE_AGE))) {
            throw CommerceProfileError.SOURCE_UNAVAILABLE.unavailable();
        }
    }

    private static Set<String> select(SegmentRule rule, CommerceProfileSource.SourceSnapshot source) {
        if (source.profiles() == null || source.profiles().size() > AudienceService.MAX_SNAPSHOT_MEMBERS) {
            throw CommerceProfileError.SOURCE_UNAVAILABLE.unavailable();
        }
        Set<String> subjects = new HashSet<>();
        Set<String> seen = new HashSet<>();
        for (var profile : source.profiles()) {
            if (profile == null || profile.memberId() == null || !MEMBER_ID.matcher(profile.memberId()).matches()
                    || !isValidLevel(profile.memberLevel()) || !seen.add(profile.memberId())) {
                throw CommerceProfileError.SOURCE_UNAVAILABLE.unavailable();
            }
            if (matches(rule, profile.memberLevel())) {
                subjects.add(SUBJECT_PREFIX + profile.memberId());
            }
        }
        return subjects;
    }

    /** 逐等级应用原规则，确保字符串匹配、ALL 与 ANY 语义和逐成员计算一致。 */
    private static int countMatches(SegmentRule rule, CommerceProfileSource.LevelSummary summary) {
        if (summary.levels() == null || summary.levels().size() > MAX_LEVELS) {
            throw CommerceProfileError.SOURCE_UNAVAILABLE.unavailable();
        }
        Set<String> seen = new HashSet<>();
        int total = 0;
        int matched = 0;
        for (var level : summary.levels()) {
            if (level == null || !isValidLevel(level.memberLevel()) || !seen.add(level.memberLevel())
                    || level.memberCount() == null || level.memberCount() <= 0 || level.memberCount() > Integer.MAX_VALUE - total) {
                throw CommerceProfileError.SOURCE_UNAVAILABLE.unavailable();
            }
            int count = level.memberCount().intValue();
            total += count;
            if (matches(rule, level.memberLevel())) matched += count;
        }
        return matched;
    }

    private static boolean isValidLevel(String level) {
        return level != null && !level.isBlank() && level.length() <= 64;
    }

    private static boolean matches(SegmentRule rule, String level) {
        return AudienceService.matches(rule, Map.of(MEMBER_LEVEL, level), SUPPORTED_FIELDS);
    }

    private void requireEnabled(String tenant) {
        if (!source.supports(tenant)) {
            throw CommerceProfileError.SOURCE_DISABLED.conflict();
        }
    }

    public record SourceStatus(boolean enabled, List<String> fieldIds) { }
    public record Estimate(int memberCount, Instant asOf) { }
    private record SnapshotCommand(String segmentId, long version) { }
}
