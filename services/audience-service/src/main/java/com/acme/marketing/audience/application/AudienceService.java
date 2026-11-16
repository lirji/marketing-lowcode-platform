package com.acme.marketing.audience.application;

import com.acme.marketing.platform.crypto.Digests;
import com.acme.marketing.platform.error.ConflictException;
import com.acme.marketing.platform.error.NotFoundException;
import com.acme.marketing.platform.web.TenantContextHolder;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * 受众领域用例编排服务。
 *
 * <p>事务、鉴权和业务规则保留在应用层；所有数据库访问统一经由 {@link AudienceRepository}。
 */
@Service
public class AudienceService {
    /** 同步快照入口的事务预算；大人群必须走后续的持久化批量圈选。 */
    public static final int MAX_SNAPSHOT_MEMBERS = 10_000;
    private static final String MEMBERSHIP_VERSION_CONFLICT = "AUDIENCE_MEMBERSHIP_VERSION_CONFLICT";
    private static final String MEMBERSHIP_VERSION_CONFLICT_MESSAGE = "相同成员版本不能表示不同的成员状态";
    private static final String SNAPSHOT_EXPIRED_MESSAGE = "快照创建时已过期";
    private static final String SNAPSHOT_MEMBERS_INVALID_MESSAGE = "快照成员必须非空白且不能超过 " + MAX_SNAPSHOT_MEMBERS + " 人";
    private static final String SNAPSHOT_TIME_INVALID_MESSAGE = "快照时间必须满足 watermark <= asOf < expiresAt";
    private static final String MEMBERSHIP_INVALID_MESSAGE = "成员主体不能为空、版本必须为正整数且必须明确成员状态";
    private final AudienceRepository repository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public AudienceService(AudienceRepository repository, ObjectMapper objectMapper, Clock clock) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /** 注册可用于人群规则的字段定义。 */
    @Transactional
    public FieldDefinition registerField(FieldDefinition field) {
        var scope = TenantContextHolder.requireCurrent();
        scope.requirePermission("audience-field:write");
        repository.saveField(scope.tenantId().value(), field, clock.instant());
        return field;
    }

    /** 校验规则并创建新的不可变人群定义版本。 */
    @Transactional
    public SegmentView createSegment(CreateSegmentRequest request) {
        var scope = TenantContextHolder.requireCurrent();
        scope.requirePermission("audience:write");
        validateRule(scope.tenantId().value(), request.rule());
        long version = repository.findLatestSegmentVersion(scope.tenantId().value(), request.segmentId())
                .map(latest -> Math.addExact(latest, 1L)).orElse(1L);
        Instant now = clock.instant();
        String ruleJson = json(request.rule());
        String digest = "sha256:" + Digests.sha256Hex(ruleJson);
        SegmentView segment = new SegmentView(request.segmentId(), version, request.name(), "ACTIVE",
                request.rule(), digest, now);
        repository.saveSegment(scope.tenantId().value(), scope.actorId(), segment);
        return segment;
    }

    /** 查询当前租户已注册的字段。 */
    public List<FieldDefinition> fields() {
        var scope = TenantContextHolder.requireCurrent();
        scope.requirePermission("audience-field:read");
        return repository.findFields(scope.tenantId().value());
    }

    /** 查询当前租户每个人群定义的最新版本。 */
    public List<SegmentView> audiences() {
        var scope = TenantContextHolder.requireCurrent();
        scope.requirePermission("audience:read");
        return repository.findLatestSegments(scope.tenantId().value());
    }

    /** 使用请求内的样本人群预览规则命中结果。 */
    public Preview preview(String segmentId, long version, List<SubjectProfile> profiles) {
        var scope = TenantContextHolder.requireCurrent();
        scope.requirePermission("audience:preview");
        SegmentView segment = segment(scope.tenantId().value(), segmentId, version);
        Map<String, ValueType> types = validateRule(scope.tenantId().value(), segment.rule());
        List<String> sample = profiles.stream().filter(profile -> matches(segment.rule(), profile.attributes(), types))
                .map(SubjectProfile::subjectToken).sorted().limit(20).toList();
        long count = profiles.stream().filter(profile -> matches(segment.rule(), profile.attributes(), types)).count();
        return new Preview(count, sample, clock.instant());
    }

