package com.acme.marketing.control.infrastructure.persistence;

import com.acme.marketing.control.application.ControlRepository;
import com.acme.marketing.control.infrastructure.persistence.mapper.ControlMapper;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

/** 基于 MyBatis 的营销控制面仓储适配器。 */
@Repository
public class MybatisControlRepository implements ControlRepository {
    private final ControlMapper mapper;

    public MybatisControlRepository(ControlMapper mapper) { this.mapper = mapper; }

    @Override public long lockReleaseBundleVersion(String tenantId, String campaignId) {
        Long version = mapper.selectReleaseBundleVersion(tenantId, campaignId);
        if (version == null) throw new IllegalStateException("locked campaign disappeared");
        return version;
    }
    @Override public int advanceReleaseBundleVersion(String tenantId, String campaignId, long expectedVersion) {
        return mapper.advanceReleaseBundleVersion(tenantId, campaignId, expectedVersion);
    }
    @Override public Optional<ReleaseBundleRow> findLatestReleaseBundle(String tenantId, String campaignId, boolean lock) {
        return Optional.ofNullable(mapper.selectLatestReleaseBundle(tenantId, campaignId, lock));
    }
    @Override public Optional<ReleaseBundleRow> findReleaseBundle(String tenantId, String campaignId, long version) {
        return Optional.ofNullable(mapper.selectReleaseBundle(tenantId, campaignId, version));
    }
    @Override public Optional<ReleaseBundleRow> lockReleaseBundle(String tenantId, String campaignId, long version) {
        return Optional.ofNullable(mapper.lockReleaseBundle(tenantId, campaignId, version));
    }
    @Override public List<ReleaseBundleAckRow> findReleaseBundleAcks(String tenantId, String bundleId, int limit) {
        return mapper.selectReleaseBundleAcks(tenantId, bundleId, limit);
    }
    @Override public void saveReleaseBundleAck(ReleaseBundleAckRow row) {
        // 调用方持有组合头锁；MySQL首次插入返回1，替换已有证据可返回2。
        int changed = mapper.upsertReleaseBundleAck(row);
        if (changed < 1 || changed > 2) throw new IllegalStateException("保存组合就绪证据的受影响行数不正确");
    }
    @Override public void saveReleaseBundle(ReleaseBundleRow row) { one(mapper.insertReleaseBundle(row), "保存活动组合"); }
    @Override public void saveReleaseBundleMember(ReleaseBundleMemberWrite write) {
        one(mapper.insertReleaseBundleMember(write), "保存活动组合成员");
    }
    @Override public Optional<String> findReleaseBundleOwner(String tenantId, String manifestId, boolean lock) {
        return Optional.ofNullable(mapper.selectReleaseBundleOwner(tenantId, manifestId, lock));
    }

    @Override public boolean campaignNumberTaken(String number) { return mapper.lockCampaignNumber(number) != null; }
    @Override public boolean approvalNumberTaken(String number) { return mapper.lockApprovalNumber(number) != null; }
    @Override public void saveCampaign(CampaignWrite write) { one(mapper.insertCampaign(write), "保存活动"); }
    @Override public List<CampaignRow> findCampaigns(String tenantId, Set<String> organizations,
            Set<String> shops, String campaignType, String businessNo, String originalId) { return mapper.selectCampaigns(tenantId, organizations, shops, campaignType, businessNo, originalId); }
    @Override public Optional<CampaignOwnershipRow> findCampaignOwnership(String tenantId, String campaignId,
            boolean lock) { return Optional.ofNullable(mapper.selectCampaignOwnership(tenantId, campaignId, lock)); }
    @Override public Optional<DmnDraftRow> findLatestDmnDraft(String tenantId, String campaignId, boolean lock) {
        return Optional.ofNullable(mapper.selectLatestDmnDraft(tenantId, campaignId, lock));
    }
    @Override public Optional<DmnDraftRow> findDmnDraft(String tenantId, String campaignId, long version) {
        return Optional.ofNullable(mapper.selectDmnDraft(tenantId, campaignId, version));
    }
    @Override public void saveDmnDraft(DmnDraftRow row) { one(mapper.insertDmnDraft(row), "保存DMN草稿版本"); }
    @Override public List<String> findDefinitionOwners(String tenantId, String definitionId) {
        return mapper.selectDefinitionOwners(tenantId, definitionId);
    }
    @Override public Optional<DefinitionRow> findDefinitionBySemanticHash(String tenantId, String definitionId,
            String semanticHash) {
        return Optional.ofNullable(mapper.selectDefinitionBySemanticHash(tenantId, definitionId, semanticHash));
    }
    @Override public Optional<Long> findLatestDefinitionVersion(String tenantId, String definitionId) {
        return Optional.ofNullable(mapper.selectLatestDefinitionVersion(tenantId, definitionId));
    }
    @Override public void saveDefinition(DefinitionWrite write) { one(mapper.insertDefinition(write), "保存定义"); }
    @Override public boolean definitionNumberTaken(String number) {
        return mapper.lockDefinitionNumber(number) != null;
    }

