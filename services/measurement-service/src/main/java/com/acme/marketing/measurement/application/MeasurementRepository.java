package com.acme.marketing.measurement.application;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * 度量上下文的业务持久化端口。
 *
 * <p>应用服务通过稳定的业务读写模型访问数据，不直接依赖 JDBC、MyBatis 或 SQL。
 */
public interface MeasurementRepository {

    /** 保存实验定义。 */
    void saveExperiment(ExperimentWrite write);

    /** 尝试保存实验分组；并发下已存在时返回 {@code false}。 */
    boolean trySaveAssignment(AssignmentWrite write);

    /** 查询已经固化的实验分组。 */
    List<AssignmentRow> findAssignment(String tenantId, String experimentId, String version, String unit);

    /** 查询并锁定冲正目标所属根事件。 */
    Optional<String> findCorrectionRootForUpdate(String tenantId, String eventId);

    /** 查询并锁定根事件的不可变身份。 */
    Optional<CorrectionIdentity> findCorrectionIdentityForUpdate(String tenantId, String eventId);

    /** 查询并锁定冲正链当前仍生效的事件。 */
    List<String> findActiveCorrectionEventsForUpdate(String tenantId, String correctionRootId);

    /** 统计冲正链长度。 */
    int countCorrectionChain(String tenantId, String correctionRootId);

    /** 将当前事实标为已冲正，返回受影响行数。 */
    int markFactCorrected(String tenantId, String eventId);

    /** 尝试保存事实；事件标识已存在时返回 {@code false}。 */
    boolean trySaveFact(FactWrite write);

    /** 查询事实接收回执所需字段。 */
    Optional<StoredFactReceipt> findStoredFactReceipt(String tenantId, String eventId);

    /** 查询构造冲正投影所需的原事实贡献。 */
    Optional<ProjectionContributionRow> findProjectionContribution(String tenantId, String eventId);

    /** 尝试创建投影水位配置；配置已存在时返回 {@code false}。 */
    boolean tryCreateWatermarkConfig(WatermarkConfigWrite write);

    /** 锁定并读取投影分区数。 */
    Optional<Integer> findWatermarkPartitionCountForUpdate(String tenantId, String projectionName);

    /** 普通读取投影分区数。 */
    Optional<Integer> findWatermarkPartitionCount(String tenantId, String projectionName);

    /** 锁定并读取单分区水位。 */
    Optional<PartitionWatermark> findPartitionWatermarkForUpdate(String tenantId, String projectionName,
            int partitionId);

    /** 新增单分区水位。 */
    void savePartitionWatermark(PartitionWatermarkWrite write);

    /** 单调推进单分区水位。 */
    void updatePartitionWatermark(PartitionWatermarkWrite write);

    /** 汇总投影分区水位。 */
    WatermarkAggregate aggregateWatermark(String tenantId, String projectionName);

    /** 查询单个实验定义。 */
    Optional<ExperimentRow> findExperiment(String tenantId, String experimentId, String version);

    /** 尝试占用实验层；该随机单元已经被占用时返回 {@code false}。 */
    boolean tryClaimLayer(LayerAssignmentWrite write);

    /** 锁定并读取实验层的当前归属。 */
    Optional<String> findLayerOwnerForUpdate(String tenantId, String layer, String unit);

    /** 查询当前生效事实。 */
    Optional<FactRow> findActiveFact(String tenantId, String eventId);

    /** 查询归因窗口内的有效触点。 */
    List<FactRow> findAttributionTouches(String tenantId, String subjectHash, String from, String to);

    /** 创建租户归因重算锁（已存在时保持原值）。 */
    void ensureAttributionLock(String tenantId, String updatedAt);

    /** 锁定租户归因重算串行化记录。 */
    Optional<String> lockAttribution(String tenantId);

    /** 查询全部有效转化事件。 */
    List<String> findActiveConversionIds(String tenantId);

    /** 清除旧的归因结果。 */
    void deleteAttributionCredits(String tenantId);

    /** 保存一条归因信用。 */
    void saveAttributionCredit(AttributionCreditWrite write);

