package com.acme.marketing.control.application;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 营销控制面持久化端口。
 *
 * <p>覆盖活动、定义审批、审计链和发布控制；应用层不感知 MyBatis 与 SQL。
 */
public interface ControlRepository {
    void saveCampaign(CampaignWrite write);
    List<CampaignRow> findCampaigns(String tenantId, Set<String> organizations, Set<String> shops, String campaignType);
    Optional<CampaignOwnershipRow> findCampaignOwnership(String tenantId, String campaignId, boolean lock);

    /** 以始终存在的活动行分配版本，避免在空历史范围上获取间隙锁。 */
    long lockReleaseBundleVersion(String tenantId, String campaignId);
    int advanceReleaseBundleVersion(String tenantId, String campaignId, long expectedVersion);
    Optional<ReleaseBundleRow> findLatestReleaseBundle(String tenantId, String campaignId, boolean lock);
    Optional<ReleaseBundleRow> findReleaseBundle(String tenantId, String campaignId, long version);
    /** 已存在的组合头串行化证据更新，避免在空ACK索引上竞争间隙锁。 */
    Optional<ReleaseBundleRow> lockReleaseBundle(String tenantId, String campaignId, long version);
    List<ReleaseBundleAckRow> findReleaseBundleAcks(String tenantId, String bundleId, int limit);
    void saveReleaseBundleAck(ReleaseBundleAckRow row);
    void saveReleaseBundle(ReleaseBundleRow row);
    void saveReleaseBundleMember(ReleaseBundleMemberWrite write);
    /** 归属只在清单锁内建立；激活时使用当前读，防止RR旧快照绕过保护。 */
    Optional<String> findReleaseBundleOwner(String tenantId, String manifestId, boolean lock);
    record ReleaseBundleRow(String tenantId, String bundleId, String campaignId, long versionNo,
            String environmentName, String cellId, String stateName, String contentJson, String contentHash,
            String commandId, String createdBy, String createdAt) { }
    record ReleaseBundleMemberWrite(String tenantId, String manifestId, String bundleId) { }
    record ReleaseBundleAckRow(String tenantId, String bundleId, String manifestId, String runtimeId,
            String ackJson, String acknowledgedAt, String signatureValue) { }

    /** 保存时使用锁定读，确保并发等待结束后读取到最新提交版本。 */
    Optional<DmnDraftRow> findLatestDmnDraft(String tenantId, String campaignId, boolean lock);
    /** 历史版本始终按租户和归属活动精确查询。 */
    Optional<DmnDraftRow> findDmnDraft(String tenantId, String campaignId, long version);
    /** 仅插入不可变版本，不提供覆盖历史草稿的写入口。 */
    void saveDmnDraft(DmnDraftRow row);
    /** 保存作者、原始命令和内容摘要，使每个版本可追溯。 */
    record DmnDraftRow(String tenantId, String campaignId, long versionNo, String tableJson,
            String contentHash, String commandId, String createdBy, String createdAt) { }

    List<String> findDefinitionOwners(String tenantId, String definitionId);
    Optional<DefinitionRow> findDefinitionBySemanticHash(String tenantId, String definitionId, String semanticHash);
    Optional<Long> findLatestDefinitionVersion(String tenantId, String definitionId);
    void saveDefinition(DefinitionWrite write);
    Optional<DefinitionRow> findLatestDefinition(String tenantId, String campaignId, String dialect);
    Optional<DefinitionRow> findDefinition(String tenantId, String definitionId, long version);
    int updateDefinitionStatus(String tenantId, String definitionId, long version, String status, String updatedAt);

    void saveApprovalCase(ApprovalCaseWrite write);
    void saveTerms(TermsWrite write);
    void saveApprovalDecision(ApprovalDecisionWrite write);
    void updateApprovalCase(String tenantId, String caseId, String status, String updatedAt);
    Optional<ApprovalCaseRow> findApprovalCase(String tenantId, String caseId, boolean lock);
    List<ApprovalCaseRow> findApprovalCases(ApprovalListQuery query);
    List<ApprovalCaseRow> findApprovalPage(ApprovalListQuery query);
    Optional<ApprovalCursorRow> findApprovalCursor(ApprovalListQuery query);
    List<ApprovalDecisionRow> findApprovalDecisions(String tenantId, String caseId);
    Optional<TermsRow> findLatestTerms(String tenantId, String definitionId, long version);

