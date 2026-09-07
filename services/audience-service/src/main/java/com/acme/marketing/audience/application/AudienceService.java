package com.acme.marketing.audience.application;

import com.acme.marketing.platform.crypto.Digests;
import com.acme.marketing.platform.error.ConflictException;
import com.acme.marketing.platform.error.NotFoundException;
import com.acme.marketing.platform.web.TenantContextHolder;
import java.math.BigDecimal;
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
        List<String> sample = profiles.stream().filter(profile -> matches(segment.rule(), profile.attributes()))
                .map(SubjectProfile::subjectToken).sorted().limit(20).toList();
        long count = profiles.stream().filter(profile -> matches(segment.rule(), profile.attributes())).count();
        return new Preview(count, sample, clock.instant());
    }

    /** 创建带成员明细的不可变人群快照。 */
    @Transactional
    public SnapshotView createSnapshot(String segmentId, long version, SnapshotRequest request) {
        var scope = TenantContextHolder.requireCurrent();
        scope.requirePermission("audience:snapshot");
        segment(scope.tenantId().value(), segmentId, version);
        if (request.expiresAt() == null || !request.expiresAt().isAfter(request.asOf())) {
            throw new IllegalArgumentException("snapshot expiry must be after as-of time");
        }
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
        var current = repository.lockMembershipVersion(scope.tenantId().value(), snapshotId, hash);
        if (current.isPresent() && update.version() <= current.orElseThrow()) {
            long currentVersion = current.orElseThrow();
            return new MembershipView(snapshotId, update.subjectToken(), false, currentVersion,
                    "STALE_UPDATE_IGNORED");
        }
        if (current.isEmpty()) {
            repository.insertMembership(scope.tenantId().value(), snapshotId, hash, update.version(), update.member(),
                    clock.instant());
        } else {
            repository.updateMembership(scope.tenantId().value(), snapshotId, hash, update.version(), update.member(),
                    clock.instant());
        }
        return new MembershipView(snapshotId, update.subjectToken(), update.member(), update.version(), "UPDATED");
    }

    /** 按快照时效策略查询成员状态。 */
    public MembershipView membership(String snapshotId, String subjectToken, Instant usedAt, StalePolicy stalePolicy) {
        var scope = TenantContextHolder.requireCurrent();
        scope.requirePermission("audience:lookup");
        SnapshotView snapshot = snapshot(scope.tenantId().value(), snapshotId);
        if (usedAt.isAfter(snapshot.expiresAt())) {
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

    private void validateRule(String tenantId, SegmentRule rule) {
        if (rule.conditions().isEmpty()) throw new IllegalArgumentException("segment rule is empty");
        Set<String> fieldIds = rule.conditions().stream().map(Condition::fieldId)
                .collect(java.util.stream.Collectors.toSet());
        Map<String, ValueType> types = repository.findFieldTypes(tenantId, fieldIds);
        for (Condition condition : rule.conditions()) {
            ValueType type = types.get(condition.fieldId());
            if (type == null) throw new ConflictException("FIELD_NOT_REGISTERED", condition.fieldId());
            validateValue(type, condition.value());
        }
    }

    private static void validateValue(ValueType type, String value) {
        try {
            switch (type) {
                case DECIMAL -> new BigDecimal(value);
                case BOOLEAN -> {
                    if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) throw new IllegalArgumentException();
                }
                case INSTANT -> Instant.parse(value);
                case STRING -> { if (value == null) throw new IllegalArgumentException(); }
            }
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("condition value does not match field type " + type, invalid);
        }
    }

    static boolean matches(SegmentRule rule, Map<String, String> attributes) {
        java.util.function.Predicate<Condition> condition = item -> compare(attributes.get(item.fieldId()), item);
        return rule.match() == Match.ALL ? rule.conditions().stream().allMatch(condition)
                : rule.conditions().stream().anyMatch(condition);
    }

    private static boolean compare(String actual, Condition condition) {
        if (actual == null) return false;
        return switch (condition.operator()) {
            case EQ -> actual.equals(condition.value());
            case NE -> !actual.equals(condition.value());
            case GT -> new BigDecimal(actual).compareTo(new BigDecimal(condition.value())) > 0;
            case GTE -> new BigDecimal(actual).compareTo(new BigDecimal(condition.value())) >= 0;
            case LT -> new BigDecimal(actual).compareTo(new BigDecimal(condition.value())) < 0;
            case LTE -> new BigDecimal(actual).compareTo(new BigDecimal(condition.value())) <= 0;
            case IN -> Set.of(condition.value().split(",")).contains(actual);
        };
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
        public SnapshotRequest { subjectTokens = Set.copyOf(subjectTokens); }
    }
    public record SnapshotView(String snapshotId, String segmentId, long segmentVersion, Instant asOf,
            Instant watermark, Instant expiresAt, long memberCount, String checksum, String state) { }
    public record MembershipUpdate(String subjectToken, long version, boolean member) { }
    public record MembershipView(String snapshotId, String subjectToken, boolean member, long version, String freshness) { }
}
