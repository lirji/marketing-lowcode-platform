package com.acme.marketing.audience.application;

import com.acme.marketing.audience.application.AudienceService.FieldDefinition;
import com.acme.marketing.audience.application.AudienceService.SegmentView;
import com.acme.marketing.audience.application.AudienceService.SnapshotView;
import com.acme.marketing.audience.application.AudienceService.ValueType;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 受众上下文的持久化端口。
 *
 * <p>应用层只依赖业务语义，不感知 MyBatis、SQL 或数据库结果集。
 */
public interface AudienceRepository {

    /** 保存租户字段定义。 */
    void saveField(String tenantId, FieldDefinition field, Instant createdAt);

    /** 按字段标识稳定排序返回租户字段定义。 */
    List<FieldDefinition> findFields(String tenantId);

    /** 一次性加载规则引用字段的类型，避免逐条件查询。 */
    Map<String, ValueType> findFieldTypes(String tenantId, Set<String> fieldIds);

    /** 返回人群定义的最新版本号；尚未创建时为空。 */
    Optional<Long> findLatestSegmentVersion(String tenantId, String segmentId);

    /** 保存不可变的人群定义版本。 */
    void saveSegment(String tenantId, String actorId, SegmentView segment);

    /** 返回每个人群定义的最新版本。 */
    List<SegmentView> findLatestSegments(String tenantId);

    /** 按租户、人群标识和版本查询定义。 */
    Optional<SegmentView> findSegment(String tenantId, String segmentId, long version);

    /**
     * 保存快照及其成员。
     *
     * <p>调用方事务同时覆盖元数据和成员，确保不会留下半成品快照。
     */
    void saveSnapshot(String tenantId, SnapshotView snapshot, List<String> subjectHashes, Instant createdAt);

    /** 按租户和快照标识查询快照。 */
    Optional<SnapshotView> findSnapshot(String tenantId, String snapshotId);

    /** 锁定成员行并返回当前版本；不存在时为空。 */
    Optional<Long> lockMembershipVersion(String tenantId, String snapshotId, String subjectHash);

    /** 插入新的快照成员。 */
    void insertMembership(String tenantId, String snapshotId, String subjectHash, long version,
            boolean member, Instant updatedAt);

    /** 更新已存在的快照成员。 */
    void updateMembership(String tenantId, String snapshotId, String subjectHash, long version,
            boolean member, Instant updatedAt);

    /** 查询成员当前状态。 */
    Optional<StoredMembership> findMembership(String tenantId, String snapshotId, String subjectHash);

    /** 数据库存储的成员状态，不包含调用时才确定的新鲜度原因。 */
    record StoredMembership(boolean member, long version) { }
}