    void ensureAuditHead(String tenantId, String updatedAt);
    Optional<AuditHeadRow> findAuditHeadForUpdate(String tenantId);
    void saveAudit(AuditWrite write);
    int compareAndSetAuditHead(AuditHeadWrite write);

    void saveManifest(ManifestWrite write);
    void updateSlotLatestGeneration(SlotLatestWrite write);
    int updateRuntimeAck(RuntimeAckWrite write);
    void saveRuntimeAck(RuntimeAckWrite write);
    int compareAndSetSlot(SlotAdvanceWrite write);
    void updateManifestState(String tenantId, String manifestId, String stateName);
    boolean tryCreateKillSwitch(KillSwitchWrite write);
    Optional<Long> findKillSwitchSequenceForUpdate(String tenantId, String namespace);
    void updateKillSwitch(KillSwitchWrite write);
    List<ManifestRow> findManifests(ManifestListQuery query);
    Optional<ManifestCursorRow> findManifestCursor(ManifestListQuery query);
    ReadinessRow summarizeReadiness(String tenantId, String manifestId, String statusName, String leaseFloor);
    List<ResourceScopeRow> findDefinitionScopes(String tenantId, String definitionId, long version,
            boolean approvedOnly);
    int countApprovedCase(String tenantId, String caseId, String definitionId, long version);
    Optional<String> findApprovedSemanticHash(String tenantId, String definitionId, long version);
    Optional<String> findApprovedGraphJson(String tenantId, String definitionId, long version);
    boolean tryCreateReleaseSlot(ReleaseSlotWrite write);
    Optional<ReleaseSlotRow> findReleaseSlot(String tenantId, String environment, String cell, String runtime,
            String namespace, boolean lock);
    List<Long> findRetainedGenerations(String tenantId, String environment, String cell, String runtime,
            String namespace, int limit);
    Optional<ManifestRow> findManifest(String tenantId, String manifestId, boolean lock);
    Optional<ManifestRow> findManifestByGeneration(String tenantId, String environment, String cell,
            String runtime, String namespace, long generation);
    void saveDirective(DirectiveWrite write);
    void saveOutbox(OutboxWrite write);
    Optional<String> findLatestDirectiveJson(String tenantId, String environment, String cell, String runtime,
            String namespace, long generation);

    boolean tryBeginCommand(CommandWrite write);
    Optional<CommandRow> findCommandForUpdate(String tenantId, String operationName, String idempotencyKey);
    void deleteCommand(String tenantId, String operationName, String idempotencyKey);
    void completeCommand(String tenantId, String operationName, String idempotencyKey, String responseJson);

