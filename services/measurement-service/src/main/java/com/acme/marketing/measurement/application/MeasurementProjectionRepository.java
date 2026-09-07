package com.acme.marketing.measurement.application;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/** 看板投影增量的持久化端口。 */
public interface MeasurementProjectionRepository {

    /** 查询增量标识对应的已存记录。 */
    Optional<StoredDelta> findDelta(String tenantId, String deltaId);

    /** 查询 Kafka 坐标已经绑定的负载摘要。 */
    Optional<String> findPayloadHashBySource(String topic, int partition, long offset);

    /** 尝试保存投影增量；任一唯一键冲突时返回 {@code false}。 */
    boolean trySaveDelta(DeltaWrite write);

    /** 汇总事实类型计数。 */
    List<FactCount> sumCounts(String tenantId, String from, String to);

    /** 汇总收入和成本。 */
    Amounts sumAmounts(String tenantId, String from, String to);

    /** 按小时或自然日汇总时序数据。 */
    List<SeriesBucket> sumSeries(String tenantId, String from, String to, boolean hourly);

    record StoredDelta(String payloadHash, String topic, Integer partition, Long offset) {
        /** 是否来自具有完整坐标的 Kafka 记录。 */
        public boolean kafkaRecord() { return partition != null; }
    }

    record DeltaWrite(String tenantId, String deltaId, String rootEventId, String sourceEventId, long revisionNo,
            String operationName, String factType, String businessKey, String campaignId, String experimentId,
            String variantId, String subjectHash, long countDelta, long revenueDeltaMinor, long costDeltaMinor,
            String occurredAt, String ingestedAt, String payloadHash, String sourceTopic, Integer sourcePartition,
            Long sourceOffset, String projectedAt) { }

    record FactCount(String factType, BigDecimal countValue) { }

    record Amounts(BigDecimal revenue, BigDecimal cost) { }

    record SeriesBucket(String bucketValue, BigDecimal revenue, BigDecimal cost, BigDecimal conversions) { }
}
