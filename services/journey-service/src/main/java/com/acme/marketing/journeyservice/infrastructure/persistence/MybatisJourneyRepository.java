package com.acme.marketing.journeyservice.infrastructure.persistence;

import com.acme.marketing.journeyservice.application.JourneyRepository;
import com.acme.marketing.journeyservice.infrastructure.persistence.mapper.JourneyMapper;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

/** 基于 MyBatis 的旅程持久化适配器。 */
@Repository
public class MybatisJourneyRepository implements JourneyRepository {
    private final JourneyMapper mapper;

    public MybatisJourneyRepository(JourneyMapper mapper) { this.mapper = mapper; }

    @Override public void saveDefinition(DefinitionWrite write) { one(mapper.insertDefinition(write), "保存旅程定义"); }
    @Override public boolean trySaveDefinition(DefinitionWrite write) { return attempt(() -> mapper.insertDefinition(write)); }
    @Override public Optional<String> findPlanJson(String tenantId, String journeyId, long version) {
        return Optional.ofNullable(mapper.selectPlanJson(tenantId, journeyId, version));
    }
    @Override public Optional<String> findActivePlanJson(String tenantId, String journeyId, long version,
            boolean lock) { return Optional.ofNullable(mapper.selectActivePlanJson(tenantId, journeyId, version, lock)); }
    @Override public Optional<String> findDefinitionStateForUpdate(String tenantId, String journeyId, long version) {
        return Optional.ofNullable(mapper.selectDefinitionStateForUpdate(tenantId, journeyId, version));
    }
    @Override public void markDefinitionMigrating(String tenantId, String journeyId, long version) {
        one(mapper.updateDefinitionMigrating(tenantId, journeyId, version), "冻结旅程版本");
    }
    @Override public void markDefinitionMigrated(String tenantId, String journeyId, long version) {
        one(mapper.updateDefinitionMigrated(tenantId, journeyId, version), "完成旅程迁移");
    }
    @Override public Optional<EnrollmentSummaryRow> findDuplicateEnrollment(String tenantId, String journeyId,
            long version, String subjectToken, String triggerEventId) {
        return Optional.ofNullable(mapper.selectDuplicateEnrollment(
                tenantId, journeyId, version, subjectToken, triggerEventId));
    }
    @Override public boolean trySaveEnrollment(EnrollmentWrite write) { return attempt(() -> mapper.insertEnrollment(write)); }
    @Override public void updateEnrollmentState(EnrollmentStateWrite write) {
        one(mapper.updateEnrollmentState(write), "更新旅程实例");
    }
    @Override public Optional<String> findEnrollmentSnapshot(String tenantId, String enrollmentId, boolean lock) {
        return Optional.ofNullable(mapper.selectEnrollmentSnapshot(tenantId, enrollmentId, lock));
    }
    @Override public List<EnrollmentListRow> findEnrollments(String tenantId, String statusName,
            String journeyId, int limit) { return mapper.selectEnrollments(tenantId, statusName, journeyId, limit); }
    @Override public List<String> findMigrationSnapshots(String tenantId, String journeyId, long fromVersion,
            String afterEnrollmentId, int limit, boolean lock) {
        return mapper.selectMigrationSnapshots(tenantId, journeyId, fromVersion, afterEnrollmentId, limit, lock);
    }
    @Override public void saveMigration(MigrationWrite write) { one(mapper.insertMigration(write), "保存旅程迁移记录"); }
    @Override public void updateEnrollmentMigration(EnrollmentMigrationWrite write) {
        one(mapper.updateEnrollmentMigration(write), "迁移旅程实例");
    }
    @Override public int countActiveEnrollments(String tenantId, String journeyId, long version) {
        return mapper.countActiveEnrollments(tenantId, journeyId, version);
    }
    @Override public void saveEffectIntent(EffectWrite write) { one(mapper.insertEffectIntent(write), "保存节点副作用"); }
    @Override public Optional<EffectRow> findEffect(String tenantId, String commandId) {
        return Optional.ofNullable(mapper.selectEffect(tenantId, commandId));
    }
    @Override public int updateTimer(TimerWrite write) { return mapper.updateTimer(write); }
    @Override public void saveTimer(TimerWrite write) { one(mapper.insertTimer(write), "保存旅程定时器"); }
    @Override public boolean trySaveGeneration(GenerationWrite write) { return attempt(() -> mapper.insertGeneration(write)); }
    @Override public Optional<GenerationRow> findGeneration(String tenantId, String environment, String cell,
            String namespace, long generation) {
        return Optional.ofNullable(mapper.selectGeneration(tenantId, environment, cell, namespace, generation));
    }
    @Override public boolean tryCreateRuntimeSlot(RuntimeSlotWrite write) {
        return attempt(() -> mapper.insertRuntimeSlot(write));
    }
    @Override public Optional<RuntimeSlotRow> findRuntimeSlot(String tenantId, String environment, String cell,
            String namespace, boolean lock) {
        return Optional.ofNullable(mapper.selectRuntimeSlot(tenantId, environment, cell, namespace, lock));
    }
    @Override public void saveActivation(ActivationWrite write) { one(mapper.insertActivation(write), "保存激活记录"); }
    @Override public void updateRuntimeSlot(RuntimeSlotActivationWrite write) {
        one(mapper.updateRuntimeSlot(write), "更新运行时槽位");
    }
    @Override public Optional<KillSwitchRow> findKillSwitchForUpdate(String tenantId, String namespace) {
        return Optional.ofNullable(mapper.selectKillSwitchForUpdate(tenantId, namespace));
    }
    @Override public boolean trySaveKillSwitch(KillSwitchWrite write) { return attempt(() -> mapper.insertKillSwitch(write)); }
    @Override public void updateKillSwitch(KillSwitchWrite write) { one(mapper.updateKillSwitch(write), "更新熔断开关"); }
    @Override public List<PersistedKillSwitch> findKillSwitches(String namespace) {
        return mapper.selectKillSwitches(namespace);
    }
    @Override public Optional<OutputReceiptRow> findOutputReceipt(String topic, int partition, long offset) {
        return Optional.ofNullable(mapper.selectOutputReceipt(topic, partition, offset));
    }
    @Override public boolean trySaveOutputReceipt(OutputReceiptWrite write) {
        return attempt(() -> mapper.insertOutputReceipt(write));
    }
    @Override public void markEnrollmentExpired(String tenantId, String enrollmentId, String updatedAt) {
        mapper.updateEnrollmentExpired(tenantId, enrollmentId, updatedAt);
    }
    @Override public void markEnrollmentFailed(String tenantId, String enrollmentId, String updatedAt) {
        mapper.updateEnrollmentFailed(tenantId, enrollmentId, updatedAt);
    }
    @Override public Optional<ProjectedEnrollmentRow> findProjectedEnrollmentForUpdate(String tenantId,
            String enrollmentId) {
        return Optional.ofNullable(mapper.selectProjectedEnrollmentForUpdate(tenantId, enrollmentId));
    }
    @Override public int countDefinition(String tenantId, String journeyId, long version) {
        return mapper.countDefinition(tenantId, journeyId, version);
    }
    @Override public void saveProjectedEnrollment(ProjectedEnrollmentWrite write) {
        one(mapper.insertProjectedEnrollment(write), "保存投影旅程实例");
    }
    @Override public void updateProjectedEnrollment(ProjectedEnrollmentUpdate write) {
        one(mapper.updateProjectedEnrollment(write), "更新投影旅程实例");
    }
    @Override public Optional<EnrollmentIdentityRow> findEnrollmentIdentity(String tenantId, String enrollmentId) {
        return Optional.ofNullable(mapper.selectEnrollmentIdentity(tenantId, enrollmentId));
    }
    @Override public void saveDispatchOutbox(DispatchOutboxWrite write) {
        one(mapper.insertDispatchOutbox(write), "保存旅程 dispatch outbox");
    }
    @Override public int incrementDispatchPosition(String tenantId, String enrollmentId, String updatedAt) {
        return mapper.incrementDispatchPosition(tenantId, enrollmentId, updatedAt);
    }
    @Override public boolean tryCreateDispatchPosition(String tenantId, String enrollmentId, String updatedAt) {
        return attempt(() -> mapper.insertDispatchPosition(tenantId, enrollmentId, updatedAt));
    }
    @Override public Optional<Long> findDispatchSequence(String tenantId, String enrollmentId) {
        return Optional.ofNullable(mapper.selectDispatchSequence(tenantId, enrollmentId));
    }

    private static boolean attempt(InsertAction action) {
        try { return action.execute() == 1; }
        catch (DuplicateKeyException duplicate) { return false; }
    }

    private static void one(int affected, String operation) {
        if (affected != 1) throw new IllegalStateException(operation + "的受影响行数不是 1");
    }

    @FunctionalInterface private interface InsertAction { int execute(); }
}