    record CampaignWrite(String tenantId, String campaignId, String name, String objective, String status,
            String organizationId, String shopId, String createdAt, String updatedAt, String campaignType) { }
    record CampaignRow(String campaignId, String name, String objective, String status, String createdAt,
            String updatedAt, String campaignType) { }
    record CampaignOwnershipRow(String organizationId, String shopId) { }
    record DefinitionWrite(String tenantId, String definitionId, String campaignId, long versionNo, String dialect,
            String graphJson, String semanticHash, String status, String createdBy, String createdAt,
            String updatedAt) { }
    record DefinitionRow(String campaignId, String definitionId, long versionNo, String dialect, String graphJson,
            String semanticHash, String status, String createdBy, String createdAt, String updatedAt) { }
    record ApprovalCaseWrite(String tenantId, String caseId, String definitionId, long definitionVersion,
            String submittedBy, String requiredRoles, String status, String createdAt, String updatedAt) { }
    record TermsWrite(String tenantId, String termsId, String definitionId, long definitionVersion,
            String contentJson, String contentHash, String createdAt) { }
    record ApprovalDecisionWrite(String tenantId, String caseId, String roleName, String actorId,
            String decidedAt) { }
    record ApprovalCaseRow(String caseId, String definitionId, long definitionVersion, String submittedBy,
            String requiredRoles, String status, String updatedAt) { }
    /** 仅承载已验证的查询条件；范围谓词必须先于LIMIT执行。 */
    record ApprovalListQuery(String tenantId, Set<String> organizations, Set<String> shops, String status,
            String cursorCreatedAt, String cursorCaseId, int limit) { }
    record ApprovalCursorRow(String createdAt, String caseId) { }
    record ApprovalDecisionRow(String roleName, String actorId) { }
    record TermsRow(String termsId, String contentJson, String contentHash, String createdAt) { }
    record AuditHeadRow(long chainIndex, String entryHash) { }
    record AuditWrite(String tenantId, String auditId, long chainIndex, String actorId, String actionName,
            String resourceRef, String occurredAt, String previousHash, String entryHash) { }
    record AuditHeadWrite(String tenantId, long chainIndex, String entryHash, String updatedAt,
            long expectedChainIndex, String expectedEntryHash) { }
    record ManifestWrite(String tenantId, String manifestId, String environmentName, String cellId,
            String runtimeName, String namespaceName, long generationNo, String stateName, String manifestJson,
            String createdAt) { }
    record SlotLatestWrite(String tenantId, String environmentName, String cellId, String runtimeName,
            String namespaceName, long latestGeneration, String updatedAt) { }
    record RuntimeAckWrite(String tenantId, String manifestId, String runtimeId, String statusName,
            String buildDigest, String supportedAbis, String warmedArtifactIds, long capacityValue,
            String acknowledgedAt, String signatureKeyId, String signatureValue) { }
    record SlotAdvanceWrite(String tenantId, String environmentName, String cellId, String runtimeName,
            String namespaceName, long stableGeneration, long desiredGeneration, long activationSequence,
            String updatedAt, long expectedDesiredGeneration, long expectedActivationSequence) { }
    record KillSwitchWrite(String tenantId, String namespaceName, long switchSequence, boolean enabledValue,
            String reasonText, String updatedBy, String updatedAt, String directiveJson) { }
    record ManifestRow(String manifestJson, String stateName) { }
    /** 发布范围与状态必须在数据库LIMIT之前过滤，JSON中的首个制品沿用现有单定义发布范围。 */
    record ManifestListQuery(String tenantId, Set<String> organizations, Set<String> shops, String state,
            String cursorCreatedAt, String cursorManifestId, int limit) { }
    record ManifestCursorRow(String createdAt, String manifestId) { }
    record ReadinessRow(int readyCount, long totalCapacity) { }
    record ResourceScopeRow(String organizationId, String shopId) { }
    record ReleaseSlotWrite(String tenantId, String environmentName, String cellId, String runtimeName,
            String namespaceName, long stableGeneration, long desiredGeneration, long latestGeneration,
            long activationSequence, String updatedAt) { }
    record ReleaseSlotRow(long stableGeneration, long desiredGeneration, long latestGeneration,
            long activationSequence) { }
    record DirectiveWrite(String tenantId, String directiveId, String environmentName, String cellId,
            String runtimeName, String namespaceName, long activationSequence, long generationNo,
            String directiveJson, String createdAt) { }
    record OutboxWrite(String tenantId, String eventId, String aggregateType, String aggregateId,
            String eventType, String payloadJson, String occurredAt, String publishedAt, String destinationTopic,
            String partitionKey, long streamSequence, int publishAttempts, String nextAttemptAt, String lastError) { }
    record CommandWrite(String tenantId, String operationName, String idempotencyKey, String payloadHash,
            String stateName, String responseJson, String createdAt, String expiresAt) { }
    record CommandRow(String payloadHash, String stateName, String responseJson, String expiresAt) { }
}