    /** 保存初始快照基线；既有流更新接口只推进成员状态，不改写初始计数与校验和。 */
    @Transactional
    public SnapshotView createSnapshot(String segmentId, long version, SnapshotRequest request) {
        var scope = TenantContextHolder.requireCurrent();
        scope.requirePermission("audience:snapshot");
        segment(scope.tenantId().value(), segmentId, version);
        if (!request.expiresAt().isAfter(clock.instant())) throw new IllegalArgumentException(SNAPSHOT_EXPIRED_MESSAGE);
        List<String> hashes = request.subjectTokens().stream().map(AudienceService::subjectHash).sorted().distinct().toList();
        String checksum = "sha256:" + Digests.sha256Hex(String.join("\n", hashes));
        String snapshotId = UUID.randomUUID().toString();
        SnapshotView snapshot = new SnapshotView(snapshotId, segmentId, version, request.asOf(), request.watermark(),
                request.expiresAt(), hashes.size(), checksum, "READY");
        repository.saveSnapshot(scope.tenantId().value(), snapshot, hashes, clock.instant());
        return snapshot;
    }

    /** 按单调递增版本更新快照成员，过期事件不会覆盖新状态。 */
    @Transactional
    public MembershipView updateMembership(String snapshotId, MembershipUpdate update) {
        var scope = TenantContextHolder.requireCurrent();
        scope.requirePermission("audience:stream-update");
        snapshot(scope.tenantId().value(), snapshotId);
        String hash = subjectHash(update.subjectToken());
        // 唯一键与条件 upsert 同时覆盖首条成员的并发插入，避免先查空行再 INSERT 的竞争。
        var current = repository.saveMembershipIfNewer(scope.tenantId().value(), snapshotId, hash, update.version(),
                update.member(), clock.instant());
        if (current.version() == update.version() && current.member() != update.member()) {
            throw new ConflictException(MEMBERSHIP_VERSION_CONFLICT, MEMBERSHIP_VERSION_CONFLICT_MESSAGE);
        }
        return new MembershipView(snapshotId, update.subjectToken(), current.member(), current.version(),
                current.version() > update.version() ? "STALE_UPDATE_IGNORED" : "UPDATED");
    }

    /** 按快照时效策略查询成员状态。 */
    public MembershipView membership(String snapshotId, String subjectToken, Instant usedAt, StalePolicy stalePolicy) {
        var scope = TenantContextHolder.requireCurrent();
        scope.requirePermission("audience:lookup");
        SnapshotView snapshot = snapshot(scope.tenantId().value(), snapshotId);
        if (!usedAt.isBefore(snapshot.expiresAt())) {
            return switch (stalePolicy) {
                case REJECT_CANDIDATE -> new MembershipView(snapshotId, subjectToken, false, 0, "STALE_REJECTED");
                case GENERIC_PATH -> new MembershipView(snapshotId, subjectToken, false, 0, "STALE_GENERIC_PATH");
                case USE_LAST_GOOD -> lookup(scope.tenantId().value(), snapshotId, subjectToken, "STALE_LAST_GOOD");
            };
        }
        return lookup(scope.tenantId().value(), snapshotId, subjectToken, "FRESH");
    }

    private MembershipView lookup(String tenantId, String snapshotId, String subjectToken, String reason) {
        return repository.findMembership(tenantId, snapshotId, subjectHash(subjectToken))
                .map(stored -> new MembershipView(snapshotId, subjectToken, stored.member(), stored.version(), reason))
                .orElseGet(() -> new MembershipView(snapshotId, subjectToken, false, 0, reason));
    }

    private SegmentView segment(String tenantId, String segmentId, long version) {
        return repository.findSegment(tenantId, segmentId, version)
                .orElseThrow(() -> new NotFoundException("SEGMENT_NOT_FOUND", "segment version not found"));
    }

    private SnapshotView snapshot(String tenantId, String snapshotId) {
        return repository.findSnapshot(tenantId, snapshotId)
                .orElseThrow(() -> new NotFoundException("SNAPSHOT_NOT_FOUND", "audience snapshot not found"));
    }

    private Map<String, ValueType> validateRule(String tenantId, SegmentRule rule) {
        if (rule.conditions().isEmpty()) throw new IllegalArgumentException("segment rule is empty");
        Set<String> fieldIds = rule.conditions().stream().map(Condition::fieldId)
                .collect(java.util.stream.Collectors.toSet());
        Map<String, ValueType> types = repository.findFieldTypes(tenantId, fieldIds);
        for (Condition condition : rule.conditions()) {
            ValueType type = types.get(condition.fieldId());
            if (type == null) throw new ConflictException("FIELD_NOT_REGISTERED", condition.fieldId());
            AudienceRuleEvaluator.validate(type, condition);
        }
        return types;
    }