    @Override public Optional<DefinitionRow> findDefinitionByNumber(String tenantId, String number) {
        return Optional.ofNullable(mapper.selectDefinitionByNumber(tenantId, number));
    }

    @Override public Optional<DefinitionRow> findLatestDefinition(String tenantId, String campaignId,
            String dialect) { return Optional.ofNullable(mapper.selectLatestDefinition(tenantId, campaignId, dialect)); }
    @Override public Optional<DefinitionRow> findDefinition(String tenantId, String definitionId, long version) {
        return Optional.ofNullable(mapper.selectDefinition(tenantId, definitionId, version));
    }
    /** 主键前缀包含租户与定义，使用版本号游标支持稳定的倒序分页。 */
    @Override public List<OfferVersionRow> findOfferVersions(String tenantId, String campaignId, String definitionId,
            String dialect, Long beforeVersion, int limit) {
        return mapper.selectOfferVersions(tenantId, campaignId, definitionId, dialect, beforeVersion, limit);
    }
    @Override public int updateDefinitionStatus(String tenantId, String definitionId, long version, String status,
            String updatedAt) { return mapper.updateDefinitionStatus(tenantId, definitionId, version, status, updatedAt); }
    @Override public void saveApprovalCase(ApprovalCaseWrite write) { one(mapper.insertApprovalCase(write), "保存审批单"); }
    @Override public void saveTerms(TermsWrite write) { one(mapper.insertTerms(write), "保存条款快照"); }
    @Override public void saveApprovalDecision(ApprovalDecisionWrite write) {
        one(mapper.insertApprovalDecision(write), "保存审批决定");
    }
    @Override public void updateApprovalCase(String tenantId, String caseId, String status, String updatedAt) {
        one(mapper.updateApprovalCase(tenantId, caseId, status, updatedAt), "更新审批单");
    }
    @Override public Optional<ApprovalCaseRow> findApprovalCase(String tenantId, String caseId, boolean lock) {
        return Optional.ofNullable(mapper.selectApprovalCase(tenantId, caseId, lock));
    }
    @Override public List<ApprovalCaseRow> findApprovalCases(ApprovalListQuery query) {
        return mapper.selectApprovalCases(query);
    }
    @Override public List<ApprovalCaseRow> findApprovalPage(ApprovalListQuery query) {
        return mapper.selectApprovalPage(query);
    }
    @Override public Optional<ApprovalCursorRow> findApprovalCursor(ApprovalListQuery query) {
        return Optional.ofNullable(mapper.selectApprovalCursor(query));
    }
    @Override public List<ApprovalDecisionRow> findApprovalDecisions(String tenantId, String caseId) {
        return mapper.selectApprovalDecisions(tenantId, caseId);
    }
    @Override public Optional<TermsRow> findLatestTerms(String tenantId, String definitionId, long version) {
        return Optional.ofNullable(mapper.selectLatestTerms(tenantId, definitionId, version));
    }
    @Override public void ensureAuditHead(String tenantId, String updatedAt) {
        // 已存在时自赋值可能报告 0 行，后续锁定读取才是权威结果。
        mapper.upsertAuditHead(tenantId, updatedAt);
    }
    @Override public Optional<AuditHeadRow> findAuditHeadForUpdate(String tenantId) {
        return Optional.ofNullable(mapper.selectAuditHeadForUpdate(tenantId));
    }
    @Override public void saveAudit(AuditWrite write) { one(mapper.insertAudit(write), "保存审计记录"); }
    @Override public int compareAndSetAuditHead(AuditHeadWrite write) { return mapper.updateAuditHead(write); }
    @Override public void saveManifest(ManifestWrite write) { one(mapper.insertManifest(write), "保存发布清单"); }
    @Override public void updateSlotLatestGeneration(SlotLatestWrite write) {
        one(mapper.updateSlotLatestGeneration(write), "更新最新发布代次");
    }
    @Override public int updateRuntimeAck(RuntimeAckWrite write) { return mapper.updateRuntimeAck(write); }
    @Override public void saveRuntimeAck(RuntimeAckWrite write) { one(mapper.insertRuntimeAck(write), "保存运行时 ACK"); }
    @Override public int compareAndSetSlot(SlotAdvanceWrite write) { return mapper.compareAndSetSlot(write); }
    @Override public void updateManifestState(String tenantId, String manifestId, String stateName) {
        one(mapper.updateManifestState(tenantId, manifestId, stateName), "更新发布清单状态");
    }
    @Override public boolean tryCreateKillSwitch(KillSwitchWrite write) {
        return attempt(() -> mapper.insertKillSwitch(write));
    }
    @Override public Optional<Long> findKillSwitchSequenceForUpdate(String tenantId, String namespace) {
        return Optional.ofNullable(mapper.selectKillSwitchSequenceForUpdate(tenantId, namespace));
    }
    @Override public void updateKillSwitch(KillSwitchWrite write) { one(mapper.updateKillSwitch(write), "更新熔断开关"); }
    @Override public List<ManifestRow> findManifests(ManifestListQuery query) {
        return mapper.selectManifests(query);
    }
    @Override public Optional<ManifestCursorRow> findManifestCursor(ManifestListQuery query) {
        return Optional.ofNullable(mapper.selectManifestCursor(query));
    }

