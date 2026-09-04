package com.acme.marketing.audience.application;

import static com.acme.marketing.platform.time.SqlTime.format;

import com.acme.marketing.platform.crypto.Digests;
import com.acme.marketing.platform.error.ConflictException;
import com.acme.marketing.platform.error.NotFoundException;
import com.acme.marketing.platform.web.TenantContextHolder;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class AudienceService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Clock clock;

    public AudienceService(JdbcTemplate jdbc, ObjectMapper mapper, Clock clock) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Transactional
    public FieldDefinition registerField(FieldDefinition field) {
        var scope = TenantContextHolder.requireCurrent();
        scope.requirePermission("audience-field:write");
        jdbc.update("insert into mk_field_definition(tenant_id,field_id,value_type,owner_name,provenance,classification,allowed_uses,max_age_seconds,null_policy,missing_policy,retention_days,created_at) values(?,?,?,?,?,?,?,?,?,?,?,?)",
                scope.tenantId().value(), field.fieldId(), field.valueType().name(), field.owner(), field.provenance(),
                field.classification().name(), String.join(",", field.allowedUses()), field.maxAgeSeconds(),
                field.nullPolicy().name(), field.missingPolicy().name(), field.retentionDays(), format(clock.instant()));
        return field;
    }

    @Transactional
    public SegmentView createSegment(CreateSegmentRequest request) {
        var scope = TenantContextHolder.requireCurrent();
        scope.requirePermission("audience:write");
        validateRule(scope.tenantId().value(), request.rule());
        Long latest = jdbc.query("select max(version_no) from mk_segment_definition where tenant_id=? and segment_id=?",
                rs -> rs.next() ? rs.getObject(1, Long.class) : null, scope.tenantId().value(), request.segmentId());
        long version = latest == null ? 1 : latest + 1;
        Instant now = clock.instant();
        String ruleJson = json(request.rule());
        String digest = "sha256:" + Digests.sha256Hex(ruleJson);
        jdbc.update("insert into mk_segment_definition(tenant_id,segment_id,version_no,name,rule_json,rule_hash,state_name,created_by,created_at) values(?,?,?,?,?,?,?,?,?)",
                scope.tenantId().value(), request.segmentId(), version, request.name(), ruleJson, digest,
                "ACTIVE", scope.actorId(), format(now));
        return new SegmentView(request.segmentId(), version, request.name(), "ACTIVE", request.rule(), digest, now);
    }

    public List<FieldDefinition> fields() {
        var scope = TenantContextHolder.requireCurrent();
        scope.requirePermission("audience-field:read");
        return jdbc.query("select field_id,value_type,owner_name,provenance,classification,allowed_uses,max_age_seconds,null_policy,missing_policy,retention_days from mk_field_definition where tenant_id=? order by field_id",
                (rs, rowNum) -> new FieldDefinition(rs.getString(1), ValueType.valueOf(rs.getString(2)),
                        rs.getString(3), rs.getString(4), Classification.valueOf(rs.getString(5)),
                        csv(rs.getString(6)), rs.getLong(7), NullPolicy.valueOf(rs.getString(8)),
                        MissingPolicy.valueOf(rs.getString(9)), rs.getInt(10)), scope.tenantId().value());
    }

    public List<SegmentView> audiences() {
        var scope = TenantContextHolder.requireCurrent();
        scope.requirePermission("audience:read");
        return jdbc.query("select segment_id,version_no,name,state_name,rule_json,rule_hash,created_at from mk_segment_definition current where tenant_id=? and version_no=(select max(latest.version_no) from mk_segment_definition latest where latest.tenant_id=current.tenant_id and latest.segment_id=current.segment_id) order by name,segment_id",
                (rs, rowNum) -> new SegmentView(rs.getString(1), rs.getLong(2), rs.getString(3),
                        rs.getString(4), read(rs.getString(5), SegmentRule.class), rs.getString(6),
                        Instant.parse(rs.getString(7))), scope.tenantId().value());
    }

    public Preview preview(String segmentId, long version, List<SubjectProfile> profiles) {
        var scope = TenantContextHolder.requireCurrent();
        scope.requirePermission("audience:preview");
        SegmentView segment = segment(scope.tenantId().value(), segmentId, version);
        List<String> sample = profiles.stream().filter(profile -> matches(segment.rule(), profile.attributes()))
                .map(SubjectProfile::subjectToken).sorted().limit(20).toList();
        long count = profiles.stream().filter(profile -> matches(segment.rule(), profile.attributes())).count();
        return new Preview(count, sample, clock.instant());
    }

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
        jdbc.update("insert into mk_audience_snapshot(tenant_id,snapshot_id,segment_id,segment_version,as_of_time,watermark_time,expires_at,member_count,checksum,state_name,created_at) values(?,?,?,?,?,?,?,?,?,?,?)",
                scope.tenantId().value(), snapshotId, segmentId, version, format(request.asOf()),
                format(request.watermark()), format(request.expiresAt()), hashes.size(), checksum,
                "READY", format(clock.instant()));
        for (String hash : hashes) {
            jdbc.update("insert into mk_audience_member(tenant_id,snapshot_id,subject_hash,membership_version,active_value,updated_at) values(?,?,?,?,?,?)",
                    scope.tenantId().value(), snapshotId, hash, 1, true, format(clock.instant()));
        }
        return new SnapshotView(snapshotId, segmentId, version, request.asOf(), request.watermark(),
                request.expiresAt(), hashes.size(), checksum, "READY");
    }

    @Transactional
    public MembershipView updateMembership(String snapshotId, MembershipUpdate update) {
        var scope = TenantContextHolder.requireCurrent();
        scope.requirePermission("audience:stream-update");
        snapshot(scope.tenantId().value(), snapshotId);
        String hash = subjectHash(update.subjectToken());
        List<Long> current = jdbc.query("select membership_version from mk_audience_member where tenant_id=? and snapshot_id=? and subject_hash=? for update",
                (rs, rowNum) -> rs.getLong(1), scope.tenantId().value(), snapshotId, hash);
        if (!current.isEmpty() && update.version() <= current.getFirst()) {
            return new MembershipView(snapshotId, update.subjectToken(), false, current.getFirst(), "STALE_UPDATE_IGNORED");
        }
        if (current.isEmpty()) {
            jdbc.update("insert into mk_audience_member(tenant_id,snapshot_id,subject_hash,membership_version,active_value,updated_at) values(?,?,?,?,?,?)",
                    scope.tenantId().value(), snapshotId, hash, update.version(), update.member(), format(clock.instant()));
        } else {
            jdbc.update("update mk_audience_member set membership_version=?,active_value=?,updated_at=? where tenant_id=? and snapshot_id=? and subject_hash=?",
                    update.version(), update.member(), format(clock.instant()), scope.tenantId().value(), snapshotId, hash);
        }
        return new MembershipView(snapshotId, update.subjectToken(), update.member(), update.version(), "UPDATED");
    }

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
        List<MembershipView> results = jdbc.query("select active_value,membership_version from mk_audience_member where tenant_id=? and snapshot_id=? and subject_hash=?",
                (rs, rowNum) -> new MembershipView(snapshotId, subjectToken, rs.getBoolean(1), rs.getLong(2), reason),
                tenantId, snapshotId, subjectHash(subjectToken));
        return results.isEmpty() ? new MembershipView(snapshotId, subjectToken, false, 0, reason) : results.getFirst();
    }

    private SegmentView segment(String tenantId, String segmentId, long version) {
        List<SegmentView> segments = jdbc.query("select name,state_name,rule_json,rule_hash,created_at from mk_segment_definition where tenant_id=? and segment_id=? and version_no=?",
                (rs, rowNum) -> new SegmentView(segmentId, version, rs.getString(1), rs.getString(2),
                        read(rs.getString(3), SegmentRule.class), rs.getString(4), Instant.parse(rs.getString(5))),
                tenantId, segmentId, version);
        if (segments.isEmpty()) throw new NotFoundException("SEGMENT_NOT_FOUND", "segment version not found");
        return segments.getFirst();
    }

    private SnapshotView snapshot(String tenantId, String snapshotId) {
        List<SnapshotView> snapshots = jdbc.query("select segment_id,segment_version,as_of_time,watermark_time,expires_at,member_count,checksum,state_name from mk_audience_snapshot where tenant_id=? and snapshot_id=?",
                (rs, rowNum) -> new SnapshotView(snapshotId, rs.getString(1), rs.getLong(2),
                        Instant.parse(rs.getString(3)), Instant.parse(rs.getString(4)), Instant.parse(rs.getString(5)),
                        rs.getLong(6), rs.getString(7), rs.getString(8)), tenantId, snapshotId);
        if (snapshots.isEmpty()) throw new NotFoundException("SNAPSHOT_NOT_FOUND", "audience snapshot not found");
        return snapshots.getFirst();
    }

    private void validateRule(String tenantId, SegmentRule rule) {
        if (rule.conditions().isEmpty()) throw new IllegalArgumentException("segment rule is empty");
        for (Condition condition : rule.conditions()) {
            List<ValueType> types = jdbc.query("select value_type from mk_field_definition where tenant_id=? and field_id=?",
                    (rs, rowNum) -> ValueType.valueOf(rs.getString(1)), tenantId, condition.fieldId());
            if (types.isEmpty()) throw new ConflictException("FIELD_NOT_REGISTERED", condition.fieldId());
            validateValue(types.getFirst(), condition.value());
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

    private static Set<String> csv(String value) {
        return value == null || value.isBlank() ? Set.of() : Set.of(value.split(","));
    }

    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (JacksonException failure) { throw new IllegalArgumentException("audience rule cannot be serialized", failure); }
    }

    private <T> T read(String value, Class<T> type) {
        try { return mapper.readValue(value, type); }
        catch (JacksonException failure) { throw new IllegalStateException("stored audience rule is invalid", failure); }
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
