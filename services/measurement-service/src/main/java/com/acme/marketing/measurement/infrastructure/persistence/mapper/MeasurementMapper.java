package com.acme.marketing.measurement.infrastructure.persistence.mapper;

import com.acme.marketing.measurement.application.MeasurementProjectionRepository;
import com.acme.marketing.measurement.application.MeasurementRepository;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 度量库 MyBatis Mapper。
 *
 * <p>所有 SQL 都位于 {@code mapper/MeasurementMapper.xml}，接口只描述参数和结果契约。
 */
@Mapper
public interface MeasurementMapper {

    int insertExperiment(MeasurementRepository.ExperimentWrite write);
    int insertAssignment(MeasurementRepository.AssignmentWrite write);
    List<MeasurementRepository.AssignmentRow> selectAssignment(@Param("tenantId") String tenantId,
            @Param("experimentId") String experimentId, @Param("version") String version,
            @Param("unit") String unit);
    String selectCorrectionRootForUpdate(@Param("tenantId") String tenantId, @Param("eventId") String eventId);
    MeasurementRepository.CorrectionIdentity selectCorrectionIdentityForUpdate(
            @Param("tenantId") String tenantId, @Param("eventId") String eventId);
    List<String> selectActiveCorrectionEventsForUpdate(@Param("tenantId") String tenantId,
            @Param("correctionRootId") String correctionRootId);
    int countCorrectionChain(@Param("tenantId") String tenantId,
            @Param("correctionRootId") String correctionRootId);
    int updateFactCorrected(@Param("tenantId") String tenantId, @Param("eventId") String eventId);
    int insertFact(MeasurementRepository.FactWrite write);
    MeasurementRepository.StoredFactReceipt selectStoredFactReceipt(@Param("tenantId") String tenantId,
            @Param("eventId") String eventId);
    MeasurementRepository.ProjectionContributionRow selectProjectionContribution(
            @Param("tenantId") String tenantId, @Param("eventId") String eventId);
    int insertWatermarkConfig(MeasurementRepository.WatermarkConfigWrite write);
    Integer selectWatermarkPartitionCountForUpdate(@Param("tenantId") String tenantId,
            @Param("projectionName") String projectionName);
    Integer selectWatermarkPartitionCount(@Param("tenantId") String tenantId,
            @Param("projectionName") String projectionName);
    MeasurementRepository.PartitionWatermark selectPartitionWatermarkForUpdate(
            @Param("tenantId") String tenantId, @Param("projectionName") String projectionName,
            @Param("partitionId") int partitionId);
    int insertPartitionWatermark(MeasurementRepository.PartitionWatermarkWrite write);
    int updatePartitionWatermark(MeasurementRepository.PartitionWatermarkWrite write);
    MeasurementRepository.WatermarkAggregate selectWatermarkAggregate(@Param("tenantId") String tenantId,
            @Param("projectionName") String projectionName);
    MeasurementRepository.ExperimentRow selectExperiment(@Param("tenantId") String tenantId,
            @Param("experimentId") String experimentId, @Param("version") String version);
    int insertLayerAssignment(MeasurementRepository.LayerAssignmentWrite write);
    String selectLayerOwnerForUpdate(@Param("tenantId") String tenantId, @Param("layer") String layer,
            @Param("unit") String unit);
    MeasurementRepository.FactRow selectActiveFact(@Param("tenantId") String tenantId,
            @Param("eventId") String eventId);
    List<MeasurementRepository.FactRow> selectAttributionTouches(@Param("tenantId") String tenantId,
            @Param("subjectHash") String subjectHash, @Param("from") String from, @Param("to") String to);
    int upsertAttributionLock(@Param("tenantId") String tenantId, @Param("updatedAt") String updatedAt);
    String selectAttributionLockForUpdate(@Param("tenantId") String tenantId);
    List<String> selectActiveConversionIds(@Param("tenantId") String tenantId);
    int deleteAttributionCredits(@Param("tenantId") String tenantId);
    int insertAttributionCredit(MeasurementRepository.AttributionCreditWrite write);
    int updateAttributionLock(@Param("tenantId") String tenantId, @Param("updatedAt") String updatedAt);
    List<MeasurementRepository.VariantCount> selectExposureCounts(@Param("tenantId") String tenantId,
            @Param("experimentId") String experimentId, @Param("version") String version);
    int updateExperimentPausedForSrm(@Param("tenantId") String tenantId,
            @Param("experimentId") String experimentId, @Param("version") String version);
    int insertTrace(MeasurementRepository.TraceWrite write);
    MeasurementRepository.TraceRow selectTraceByRequest(@Param("tenantId") String tenantId,
            @Param("requestId") String requestId, @Param("now") String now);
    MeasurementRepository.TraceRow selectTraceByOrder(@Param("tenantId") String tenantId,
            @Param("orderId") String orderId, @Param("now") String now);
    int insertCommand(MeasurementRepository.CommandWrite write);
    MeasurementRepository.StoredCommand selectCommandForUpdate(@Param("tenantId") String tenantId,
            @Param("commandId") String commandId);
    int deleteCommand(@Param("tenantId") String tenantId, @Param("commandId") String commandId);
    int updateCommandCompleted(@Param("tenantId") String tenantId, @Param("commandId") String commandId,
            @Param("responseJson") String responseJson);

    MeasurementProjectionRepository.StoredDelta selectProjectionDelta(@Param("tenantId") String tenantId,
            @Param("deltaId") String deltaId);
    String selectProjectionPayloadHashBySource(@Param("topic") String topic,
            @Param("partition") int partition, @Param("offset") long offset);
    int insertProjectionDelta(MeasurementProjectionRepository.DeltaWrite write);
    List<MeasurementProjectionRepository.FactCount> selectProjectionCounts(@Param("tenantId") String tenantId,
            @Param("from") String from, @Param("to") String to);
    MeasurementProjectionRepository.Amounts selectProjectionAmounts(@Param("tenantId") String tenantId,
            @Param("from") String from, @Param("to") String to);
    List<MeasurementProjectionRepository.SeriesBucket> selectProjectionSeries(
            @Param("tenantId") String tenantId, @Param("from") String from, @Param("to") String to,
            @Param("hourly") boolean hourly);
}