    @Override public ReadinessRow summarizeReadiness(String tenantId, String manifestId, String statusName,
            String leaseFloor) { return mapper.selectReadiness(tenantId, manifestId, statusName, leaseFloor); }
    @Override public List<ResourceScopeRow> findDefinitionScopes(String tenantId, String definitionId, long version,
            boolean approvedOnly) { return mapper.selectDefinitionScopes(tenantId, definitionId, version, approvedOnly); }
    @Override public int countApprovedCase(String tenantId, String caseId, String definitionId, long version) {
        return mapper.countApprovedCase(tenantId, caseId, definitionId, version);
    }
    @Override public Optional<String> findApprovedSemanticHash(String tenantId, String definitionId, long version) {
        return Optional.ofNullable(mapper.selectApprovedSemanticHash(tenantId, definitionId, version));
    }
    @Override public Optional<String> findApprovedGraphJson(String tenantId, String definitionId, long version) {
        return Optional.ofNullable(mapper.selectApprovedGraphJson(tenantId, definitionId, version));
    }
    @Override public boolean tryCreateReleaseSlot(ReleaseSlotWrite write) {
        return attempt(() -> mapper.insertReleaseSlot(write));
    }
    @Override public Optional<ReleaseSlotRow> findReleaseSlot(String tenantId, String environment, String cell,
            String runtime, String namespace, boolean lock) {
        return Optional.ofNullable(mapper.selectReleaseSlot(tenantId, environment, cell, runtime, namespace, lock));
    }
    @Override public int markReleaseSlotBundleManaged(String tenantId, String environment, String cell,
            String runtime, String namespace) {
        return mapper.markReleaseSlotBundleManaged(tenantId, environment, cell, runtime, namespace);
    }
    @Override public List<Long> findRetainedGenerations(String tenantId, String environment, String cell,
            String runtime, String namespace, int limit) {
        return mapper.selectRetainedGenerations(tenantId, environment, cell, runtime, namespace, limit);
    }
    @Override public Optional<ManifestRow> findManifest(String tenantId, String manifestId, boolean lock) {
        return Optional.ofNullable(mapper.selectManifest(tenantId, manifestId, lock));
    }
    @Override public Optional<ManifestRow> findManifestByGeneration(String tenantId, String environment, String cell,
            String runtime, String namespace, long generation) {
        return Optional.ofNullable(mapper.selectManifestByGeneration(
                tenantId, environment, cell, runtime, namespace, generation));
    }
    @Override public void saveDirective(DirectiveWrite write) { one(mapper.insertDirective(write), "保存激活指令"); }
    @Override public void saveOutbox(OutboxWrite write) { one(mapper.insertOutbox(write), "保存发布 outbox"); }
    @Override public Optional<String> findLatestDirectiveJson(String tenantId, String environment, String cell,
            String runtime, String namespace, long generation) {
        return Optional.ofNullable(mapper.selectLatestDirectiveJson(
                tenantId, environment, cell, runtime, namespace, generation));
    }
    @Override public boolean tryBeginCommand(CommandWrite write) { return attempt(() -> mapper.insertCommand(write)); }
    @Override public Optional<CommandRow> findCommandForUpdate(String tenantId, String operationName,
            String idempotencyKey) {
        return Optional.ofNullable(mapper.selectCommandForUpdate(tenantId, operationName, idempotencyKey));
    }
    @Override public void deleteCommand(String tenantId, String operationName, String idempotencyKey) {
        one(mapper.deleteCommand(tenantId, operationName, idempotencyKey), "删除过期控制命令");
    }
    @Override public void completeCommand(String tenantId, String operationName, String idempotencyKey,
            String responseJson) {
        one(mapper.updateCommandCompleted(tenantId, operationName, idempotencyKey, responseJson), "完成控制命令");
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