    /** 更新归因重算完成时间。 */
    void updateAttributionLock(String tenantId, String updatedAt);

    /** 按变体统计有效曝光量。 */
    List<VariantCount> countExposureByVariant(String tenantId, String experimentId, String version);

    /** 因样本比例失衡暂停实验。 */
    void pauseExperimentForSrm(String tenantId, String experimentId, String version);

    /** 保存决策轨迹。 */
    void saveTrace(TraceWrite write);

    /** 按请求标识查询仍可见的决策轨迹。 */
    Optional<TraceRow> findTraceByRequest(String tenantId, String requestId, String now);

    /** 按订单标识查询仍可见的最新决策轨迹。 */
    Optional<TraceRow> findTraceByOrder(String tenantId, String orderId, String now);

    /** 尝试创建命令幂等记录；记录已存在时返回 {@code false}。 */
    boolean tryBeginCommand(CommandWrite write);

    /** 锁定并读取命令幂等记录。 */
    Optional<StoredCommand> findCommandForUpdate(String tenantId, String commandId);

    /** 删除已过期命令。 */
    void deleteCommand(String tenantId, String commandId);

    /** 保存命令响应。 */
    void completeCommand(String tenantId, String commandId, String responseJson);

    record ExperimentWrite(String tenantId, String experimentId, String versionNo, String layerName,
            String definitionJson, String stateName, String createdAt) { }

    record AssignmentWrite(String tenantId, String experimentId, String versionNo, String layerName,
            String randomizationUnit, String variantId, boolean holdoutValue, int bucketNo, String assignedAt) { }

    record AssignmentRow(String variantId, boolean holdoutValue, int bucketNo, String assignedAt) { }

    record CorrectionIdentity(String type, String businessKey, String subjectHash) { }

    record FactWrite(String tenantId, String eventId, String factType, String businessKey, String subjectHash,
            String occurredAt, String ingestedAt, String schemaVersion, String payloadHash, String attributesJson,
            String correctionOf, String correctionRootId, boolean correctedValue, long revenueMinor, long costMinor,
            String experimentId, String experimentVersion, String variantId) { }

    record StoredFactReceipt(String ingestedAt, String payloadHash) { }

    record ProjectionContributionRow(String factType, String businessKey, String subjectHash, String occurredAt,
            String ingestedAt, long revenueMinor, long costMinor, String attributesJson, String experimentId,
            String variantId) { }

    record WatermarkConfigWrite(String tenantId, String projectionName, int partitionCount, String updatedAt) { }

    record PartitionWatermark(long sourceOffset, long completeThroughEpochMillis) { }

    record PartitionWatermarkWrite(String tenantId, String projectionName, int partitionId, long sourceOffset,
            long completeThroughEpochMillis, String updatedAt) { }

    record WatermarkAggregate(int partitionCount, long minimumEpochMillis) { }

    record ExperimentRow(String definitionJson, String stateName, String createdAt) { }

    record LayerAssignmentWrite(String tenantId, String layerName, String randomizationUnit, String experimentId,
            String assignedAt) { }

    record FactRow(String eventId, String factType, String businessKey, String subjectHash, String occurredAt,
            long revenueMinor, long costMinor) { }

    record AttributionCreditWrite(String tenantId, String conversionEventId, String policyName,
            String touchEventId, BigDecimal creditValue, long revenueMinor, String calculatedAt) { }

    record VariantCount(String variantId, long countValue) { }

    record TraceWrite(String tenantId, String traceId, String requestId, String orderId, String subjectHash,
            long generationNo, long durationMicros, String candidatesJson, String pricingJson, String termsVersion,
            String expiresAt, boolean legalHold, String createdAt) { }

    record TraceRow(String traceId, String requestId, String orderId, String subjectHash, long generationNo,
            long durationMicros, String candidatesJson, String pricingJson, String termsVersion, String expiresAt,
            boolean legalHold, String createdAt) { }

    record CommandWrite(String tenantId, String commandId, String payloadHash, String stateName,
            String responseJson, String createdAt, String expiresAt) { }

    record StoredCommand(String payloadHash, String stateName, String responseJson, String expiresAt) { }
}
