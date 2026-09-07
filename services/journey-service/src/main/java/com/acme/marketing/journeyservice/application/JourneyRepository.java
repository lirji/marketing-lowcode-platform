package com.acme.marketing.journeyservice.application;

import java.util.List;
import java.util.Optional;

/**
 * 旅程定义、实例、运行时发布与输出投影的持久化端口。
 *
 * <p>端口使用数据库无关的读写模型，使应用层只表达事务与领域语义。
 */
public interface JourneyRepository {

    void saveDefinition(DefinitionWrite write);
    boolean trySaveDefinition(DefinitionWrite write);
    Optional<String> findPlanJson(String tenantId, String journeyId, long version);
    Optional<String> findActivePlanJson(String tenantId, String journeyId, long version, boolean lock);
    Optional<String> findDefinitionStateForUpdate(String tenantId, String journeyId, long version);
    void markDefinitionMigrating(String tenantId, String journeyId, long version);
    void markDefinitionMigrated(String tenantId, String journeyId, long version);

    Optional<EnrollmentSummaryRow> findDuplicateEnrollment(String tenantId, String journeyId, long version,
            String subjectToken, String triggerEventId);
    boolean trySaveEnrollment(EnrollmentWrite write);
    void updateEnrollmentState(EnrollmentStateWrite write);
    Optional<String> findEnrollmentSnapshot(String tenantId, String enrollmentId, boolean lock);
    List<EnrollmentListRow> findEnrollments(String tenantId, String statusName, String journeyId, int limit);
    List<String> findMigrationSnapshots(String tenantId, String journeyId, long fromVersion,
            String afterEnrollmentId, int limit, boolean lock);
    void saveMigration(MigrationWrite write);
    void updateEnrollmentMigration(EnrollmentMigrationWrite write);
    int countActiveEnrollments(String tenantId, String journeyId, long version);

    void saveEffectIntent(EffectWrite write);
    Optional<EffectRow> findEffect(String tenantId, String commandId);
    int updateTimer(TimerWrite write);
    void saveTimer(TimerWrite write);

    boolean trySaveGeneration(GenerationWrite write);
    Optional<GenerationRow> findGeneration(String tenantId, String environment, String cell, String namespace,
            long generation);
    boolean tryCreateRuntimeSlot(RuntimeSlotWrite write);
    Optional<RuntimeSlotRow> findRuntimeSlot(String tenantId, String environment, String cell, String namespace,
            boolean lock);
    void saveActivation(ActivationWrite write);
    void updateRuntimeSlot(RuntimeSlotActivationWrite write);

    Optional<KillSwitchRow> findKillSwitchForUpdate(String tenantId, String namespace);
    boolean trySaveKillSwitch(KillSwitchWrite write);
    void updateKillSwitch(KillSwitchWrite write);
    List<PersistedKillSwitch> findKillSwitches(String namespace);

    Optional<OutputReceiptRow> findOutputReceipt(String topic, int partition, long offset);
    boolean trySaveOutputReceipt(OutputReceiptWrite write);
    void markEnrollmentExpired(String tenantId, String enrollmentId, String updatedAt);
    void markEnrollmentFailed(String tenantId, String enrollmentId, String updatedAt);
    Optional<ProjectedEnrollmentRow> findProjectedEnrollmentForUpdate(String tenantId, String enrollmentId);
    int countDefinition(String tenantId, String journeyId, long version);
    void saveProjectedEnrollment(ProjectedEnrollmentWrite write);
    void updateProjectedEnrollment(ProjectedEnrollmentUpdate write);
    Optional<EnrollmentIdentityRow> findEnrollmentIdentity(String tenantId, String enrollmentId);
    void saveDispatchOutbox(DispatchOutboxWrite write);
    int incrementDispatchPosition(String tenantId, String enrollmentId, String updatedAt);
    boolean tryCreateDispatchPosition(String tenantId, String enrollmentId, String updatedAt);
    Optional<Long> findDispatchSequence(String tenantId, String enrollmentId);

