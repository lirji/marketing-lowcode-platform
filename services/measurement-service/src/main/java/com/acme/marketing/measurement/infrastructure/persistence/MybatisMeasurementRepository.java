package com.acme.marketing.measurement.infrastructure.persistence;

import com.acme.marketing.measurement.application.MeasurementRepository;
import com.acme.marketing.measurement.infrastructure.persistence.mapper.MeasurementMapper;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

/** 基于 MyBatis 的度量业务仓储适配器。 */
@Repository
public class MybatisMeasurementRepository implements MeasurementRepository {
    private final MeasurementMapper mapper;

    public MybatisMeasurementRepository(MeasurementMapper mapper) {
        this.mapper = mapper;
    }

    @Override public void saveExperiment(ExperimentWrite write) { requireOne(mapper.insertExperiment(write), "保存实验"); }
    @Override public boolean trySaveAssignment(AssignmentWrite write) { return tryInsert(() -> mapper.insertAssignment(write)); }
    @Override public List<AssignmentRow> findAssignment(String tenantId, String experimentId, String version,
            String unit) { return mapper.selectAssignment(tenantId, experimentId, version, unit); }
    @Override public Optional<String> findCorrectionRootForUpdate(String tenantId, String eventId) {
        return Optional.ofNullable(mapper.selectCorrectionRootForUpdate(tenantId, eventId));
    }
    @Override public Optional<CorrectionIdentity> findCorrectionIdentityForUpdate(String tenantId, String eventId) {
        return Optional.ofNullable(mapper.selectCorrectionIdentityForUpdate(tenantId, eventId));
    }
    @Override public List<String> findActiveCorrectionEventsForUpdate(String tenantId, String rootId) {
        return mapper.selectActiveCorrectionEventsForUpdate(tenantId, rootId);
    }
    @Override public int countCorrectionChain(String tenantId, String rootId) {
        return mapper.countCorrectionChain(tenantId, rootId);
    }
    @Override public int markFactCorrected(String tenantId, String eventId) {
        return mapper.updateFactCorrected(tenantId, eventId);
    }
    @Override public boolean trySaveFact(FactWrite write) { return tryInsert(() -> mapper.insertFact(write)); }
    @Override public Optional<StoredFactReceipt> findStoredFactReceipt(String tenantId, String eventId) {
        return Optional.ofNullable(mapper.selectStoredFactReceipt(tenantId, eventId));
    }
    @Override public Optional<ProjectionContributionRow> findProjectionContribution(String tenantId, String eventId) {
        return Optional.ofNullable(mapper.selectProjectionContribution(tenantId, eventId));
    }
    @Override public boolean tryCreateWatermarkConfig(WatermarkConfigWrite write) {
        return tryInsert(() -> mapper.insertWatermarkConfig(write));
    }
    @Override public Optional<Integer> findWatermarkPartitionCountForUpdate(String tenantId, String name) {
        return Optional.ofNullable(mapper.selectWatermarkPartitionCountForUpdate(tenantId, name));
    }
    @Override public Optional<Integer> findWatermarkPartitionCount(String tenantId, String name) {
        return Optional.ofNullable(mapper.selectWatermarkPartitionCount(tenantId, name));
    }
    @Override public Optional<PartitionWatermark> findPartitionWatermarkForUpdate(String tenantId, String name,
            int partitionId) {
        return Optional.ofNullable(mapper.selectPartitionWatermarkForUpdate(tenantId, name, partitionId));
    }
    @Override public void savePartitionWatermark(PartitionWatermarkWrite write) {
        requireOne(mapper.insertPartitionWatermark(write), "保存投影分区水位");
    }
    @Override public void updatePartitionWatermark(PartitionWatermarkWrite write) {
        requireOne(mapper.updatePartitionWatermark(write), "推进投影分区水位");
    }
    @Override public WatermarkAggregate aggregateWatermark(String tenantId, String name) {
        return mapper.selectWatermarkAggregate(tenantId, name);
    }
    @Override public Optional<ExperimentRow> findExperiment(String tenantId, String experimentId, String version) {
        return Optional.ofNullable(mapper.selectExperiment(tenantId, experimentId, version));
    }
    @Override public boolean tryClaimLayer(LayerAssignmentWrite write) {
        return tryInsert(() -> mapper.insertLayerAssignment(write));
    }
    @Override public Optional<String> findLayerOwnerForUpdate(String tenantId, String layer, String unit) {
        return Optional.ofNullable(mapper.selectLayerOwnerForUpdate(tenantId, layer, unit));
    }
    @Override public Optional<FactRow> findActiveFact(String tenantId, String eventId) {
        return Optional.ofNullable(mapper.selectActiveFact(tenantId, eventId));
    }
    @Override public List<FactRow> findAttributionTouches(String tenantId, String subjectHash, String from, String to) {
        return mapper.selectAttributionTouches(tenantId, subjectHash, from, to);
    }
    @Override public void ensureAttributionLock(String tenantId, String updatedAt) {
        // 已存在时自赋值可能返回 0；随后锁定读取才是成功与否的权威判断。
        mapper.upsertAttributionLock(tenantId, updatedAt);
    }
    @Override public Optional<String> lockAttribution(String tenantId) {
        return Optional.ofNullable(mapper.selectAttributionLockForUpdate(tenantId));
    }
    @Override public List<String> findActiveConversionIds(String tenantId) {
        return mapper.selectActiveConversionIds(tenantId);
    }
    @Override public void deleteAttributionCredits(String tenantId) { mapper.deleteAttributionCredits(tenantId); }
    @Override public void saveAttributionCredit(AttributionCreditWrite write) {
        requireOne(mapper.insertAttributionCredit(write), "保存归因信用");
    }
    @Override public void updateAttributionLock(String tenantId, String updatedAt) {
        requireOne(mapper.updateAttributionLock(tenantId, updatedAt), "更新归因重算锁");
    }
    @Override public List<VariantCount> countExposureByVariant(String tenantId, String experimentId,
            String version) { return mapper.selectExposureCounts(tenantId, experimentId, version); }
    @Override public void pauseExperimentForSrm(String tenantId, String experimentId, String version) {
        requireOne(mapper.updateExperimentPausedForSrm(tenantId, experimentId, version), "暂停样本失衡实验");
    }
    @Override public void saveTrace(TraceWrite write) { requireOne(mapper.insertTrace(write), "保存决策轨迹"); }
    @Override public Optional<TraceRow> findTraceByRequest(String tenantId, String requestId, String now) {
        return Optional.ofNullable(mapper.selectTraceByRequest(tenantId, requestId, now));
    }
    @Override public Optional<TraceRow> findTraceByOrder(String tenantId, String orderId, String now) {
        return Optional.ofNullable(mapper.selectTraceByOrder(tenantId, orderId, now));
    }
    @Override public boolean tryBeginCommand(CommandWrite write) { return tryInsert(() -> mapper.insertCommand(write)); }
    @Override public Optional<StoredCommand> findCommandForUpdate(String tenantId, String commandId) {
        return Optional.ofNullable(mapper.selectCommandForUpdate(tenantId, commandId));
    }
    @Override public void deleteCommand(String tenantId, String commandId) {
        requireOne(mapper.deleteCommand(tenantId, commandId), "删除过期度量命令");
    }
    @Override public void completeCommand(String tenantId, String commandId, String responseJson) {
        requireOne(mapper.updateCommandCompleted(tenantId, commandId, responseJson), "完成度量命令");
    }

    /** 将数据库唯一键冲突转换成端口的竞争失败语义。 */
    private static boolean tryInsert(InsertAction action) {
        try {
            return action.execute() == 1;
        } catch (DuplicateKeyException duplicate) {
            return false;
        }
    }

    private static void requireOne(int affected, String operation) {
        if (affected != 1) throw new IllegalStateException(operation + "的受影响行数不是 1");
    }

    @FunctionalInterface
    private interface InsertAction { int execute(); }
}
