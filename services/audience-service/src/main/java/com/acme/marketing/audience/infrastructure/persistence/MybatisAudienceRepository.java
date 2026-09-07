package com.acme.marketing.audience.infrastructure.persistence;

import static com.acme.marketing.platform.time.SqlTime.format;

import com.acme.marketing.audience.application.AudienceRepository;
import com.acme.marketing.audience.application.AudienceService.Classification;
import com.acme.marketing.audience.application.AudienceService.FieldDefinition;
import com.acme.marketing.audience.application.AudienceService.MissingPolicy;
import com.acme.marketing.audience.application.AudienceService.NullPolicy;
import com.acme.marketing.audience.application.AudienceService.SegmentRule;
import com.acme.marketing.audience.application.AudienceService.SegmentView;
import com.acme.marketing.audience.application.AudienceService.SnapshotView;
import com.acme.marketing.audience.application.AudienceService.ValueType;
import com.acme.marketing.audience.infrastructure.persistence.mapper.AudienceMapper;
import com.acme.marketing.audience.infrastructure.persistence.mapper.AudienceMapper.FieldRow;
import com.acme.marketing.audience.infrastructure.persistence.mapper.AudienceMapper.FieldTypeRow;
import com.acme.marketing.audience.infrastructure.persistence.mapper.AudienceMapper.FieldWrite;
import com.acme.marketing.audience.infrastructure.persistence.mapper.AudienceMapper.MemberWrite;
import com.acme.marketing.audience.infrastructure.persistence.mapper.AudienceMapper.MembershipRow;
import com.acme.marketing.audience.infrastructure.persistence.mapper.AudienceMapper.SegmentRow;
import com.acme.marketing.audience.infrastructure.persistence.mapper.AudienceMapper.SegmentWrite;
import com.acme.marketing.audience.infrastructure.persistence.mapper.AudienceMapper.SnapshotRow;
import com.acme.marketing.audience.infrastructure.persistence.mapper.AudienceMapper.SnapshotWrite;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Repository;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * 基于 MyBatis 的受众持久化适配器。
 *
 * <p>这里负责领域模型与数据库读写模型的转换，SQL 本身全部位于 Mapper XML。
 */
@Repository
public class MybatisAudienceRepository implements AudienceRepository {
    private static final int MEMBER_BATCH_SIZE = 500;

    private final AudienceMapper mapper;
    private final ObjectMapper objectMapper;

