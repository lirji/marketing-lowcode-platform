package com.acme.marketing.audience.infrastructure.persistence.mapper;

import java.util.List;
import java.util.Set;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 受众库 MyBatis Mapper。
 *
 * <p>接口只声明数据库操作，SQL 统一维护在同名 Mapper XML 中。
 */
@Mapper
public interface AudienceMapper {

    /** 插入字段定义。 */
    int insertField(FieldWrite field);

    /** 查询租户全部字段定义。 */
    List<FieldRow> selectFields(@Param("tenantId") String tenantId);

    /** 批量查询指定字段的类型。 */
    List<FieldTypeRow> selectFieldTypes(@Param("tenantId") String tenantId,
            @Param("fieldIds") Set<String> fieldIds);

    /** 查询人群定义的最大版本号。 */
    Long selectLatestSegmentVersion(@Param("tenantId") String tenantId,
            @Param("segmentId") String segmentId);

    /** 插入不可变的人群定义版本。 */
    int insertSegment(SegmentWrite segment);

    /** 查询每个人群定义的最新版本。 */
    List<SegmentRow> selectLatestSegments(@Param("tenantId") String tenantId);

    /** 查询指定人群定义版本。 */
    SegmentRow selectSegment(@Param("tenantId") String tenantId, @Param("segmentId") String segmentId,
            @Param("version") long version);

    /** 插入快照元数据。 */
    int insertSnapshot(SnapshotWrite snapshot);

    /** 批量插入快照成员。 */
    int insertMembers(@Param("members") List<MemberWrite> members);

    /** 查询指定快照。 */
    SnapshotRow selectSnapshot(@Param("tenantId") String tenantId, @Param("snapshotId") String snapshotId);

    /** 使用悲观锁读取成员版本。 */
    Long selectMembershipVersionForUpdate(@Param("tenantId") String tenantId,
            @Param("snapshotId") String snapshotId, @Param("subjectHash") String subjectHash);

    /** 插入单个成员。 */
    int insertMember(MemberWrite member);

    /** 更新单个成员。 */
    int updateMember(MemberWrite member);

    /** 查询单个成员状态。 */
    MembershipRow selectMembership(@Param("tenantId") String tenantId,
            @Param("snapshotId") String snapshotId, @Param("subjectHash") String subjectHash);

    /** 字段定义写模型。 */
    record FieldWrite(String tenantId, String fieldId, String valueType, String ownerName, String provenance,
            String classification, String allowedUses, long maxAgeSeconds, String nullPolicy,
            String missingPolicy, int retentionDays, String createdAt) { }

    /** 字段定义读模型。 */
    record FieldRow(String fieldId, String valueType, String ownerName, String provenance,
            String classification, String allowedUses, long maxAgeSeconds, String nullPolicy,
            String missingPolicy, int retentionDays) { }

    /** 规则校验所需的精简字段读模型。 */
    record FieldTypeRow(String fieldId, String valueType) { }

    /** 人群定义写模型。 */
    record SegmentWrite(String tenantId, String segmentId, long versionNo, String name, String ruleJson,
            String ruleHash, String stateName, String createdBy, String createdAt) { }

    /** 人群定义读模型。 */
    record SegmentRow(String segmentId, long versionNo, String name, String stateName, String ruleJson,
            String ruleHash, String createdAt) { }

    /** 快照写模型。 */
    record SnapshotWrite(String tenantId, String snapshotId, String segmentId, long segmentVersion,
            String asOfTime, String watermarkTime, String expiresAt, long memberCount, String checksum,
            String stateName, String createdAt) { }

    /** 快照读模型。 */
    record SnapshotRow(String snapshotId, String segmentId, long segmentVersion, String asOfTime,
            String watermarkTime, String expiresAt, long memberCount, String checksum, String stateName) { }

    /** 成员写模型。 */
    record MemberWrite(String tenantId, String snapshotId, String subjectHash, long membershipVersion,
            boolean activeValue, String updatedAt) { }

    /** 成员状态读模型。 */
    record MembershipRow(boolean activeValue, long membershipVersion) { }
}
