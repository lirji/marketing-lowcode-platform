package com.acme.marketing.eventgateway.infrastructure.persistence;

import static com.acme.marketing.platform.time.SqlTime.format;

import com.acme.marketing.eventgateway.application.EventIngestionRepository;
import com.acme.marketing.eventgateway.infrastructure.persistence.mapper.EventGatewayMapper;
import com.acme.marketing.eventgateway.infrastructure.persistence.mapper.EventGatewayMapper.AggregateVersionWrite;
import com.acme.marketing.eventgateway.infrastructure.persistence.mapper.EventGatewayMapper.StreamPositionWrite;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

/**
 * 基于 MyBatis 的事件接入持久化适配器。
 *
 * <p>适配器转换应用层读写模型并收敛唯一键竞争，SQL 本身统一位于 Mapper XML。
 */
@Repository
public class MybatisEventIngestionRepository implements EventIngestionRepository {
    private final EventGatewayMapper mapper;

    public MybatisEventIngestionRepository(EventGatewayMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void insertSource(SourceWrite source) {
        requireOne(mapper.insertSource(new EventGatewayMapper.SourceWrite(source.tenantId(), source.sourceId(),
                source.sourceUri(), source.sourceUriHash(), source.allowedTypes(), source.schemaVersions(),
                source.maxLatenessSeconds(), source.enabled(), format(source.createdAt()))), "保存事件源");
    }

    @Override
    public Optional<StoredReceipt> findReceipt(String tenantId, String sourceId, String eventId) {
        EventGatewayMapper.ReceiptRow row = mapper.selectReceipt(tenantId, sourceId, eventId);
        return row == null ? Optional.empty() : Optional.of(new StoredReceipt(row.receiptId(), row.statusName(),
                row.reasonCode(), Instant.parse(row.ingestedAt()), row.payloadHash()));
    }

    @Override
    public boolean insertReceipt(ReceiptWrite receipt) {
        try {
            requireOne(mapper.insertReceipt(new EventGatewayMapper.ReceiptWrite(receipt.tenantId(),
                    receipt.receiptId(), receipt.sourceId(), receipt.eventId(), receipt.eventType(),
                    receipt.businessKey(), receipt.aggregateVersion(), receipt.status(), receipt.reasonCode(),
                    receipt.payloadHash(), receipt.eventJson(), format(receipt.occurredAt()),
                    format(receipt.ingestedAt()))), "保存事件回执");
            return true;
        } catch (DuplicateKeyException race) {
            return false;
        }
    }

    @Override
    public void insertQuarantine(QuarantineWrite quarantine) {
        requireOne(mapper.insertQuarantine(new EventGatewayMapper.QuarantineWrite(quarantine.tenantId(),
                quarantine.quarantineId(), quarantine.receiptId(), quarantine.reasonCode(),
                quarantine.eventJson(), quarantine.state(), format(quarantine.createdAt()))), "保存隔离事件");
    }

    @Override
    public Optional<QuarantineRecord> lockQuarantine(String tenantId, String quarantineId) {
        EventGatewayMapper.QuarantineRow row = mapper.selectQuarantineForUpdate(tenantId, quarantineId);
        return row == null ? Optional.empty()
                : Optional.of(new QuarantineRecord(row.receiptId(), row.eventJson(), row.stateName()));
    }

    @Override
    public void markQuarantineReplayed(String tenantId, String quarantineId, Instant replayedAt) {
        requireOne(mapper.markQuarantineReplayed(tenantId, quarantineId, format(replayedAt)), "更新隔离事件状态");
    }

    @Override
    public void markReceiptReplayed(String tenantId, String receiptId) {
        requireOne(mapper.markReceiptReplayed(tenantId, receiptId), "更新事件回执状态");
    }

    @Override
    public List<QuarantineItem> findQuarantine(String tenantId) {
        return mapper.selectQuarantine(tenantId).stream()
                .map(row -> new QuarantineItem(row.quarantineId(), row.receiptId(), row.reasonCode(),
                        row.stateName(), Instant.parse(row.createdAt()),
                        row.replayedAt() == null ? null : Instant.parse(row.replayedAt())))
                .toList();
    }

    @Override
    public Optional<SourceRecord> findSource(String tenantId, String sourceId) {
        EventGatewayMapper.SourceRow row = mapper.selectSource(tenantId, sourceId);
        return row == null ? Optional.empty() : Optional.of(new SourceRecord(row.sourceUri(), row.allowedTypes(),
                row.schemaVersions(), row.maxLatenessSeconds(), row.enabledValue()));
    }

    @Override
    public Optional<Long> lockLastAggregateVersion(String tenantId, String sourceId, String businessKey) {
        return Optional.ofNullable(mapper.selectLastAggregateVersionForUpdate(tenantId, sourceId, businessKey));
    }

    @Override
    public boolean updateAggregateVersion(String tenantId, String sourceId, String businessKey,
            long aggregateVersion, Instant updatedAt) {
        return mapper.updateAggregateVersion(aggregateVersion(tenantId, sourceId, businessKey,
                aggregateVersion, updatedAt)) == 1;
    }

    @Override
    public void insertAggregateVersion(String tenantId, String sourceId, String businessKey,
            long aggregateVersion, Instant updatedAt) {
        requireOne(mapper.insertAggregateVersion(aggregateVersion(tenantId, sourceId, businessKey,
                aggregateVersion, updatedAt)), "创建聚合版本游标");
    }

    @Override
    public boolean incrementStreamSequence(String tenantId, String topic, String partitionKey, Instant updatedAt) {
        return mapper.incrementStreamSequence(streamPosition(tenantId, topic, partitionKey, 0, updatedAt)) == 1;
    }

    @Override
    public boolean insertInitialStreamSequence(String tenantId, String topic, String partitionKey,
            Instant updatedAt) {
        try {
            requireOne(mapper.insertStreamSequence(streamPosition(tenantId, topic, partitionKey, 1, updatedAt)),
                    "创建事件流游标");
            return true;
        } catch (DuplicateKeyException race) {
            return false;
        }
    }

    @Override
    public Optional<Long> findStreamSequence(String tenantId, String topic, String partitionKey) {
        return Optional.ofNullable(mapper.selectStreamSequence(tenantId, topic, partitionKey));
    }

    @Override
    public void insertOutbox(OutboxWrite outbox) {
        requireOne(mapper.insertOutbox(new EventGatewayMapper.OutboxWrite(outbox.tenantId(), outbox.outboxId(),
                outbox.receiptId(), outbox.eventType(), outbox.destinationTopic(), outbox.partitionKey(),
                outbox.streamSequence(), outbox.payload(), format(outbox.createdAt()), format(outbox.createdAt()),
                outbox.depthBucketId())), "保存事件 outbox");
    }

    private static AggregateVersionWrite aggregateVersion(String tenantId, String sourceId, String businessKey,
            long aggregateVersion, Instant updatedAt) {
        return new AggregateVersionWrite(tenantId, sourceId, businessKey, aggregateVersion, format(updatedAt));
    }

    private static StreamPositionWrite streamPosition(String tenantId, String topic, String partitionKey,
            long sequence, Instant updatedAt) {
        return new StreamPositionWrite(tenantId, topic, partitionKey, sequence, format(updatedAt));
    }

    private static void requireOne(int affected, String operation) {
        if (affected != 1) throw new IllegalStateException(operation + "的受影响行数不正确");
    }
}
