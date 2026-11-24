package com.acme.marketing.audience.application;

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
    public Estimate estimate(String segmentId, long version) {
        var scope = TenantContextHolder.requireCurrent();
        scope.requirePermission("audience:preview");
        var selection = select(scope.tenantId().value(), segmentId, version);
        return new Estimate(selection.subjects().size(), selection.asOf());
    }

    /** 保留同键首次快照。只有命令键在远端读取期间持锁，且读取限制为四秒、两个并发。 */
    public AudienceService.SnapshotView snapshot(String segmentId, long version, String idempotencyKey) {
        var scope = TenantContextHolder.requireCurrent();
        scope.requirePermission("audience:snapshot");
        String tenant = scope.tenantId().value();
        requireEnabled(tenant);
        return commands.execute(scope.tenantId(), "audience.commerce.snapshot", idempotencyKey,
                new SnapshotCommand(segmentId, version), AudienceService.SnapshotView.class, () -> {
                    var selection = select(tenant, segmentId, version);
                    return audiences.createCommerceSnapshot(segmentId, version, selection.subjects(), selection.asOf());
                });
    }

    private Selection select(String tenant, String segmentId, long version) {
        requireEnabled(tenant);
        var segment = audiences.segment(tenant, segmentId, version);
        var types = audiences.validateRule(tenant, segment.rule());
        if (!types.equals(Map.of(MEMBER_LEVEL, AudienceService.ValueType.STRING))) {
            throw CommerceProfileError.FIELD_UNSUPPORTED.conflict();
        }

        var result = source.read(tenant);
        Instant now = clock.instant();
        if (result == null || result.asOf() == null || result.asOf().isAfter(now)
                || result.asOf().isBefore(now.minus(MAX_SOURCE_AGE)) || result.profiles() == null
                || result.profiles().size() > AudienceService.MAX_SNAPSHOT_MEMBERS) {
            throw CommerceProfileError.SOURCE_UNAVAILABLE.unavailable();
        }

        Set<String> subjects = new HashSet<>();
        Set<String> seen = new HashSet<>();
        for (var profile : result.profiles()) {
            if (profile == null || profile.memberId() == null || !MEMBER_ID.matcher(profile.memberId()).matches()
                    || profile.memberLevel() == null || profile.memberLevel().isBlank()
                    || profile.memberLevel().length() > 64 || !seen.add(profile.memberId())) {
                throw CommerceProfileError.SOURCE_UNAVAILABLE.unavailable();
            }
            if (AudienceService.matches(segment.rule(), Map.of(MEMBER_LEVEL, profile.memberLevel()), types)) {
                subjects.add(SUBJECT_PREFIX + profile.memberId());
            }
        }
        return new Selection(subjects, result.asOf());
    }

    private void requireEnabled(String tenant) {
        if (!source.supports(tenant)) {
            throw CommerceProfileError.SOURCE_DISABLED.conflict();
        }
    }

    public record SourceStatus(boolean enabled, List<String> fieldIds) { }
    public record Estimate(int memberCount, Instant asOf) { }
    private record Selection(Set<String> subjects, Instant asOf) { }
    private record SnapshotCommand(String segmentId, long version) { }
}
