package com.acme.marketing.journeyservice.infrastructure.persistence.mapper;

import com.acme.marketing.journeyservice.application.JourneyDispatchRepository;
import com.acme.marketing.journeyservice.application.JourneyRepository;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 旅程库 MyBatis Mapper；SQL 统一位于同名 XML。 */
@Mapper
public interface JourneyMapper {
    int insertDefinition(JourneyRepository.DefinitionWrite write);
    String selectPlanJson(@Param("tenantId") String tenantId, @Param("journeyId") String journeyId,
            @Param("version") long version);
    String selectActivePlanJson(@Param("tenantId") String tenantId, @Param("journeyId") String journeyId,
            @Param("version") long version, @Param("lock") boolean lock);
    String selectDefinitionStateForUpdate(@Param("tenantId") String tenantId,
            @Param("journeyId") String journeyId, @Param("version") long version);
    int updateDefinitionMigrating(@Param("tenantId") String tenantId, @Param("journeyId") String journeyId,
            @Param("version") long version);
    int updateDefinitionMigrated(@Param("tenantId") String tenantId, @Param("journeyId") String journeyId,
            @Param("version") long version);
    JourneyRepository.EnrollmentSummaryRow selectDuplicateEnrollment(@Param("tenantId") String tenantId,
            @Param("journeyId") String journeyId, @Param("version") long version,
            @Param("subjectToken") String subjectToken, @Param("triggerEventId") String triggerEventId);
    int insertEnrollment(JourneyRepository.EnrollmentWrite write);
    int updateEnrollmentState(JourneyRepository.EnrollmentStateWrite write);
    String selectEnrollmentSnapshot(@Param("tenantId") String tenantId,
            @Param("enrollmentId") String enrollmentId, @Param("lock") boolean lock);
    List<JourneyRepository.EnrollmentListRow> selectEnrollments(@Param("tenantId") String tenantId,
            @Param("statusName") String statusName, @Param("journeyId") String journeyId,
            @Param("limit") int limit);
    List<String> selectMigrationSnapshots(@Param("tenantId") String tenantId,
            @Param("journeyId") String journeyId, @Param("fromVersion") long fromVersion,
            @Param("afterEnrollmentId") String afterEnrollmentId, @Param("limit") int limit,
            @Param("lock") boolean lock);
    int insertMigration(JourneyRepository.MigrationWrite write);
    int updateEnrollmentMigration(JourneyRepository.EnrollmentMigrationWrite write);
    int countActiveEnrollments(@Param("tenantId") String tenantId, @Param("journeyId") String journeyId,
            @Param("version") long version);
    int insertEffectIntent(JourneyRepository.EffectWrite write);
    JourneyRepository.EffectRow selectEffect(@Param("tenantId") String tenantId,
            @Param("commandId") String commandId);
    int updateTimer(JourneyRepository.TimerWrite write);
    int insertTimer(JourneyRepository.TimerWrite write);
    int insertGeneration(JourneyRepository.GenerationWrite write);
    JourneyRepository.GenerationRow selectGeneration(@Param("tenantId") String tenantId,
            @Param("environment") String environment, @Param("cell") String cell,
            @Param("namespace") String namespace, @Param("generation") long generation);
    int insertRuntimeSlot(JourneyRepository.RuntimeSlotWrite write);
    JourneyRepository.RuntimeSlotRow selectRuntimeSlot(@Param("tenantId") String tenantId,
            @Param("environment") String environment, @Param("cell") String cell,
            @Param("namespace") String namespace, @Param("lock") boolean lock);
    int insertActivation(JourneyRepository.ActivationWrite write);
    int updateRuntimeSlot(JourneyRepository.RuntimeSlotActivationWrite write);
    JourneyRepository.KillSwitchRow selectKillSwitchForUpdate(@Param("tenantId") String tenantId,
            @Param("namespace") String namespace);
    int insertKillSwitch(JourneyRepository.KillSwitchWrite write);
    int updateKillSwitch(JourneyRepository.KillSwitchWrite write);
    List<JourneyRepository.PersistedKillSwitch> selectKillSwitches(@Param("namespace") String namespace);
    JourneyRepository.OutputReceiptRow selectOutputReceipt(@Param("topic") String topic,
            @Param("partition") int partition, @Param("offset") long offset);
    int insertOutputReceipt(JourneyRepository.OutputReceiptWrite write);
    int updateEnrollmentExpired(@Param("tenantId") String tenantId, @Param("enrollmentId") String enrollmentId,
            @Param("updatedAt") String updatedAt);
    int updateEnrollmentFailed(@Param("tenantId") String tenantId, @Param("enrollmentId") String enrollmentId,
            @Param("updatedAt") String updatedAt);
    JourneyRepository.ProjectedEnrollmentRow selectProjectedEnrollmentForUpdate(
            @Param("tenantId") String tenantId, @Param("enrollmentId") String enrollmentId);
    int countDefinition(@Param("tenantId") String tenantId, @Param("journeyId") String journeyId,
            @Param("version") long version);
    int insertProjectedEnrollment(JourneyRepository.ProjectedEnrollmentWrite write);
    int updateProjectedEnrollment(JourneyRepository.ProjectedEnrollmentUpdate write);
    JourneyRepository.EnrollmentIdentityRow selectEnrollmentIdentity(@Param("tenantId") String tenantId,
            @Param("enrollmentId") String enrollmentId);
    int insertDispatchOutbox(JourneyRepository.DispatchOutboxWrite write);
    int incrementDispatchPosition(@Param("tenantId") String tenantId,
            @Param("enrollmentId") String enrollmentId, @Param("updatedAt") String updatedAt);
    int insertDispatchPosition(@Param("tenantId") String tenantId,
            @Param("enrollmentId") String enrollmentId, @Param("updatedAt") String updatedAt);
    Long selectDispatchSequence(@Param("tenantId") String tenantId,
            @Param("enrollmentId") String enrollmentId);
    List<JourneyDispatchRepository.PendingDispatch> selectPendingDispatches(@Param("now") String now,
            @Param("limit") int limit);
    int updateDispatchPublished(@Param("tenantId") String tenantId, @Param("outboxId") String outboxId,
            @Param("attempts") int attempts, @Param("publishedAt") String publishedAt);
    int updateEffectState(@Param("tenantId") String tenantId, @Param("commandId") String commandId,
            @Param("stateName") String stateName);
    int updateDispatchDeadLettered(@Param("tenantId") String tenantId, @Param("outboxId") String outboxId,
            @Param("attempts") int attempts, @Param("deadLetteredAt") String deadLetteredAt,
            @Param("error") String error);
    int updateDispatchRetry(@Param("tenantId") String tenantId, @Param("outboxId") String outboxId,
            @Param("attempts") int attempts, @Param("nextAttemptAt") String nextAttemptAt,
            @Param("error") String error);
}
