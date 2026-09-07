package com.acme.marketing.eventgateway.infrastructure.persistence.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 事件网关 MyBatis Mapper。
 *
 * <p>该接口只声明数据库操作，运行期 DML SQL 全部维护在同名 Mapper XML 中。
 */
@Mapper
public interface EventGatewayMapper {

    /** 插入事件源。 */
    int insertSource(SourceWrite source);

    /** 查询幂等回执。 */
    ReceiptRow selectReceipt(@Param("tenantId") String tenantId, @Param("sourceId") String sourceId,
            @Param("eventId") String eventId);

    /** 插入事件回执。 */
    int insertReceipt(ReceiptWrite receipt);

    /** 插入隔离事件。 */
    int insertQuarantine(QuarantineWrite quarantine);

    /** 加锁查询隔离事件。 */
    QuarantineRow selectQuarantineForUpdate(@Param("tenantId") String tenantId,
            @Param("quarantineId") String quarantineId);

    /** 标记隔离事件已经重放。 */
    int markQuarantineReplayed(@Param("tenantId") String tenantId,
            @Param("quarantineId") String quarantineId, @Param("replayedAt") String replayedAt);

    /** 标记事件回执已经重放。 */
    int markReceiptReplayed(@Param("tenantId") String tenantId, @Param("receiptId") String receiptId);

    /** 查询隔离事件列表。 */
    List<QuarantineItemRow> selectQuarantine(@Param("tenantId") String tenantId);

    /** 查询事件源。 */
    SourceRow selectSource(@Param("tenantId") String tenantId, @Param("sourceId") String sourceId);

    /** 加锁查询聚合版本。 */
    Long selectLastAggregateVersionForUpdate(@Param("tenantId") String tenantId,
            @Param("sourceId") String sourceId, @Param("businessKey") String businessKey);

    /** 更新聚合版本。 */
    int updateAggregateVersion(AggregateVersionWrite version);

    /** 插入聚合版本。 */
    int insertAggregateVersion(AggregateVersionWrite version);

    /** 递增事件流序号。 */
    int incrementStreamSequence(StreamPositionWrite position);

    /** 创建事件流序号。 */
    int insertStreamSequence(StreamPositionWrite position);

    /** 查询事件流序号。 */
    Long selectStreamSequence(@Param("tenantId") String tenantId,
            @Param("destinationTopic") String destinationTopic,
            @Param("partitionKey") String partitionKey);

    /** 插入 outbox 事件。 */
    int insertOutbox(OutboxWrite outbox);

    /** 加锁查询可领取的 outbox 候选。 */
    List<OutboxCandidateRow> selectClaimCandidates(@Param("now") String now, @Param("limit") int limit);

    /** 领取 outbox 租约。 */
    int claimOutbox(OutboxLeaseWrite lease);

    /** 标记 outbox 发布成功。 */
    int markOutboxPublished(OutboxTerminalWrite terminal);

    /** 标记 outbox 永久失败。 */
    int markOutboxDead(OutboxFailureWrite failure);

    /** 释放 outbox 租约并安排重试。 */
    int markOutboxRetry(OutboxFailureWrite failure);

    /** 查询最早的未完成 outbox 创建时间。 */
    String selectOldestPendingCreatedAt();

    /** 增加一个深度计数分桶。 */
    int incrementDepth(DepthWrite depth);

    /** 减少一个深度计数分桶。 */
    int decrementDepth(DepthWrite depth);

    /** 汇总指定范围的深度。 */
    Long sumDepth(@Param("scopeType") String scopeType, @Param("scopeId") String scopeId);

    /** 事件源写模型。 */
    record SourceWrite(String tenantId, String sourceId, String sourceUri, String sourceUriHash,
            String allowedTypes, String schemaVersions, long maxLatenessSeconds, boolean enabledValue,
            String createdAt) { }

    /** 事件源读模型。 */
    record SourceRow(String sourceUri, String allowedTypes, String schemaVersions,
            long maxLatenessSeconds, boolean enabledValue) { }

    /** 回执写模型。 */
    record ReceiptWrite(String tenantId, String receiptId, String sourceId, String eventId,
            String eventType, String businessKey, Long aggregateVersion, String statusName, String reasonCode,
            String payloadHash, String eventJson, String occurredAt, String ingestedAt) { }

    /** 回执读模型。 */
    record ReceiptRow(String receiptId, String statusName, String reasonCode, String ingestedAt,
            String payloadHash) { }

    /** 隔离事件写模型。 */
    record QuarantineWrite(String tenantId, String quarantineId, String receiptId, String reasonCode,
            String eventJson, String stateName, String createdAt) { }

    /** 隔离事件锁定读模型。 */
    record QuarantineRow(String receiptId, String eventJson, String stateName) { }

    /** 隔离事件列表读模型。 */
    record QuarantineItemRow(String quarantineId, String receiptId, String reasonCode, String stateName,
            String createdAt, String replayedAt) { }

    /** 聚合版本写模型。 */
    record AggregateVersionWrite(String tenantId, String sourceId, String businessKey,
            long lastVersion, String updatedAt) { }

    /** 事件流游标写模型。 */
    record StreamPositionWrite(String tenantId, String destinationTopic, String partitionKey,
            long lastSequence, String updatedAt) { }

    /** outbox 写模型。 */
    record OutboxWrite(String tenantId, String outboxId, String receiptId, String eventType,
            String destinationTopic, String partitionKey, long streamSequence, String payloadJson,
            String createdAt, String nextAttemptAt, int depthBucketId) { }

    /** outbox 候选读模型。 */
    record OutboxCandidateRow(String tenantId, String outboxId, String destinationTopic,
            String partitionKey, String payloadJson, int publishAttempts, long leaseVersion,
            int depthBucketId) { }

    /** outbox 租约写模型。 */
    record OutboxLeaseWrite(String tenantId, String outboxId, String workerId, String leaseUntil,
            long newLeaseVersion, long expectedLeaseVersion, String now) { }

    /** outbox 成功终态写模型。 */
    record OutboxTerminalWrite(String tenantId, String outboxId, String workerId, long leaseVersion,
            int publishAttempts, String publishedAt) { }

    /** outbox 失败状态写模型。 */
    record OutboxFailureWrite(String tenantId, String outboxId, String workerId, long leaseVersion,
            int publishAttempts, String stateAt, String lastError) { }

    /** 深度计数写模型。 */
    record DepthWrite(String scopeType, String scopeId, int bucketId, String updatedAt) { }
}