    record DefinitionWrite(String tenantId, String journeyId, long versionNo, String planJson, String stateName,
            String createdBy, String createdAt) { }
    record EnrollmentSummaryRow(String enrollmentId, String snapshotJson, String createdAt) { }
    record EnrollmentWrite(String tenantId, String enrollmentId, String journeyId, long journeyVersion,
            String subjectToken, String triggerEventId, String statusName, String currentNodeId, String snapshotJson,
            String projectionTopic, Integer projectionPartition, Long projectionOffset, String createdAt,
            String updatedAt) { }
    record EnrollmentStateWrite(String tenantId, String enrollmentId, String statusName, String currentNodeId,
            String snapshotJson, String updatedAt) { }
    record EnrollmentListRow(String snapshotJson, String updatedAt) { }
    record MigrationWrite(String tenantId, String migrationId, String enrollmentId, long fromVersion,
            long toVersion, String previousSnapshotJson, String stateName, String createdBy, String createdAt) { }
    record EnrollmentMigrationWrite(String tenantId, String enrollmentId, long journeyVersion,
            String currentNodeId, String snapshotJson, String updatedAt) { }
    record EffectWrite(String tenantId, String commandId, String enrollmentId, String nodeId, String effectType,
            String payloadJson, String stateName, String createdAt) { }
    record EffectRow(String enrollmentId, String nodeId, String effectType, String payloadJson) { }
    record TimerWrite(String tenantId, String timerKey, String enrollmentId, String fireAt, String stateName,
            String createdAt) { }
    record GenerationWrite(String tenantId, String environmentName, String cellId, String namespaceName,
            long generationNo, String releaseKeyId, String manifestJson, String artifactId, byte[] artifactPayload,
            String manifestSignature, String warmedAt) {
        public GenerationWrite { artifactPayload = artifactPayload.clone(); }
        @Override public byte[] artifactPayload() { return artifactPayload.clone(); }
    }
    record GenerationRow(String releaseKeyId, String manifestJson, String artifactId, byte[] artifactPayload) {
        public GenerationRow { artifactPayload = artifactPayload.clone(); }
        @Override public byte[] artifactPayload() { return artifactPayload.clone(); }
    }
    record RuntimeSlotWrite(String tenantId, String environmentName, String cellId, String namespaceName,
            long desiredGeneration, long activationSequence, String directiveSignature, String directiveJson,
            String updatedAt) { }
    record RuntimeSlotRow(long desiredGeneration, long activationSequence, String directiveSignature) { }
    record ActivationWrite(String tenantId, String environmentName, String cellId, String namespaceName,
            long activationSequence, long generationNo, String directiveSignature, String directiveJson,
            String activatedAt) { }
    record RuntimeSlotActivationWrite(String tenantId, String environmentName, String cellId, String namespaceName,
            long desiredGeneration, long activationSequence, String directiveSignature, String directiveJson,
            String updatedAt) { }
    record KillSwitchRow(long switchSequence, String directiveSignature, boolean enabledValue, String reasonText) { }
    record KillSwitchWrite(String tenantId, String namespaceName, long switchSequence, boolean enabledValue,
            String reasonText, String directiveSignature, String directiveJson, String updatedAt) { }
    record PersistedKillSwitch(String tenantId, String directiveJson) { }
    record OutputReceiptRow(String payloadHash, String eventType) { }
    record OutputReceiptWrite(String sourceTopic, int sourcePartition, long sourceOffset, String tenantId,
            String enrollmentId, String eventType, String payloadHash, String consumedAt) { }
    record ProjectedEnrollmentRow(String journeyId, long journeyVersion, String subjectToken, String projectionTopic,
            int projectionPartition, long projectionOffset) { }
    record ProjectedEnrollmentWrite(String tenantId, String enrollmentId, String journeyId, long journeyVersion,
            String subjectToken, String triggerEventId, String statusName, String currentNodeId, String snapshotJson,
            String projectionTopic, int projectionPartition, long projectionOffset, String createdAt,
            String updatedAt) { }
    record ProjectedEnrollmentUpdate(String tenantId, String enrollmentId, String statusName, String currentNodeId,
            String snapshotJson, String projectionTopic, int projectionPartition, long projectionOffset,
            String updatedAt) { }
    record EnrollmentIdentityRow(String journeyId, long journeyVersion, String subjectToken) { }
    record DispatchOutboxWrite(String tenantId, String outboxId, String commandId, String enrollmentId,
            String destinationTopic, String partitionKey, long streamSequence, String payloadJson,
            String nextAttemptAt, String createdAt) { }
}