    public MybatisAudienceRepository(AudienceMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public void saveField(String tenantId, FieldDefinition field, Instant createdAt) {
        requireOne(mapper.insertField(new FieldWrite(tenantId, field.fieldId(), field.valueType().name(),
                field.owner(), field.provenance(), field.classification().name(),
                String.join(",", field.allowedUses()), field.maxAgeSeconds(), field.nullPolicy().name(),
                field.missingPolicy().name(), field.retentionDays(), format(createdAt))), "保存字段定义");
    }

    @Override
    public List<FieldDefinition> findFields(String tenantId) {
        return mapper.selectFields(tenantId).stream().map(MybatisAudienceRepository::toField).toList();
    }

    @Override
    public Map<String, ValueType> findFieldTypes(String tenantId, Set<String> fieldIds) {
        if (fieldIds.isEmpty()) return Map.of();
        Map<String, ValueType> types = new LinkedHashMap<>();
        for (FieldTypeRow row : mapper.selectFieldTypes(tenantId, fieldIds)) {
            types.put(row.fieldId(), ValueType.valueOf(row.valueType()));
        }
        return Map.copyOf(types);
    }

    @Override
    public Optional<Long> findLatestSegmentVersion(String tenantId, String segmentId) {
        return Optional.ofNullable(mapper.selectLatestSegmentVersion(tenantId, segmentId));
    }

    @Override
    public void saveSegment(String tenantId, String actorId, SegmentView segment) {
        requireOne(mapper.insertSegment(new SegmentWrite(tenantId, segment.segmentId(), segment.version(),
                segment.name(), json(segment.rule()), segment.ruleHash(), segment.status(), actorId,
                format(segment.createdAt()))), "保存人群定义");
    }

    @Override
    public List<SegmentView> findLatestSegments(String tenantId) {
        return mapper.selectLatestSegments(tenantId).stream().map(this::toSegment).toList();
    }

    @Override
    public Optional<SegmentView> findSegment(String tenantId, String segmentId, long version) {
        return Optional.ofNullable(mapper.selectSegment(tenantId, segmentId, version)).map(this::toSegment);
    }

    @Override
    public void saveSnapshot(String tenantId, SnapshotView snapshot, List<String> subjectHashes, Instant createdAt) {
        requireOne(mapper.insertSnapshot(new SnapshotWrite(tenantId, snapshot.snapshotId(), snapshot.segmentId(),
                snapshot.segmentVersion(), format(snapshot.asOf()), format(snapshot.watermark()),
                format(snapshot.expiresAt()), snapshot.memberCount(), snapshot.checksum(), snapshot.state(),
                format(createdAt))), "保存人群快照");
        String updatedAt = format(createdAt);
        List<MemberWrite> members = subjectHashes.stream()
                .map(hash -> new MemberWrite(tenantId, snapshot.snapshotId(), hash, 1, true, updatedAt))
                .toList();
        // 限制单条多值 INSERT 的大小，避免超大人群触发 MySQL max_allowed_packet。
        for (int start = 0; start < members.size(); start += MEMBER_BATCH_SIZE) {
            int end = Math.min(start + MEMBER_BATCH_SIZE, members.size());
            int inserted = mapper.insertMembers(members.subList(start, end));
            if (inserted != end - start) {
                throw new IllegalStateException("批量保存人群成员的受影响行数不正确");
            }
        }
    }

    @Override
    public Optional<SnapshotView> findSnapshot(String tenantId, String snapshotId) {
        return Optional.ofNullable(mapper.selectSnapshot(tenantId, snapshotId))
                .map(MybatisAudienceRepository::toSnapshot);
    }

    @Override
    public Optional<Long> lockMembershipVersion(String tenantId, String snapshotId, String subjectHash) {
        return Optional.ofNullable(mapper.selectMembershipVersionForUpdate(tenantId, snapshotId, subjectHash));
    }

    @Override
    public void insertMembership(String tenantId, String snapshotId, String subjectHash, long version,
            boolean member, Instant updatedAt) {
        requireOne(mapper.insertMember(memberWrite(tenantId, snapshotId, subjectHash, version, member, updatedAt)),
                "新增人群成员");
    }

    @Override
    public void updateMembership(String tenantId, String snapshotId, String subjectHash, long version,
            boolean member, Instant updatedAt) {
        requireOne(mapper.updateMember(memberWrite(tenantId, snapshotId, subjectHash, version, member, updatedAt)),
                "更新人群成员");
    }

    @Override
    public Optional<StoredMembership> findMembership(String tenantId, String snapshotId, String subjectHash) {
        MembershipRow row = mapper.selectMembership(tenantId, snapshotId, subjectHash);
        return row == null ? Optional.empty()
                : Optional.of(new StoredMembership(row.activeValue(), row.membershipVersion()));
    }

    private SegmentView toSegment(SegmentRow row) {
        return new SegmentView(row.segmentId(), row.versionNo(), row.name(), row.stateName(),
                read(row.ruleJson(), SegmentRule.class), row.ruleHash(), Instant.parse(row.createdAt()));
    }

    private static FieldDefinition toField(FieldRow row) {
        return new FieldDefinition(row.fieldId(), ValueType.valueOf(row.valueType()), row.ownerName(),
                row.provenance(), Classification.valueOf(row.classification()), csv(row.allowedUses()),
                row.maxAgeSeconds(), NullPolicy.valueOf(row.nullPolicy()),
                MissingPolicy.valueOf(row.missingPolicy()), row.retentionDays());
    }

    private static SnapshotView toSnapshot(SnapshotRow row) {
        return new SnapshotView(row.snapshotId(), row.segmentId(), row.segmentVersion(),
                Instant.parse(row.asOfTime()), Instant.parse(row.watermarkTime()), Instant.parse(row.expiresAt()),
                row.memberCount(), row.checksum(), row.stateName());
    }

    private static MemberWrite memberWrite(String tenantId, String snapshotId, String subjectHash, long version,
            boolean member, Instant updatedAt) {
        return new MemberWrite(tenantId, snapshotId, subjectHash, version, member, format(updatedAt));
    }

    private static Set<String> csv(String value) {
        return value == null || value.isBlank() ? Set.of() : Set.copyOf(new HashSet<>(Arrays.asList(value.split(","))));
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException failure) {
            throw new IllegalArgumentException("人群规则无法序列化", failure);
        }
    }

    private <T> T read(String value, Class<T> type) {
        try {
            return objectMapper.readValue(value, type);
        } catch (JacksonException failure) {
            throw new IllegalStateException("数据库中的人群规则无效", failure);
        }
    }

    private static void requireOne(int affectedRows, String operation) {
        if (affectedRows != 1) throw new IllegalStateException(operation + "的受影响行数不正确");
    }
}