    static boolean matches(SegmentRule rule, Map<String, String> attributes, Map<String, ValueType> types) {
        java.util.function.Predicate<Condition> condition = item ->
                AudienceRuleEvaluator.matches(types.get(item.fieldId()), item, attributes.get(item.fieldId()));
        return rule.match() == Match.ALL ? rule.conditions().stream().allMatch(condition)
                : rule.conditions().stream().anyMatch(condition);
    }

    private static String subjectHash(String subjectToken) {
        if (subjectToken == null || subjectToken.isBlank()) throw new IllegalArgumentException("subject token is required");
        return Digests.sha256Hex(subjectToken);
    }

    private String json(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (JacksonException failure) { throw new IllegalArgumentException("audience rule cannot be serialized", failure); }
    }

    public enum ValueType { STRING, DECIMAL, BOOLEAN, INSTANT }
    public enum Classification { PUBLIC, INTERNAL, PERSONAL, SENSITIVE }
    public enum NullPolicy { NO_MATCH, EXPLICIT_NULL, ERROR }
    public enum MissingPolicy { NO_MATCH, REJECT, USE_DEFAULT }
    public enum StalePolicy { REJECT_CANDIDATE, USE_LAST_GOOD, GENERIC_PATH }
    public enum Match { ALL, ANY }
    public enum Operator { EQ, NE, GT, GTE, LT, LTE, IN }
    public record FieldDefinition(String fieldId, ValueType valueType, String owner, String provenance,
            Classification classification, Set<String> allowedUses, long maxAgeSeconds,
            NullPolicy nullPolicy, MissingPolicy missingPolicy, int retentionDays) {
        public FieldDefinition {
            allowedUses = Set.copyOf(allowedUses);
            if (fieldId == null || fieldId.isBlank() || owner == null || provenance == null
                    || maxAgeSeconds < 0 || retentionDays < 1) throw new IllegalArgumentException("field definition is invalid");
        }
    }
    public record Condition(String fieldId, Operator operator, String value) { }
    public record SegmentRule(Match match, List<Condition> conditions) {
        public SegmentRule { conditions = List.copyOf(conditions); }
    }
    public record CreateSegmentRequest(String segmentId, String name, SegmentRule rule) { }
    public record SegmentView(String segmentId, long version, String name, String status, SegmentRule rule,
            String ruleHash, Instant createdAt) { }
    public record SubjectProfile(String subjectToken, Map<String, String> attributes) {
        public SubjectProfile { attributes = Map.copyOf(attributes); }
    }
    public record Preview(long estimatedCount, List<String> sampleSubjectTokens, Instant calculatedAt) { }
    public record SnapshotRequest(Set<String> subjectTokens, Instant asOf, Instant watermark, Instant expiresAt) {
        public SnapshotRequest {
            if (subjectTokens == null || subjectTokens.size() > MAX_SNAPSHOT_MEMBERS
                    || subjectTokens.stream().anyMatch(token -> token == null || token.isBlank())) {
                throw new IllegalArgumentException(SNAPSHOT_MEMBERS_INVALID_MESSAGE);
            }
            if (asOf == null || watermark == null || expiresAt == null || watermark.isAfter(asOf)
                    || !expiresAt.isAfter(asOf)) {
                throw new IllegalArgumentException(SNAPSHOT_TIME_INVALID_MESSAGE);
            }
            // 成员集合没有输入顺序语义；固定排序使进程重启后的幂等指纹保持一致。
            subjectTokens = java.util.Collections.unmodifiableSet(new java.util.TreeSet<>(subjectTokens));
        }
    }
    public record SnapshotView(String snapshotId, String segmentId, long segmentVersion, Instant asOf,
            Instant watermark, Instant expiresAt, long memberCount, String checksum, String state) { }
    public record MembershipUpdate(String subjectToken, long version, Boolean member) {
        public MembershipUpdate {
            if (subjectToken == null || subjectToken.isBlank() || version < 1 || member == null) {
                throw new IllegalArgumentException(MEMBERSHIP_INVALID_MESSAGE);
            }
        }
    }
    public record MembershipView(String snapshotId, String subjectToken, boolean member, long version, String freshness) { }
}
