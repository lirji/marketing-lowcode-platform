package com.acme.marketing.eventgateway.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 事件接入用例的持久化端口。
 *
 * <p>端口表达接入事务需要的原子数据库操作；MyBatis、表结构和 SQL 均由基础设施适配器负责。
 */
public interface EventIngestionRepository {

    /** 保存事件源注册信息。 */
    void insertSource(SourceWrite source);

    /** 按来源与事件号查询幂等回执。 */
    Optional<StoredReceipt> findReceipt(String tenantId, String sourceId, String eventId);

    /** 尝试保存事件回执；并发幂等键已存在时返回 false。 */
    boolean insertReceipt(ReceiptWrite receipt);

    /** 保存隔离事件。 */
    void insertQuarantine(QuarantineWrite quarantine);

    /** 加锁读取隔离事件，保证同一隔离项只被成功重放一次。 */
    Optional<QuarantineRecord> lockQuarantine(String tenantId, String quarantineId);

    /** 标记隔离事件已经重放。 */
    void markQuarantineReplayed(String tenantId, String quarantineId, Instant replayedAt);

    /** 将原回执更新为已重放。 */
    void markReceiptReplayed(String tenantId, String receiptId);

    /** 查询租户的隔离事件列表。 */
    List<QuarantineItem> findQuarantine(String tenantId);

    /** 查询事件源配置。 */
    Optional<SourceRecord> findSource(String tenantId, String sourceId);

    /** 加锁读取业务聚合的最后版本。 */
    Optional<Long> lockLastAggregateVersion(String tenantId, String sourceId, String businessKey);

    /** 更新业务聚合版本；不存在时返回 false。 */
    boolean updateAggregateVersion(String tenantId, String sourceId, String businessKey,
            long aggregateVersion, Instant updatedAt);

    /** 创建业务聚合版本游标。 */
    void insertAggregateVersion(String tenantId, String sourceId, String businessKey,
            long aggregateVersion, Instant updatedAt);

    /** 递增事件流序号；流尚未建立时返回 false。 */
    boolean incrementStreamSequence(String tenantId, String topic, String partitionKey, Instant updatedAt);

    /** 尝试创建事件流序号；并发创建者已经成功时返回 false。 */
    boolean insertInitialStreamSequence(String tenantId, String topic, String partitionKey, Instant updatedAt);

    /** 查询当前事件流序号。 */
    Optional<Long> findStreamSequence(String tenantId, String topic, String partitionKey);

    /** 保存待发布事件。 */
    void insertOutbox(OutboxWrite outbox);

    /** 事件源写模型。 */
    record SourceWrite(String tenantId, String sourceId, String sourceUri, String sourceUriHash,
            String allowedTypes, String schemaVersions, long maxLatenessSeconds, boolean enabled,
            Instant createdAt) { }

    /** 事件源读模型。 */
    record SourceRecord(String sourceUri, String allowedTypes, String schemaVersions,
            long maxLatenessSeconds, boolean enabled) { }

    /** 回执写模型。 */
    record ReceiptWrite(String tenantId, String receiptId, String sourceId, String eventId,
            String eventType, String businessKey, Long aggregateVersion, String status, String reasonCode,
            String payloadHash, String eventJson, Instant occurredAt, Instant ingestedAt) { }

    /** 已存储的幂等回执。 */
    record StoredReceipt(String receiptId, String status, String reasonCode, Instant ingestedAt,
            String payloadHash) { }

    /** 隔离事件写模型。 */
    record QuarantineWrite(String tenantId, String quarantineId, String receiptId, String reasonCode,
            String eventJson, String state, Instant createdAt) { }

    /** 加锁读取的隔离事件。 */
    record QuarantineRecord(String receiptId, String eventJson, String state) { }

    /** 隔离列表项。 */
    record QuarantineItem(String quarantineId, String receiptId, String reasonCode, String state,
            Instant createdAt, Instant replayedAt) { }

    /** outbox 写模型。 */
    record OutboxWrite(String tenantId, String outboxId, String receiptId, String eventType,
            String destinationTopic, String partitionKey, long streamSequence, String payload,
            Instant createdAt, int depthBucketId) { }
}
