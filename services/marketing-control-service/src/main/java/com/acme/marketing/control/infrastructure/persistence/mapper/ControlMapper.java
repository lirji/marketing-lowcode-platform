package com.acme.marketing.control.infrastructure.persistence.mapper;

import com.acme.marketing.control.application.ControlRepository;
import com.acme.marketing.control.application.ReleaseOutboxRepository;
import java.util.List;
import java.util.Set;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 营销控制库 MyBatis Mapper；SQL 统一维护在同名 XML 中。 */
@Mapper
public interface ControlMapper {
    int insertCampaign(ControlRepository.CampaignWrite write);
    List<ControlRepository.CampaignRow> selectCampaigns(@Param("tenantId") String tenantId,
            @Param("organizations") Set<String> organizations, @Param("shops") Set<String> shops, @Param("campaignType") String campaignType);
    ControlRepository.CampaignOwnershipRow selectCampaignOwnership(@Param("tenantId") String tenantId,
            @Param("campaignId") String campaignId, @Param("lock") boolean lock);
    List<String> selectDefinitionOwners(@Param("tenantId") String tenantId,
            @Param("definitionId") String definitionId);
    ControlRepository.DefinitionRow selectDefinitionBySemanticHash(@Param("tenantId") String tenantId,
            @Param("definitionId") String definitionId, @Param("semanticHash") String semanticHash);
    Long selectLatestDefinitionVersion(@Param("tenantId") String tenantId,
            @Param("definitionId") String definitionId);
    int insertDefinition(ControlRepository.DefinitionWrite write);
    ControlRepository.DefinitionRow selectLatestDefinition(@Param("tenantId") String tenantId,
            @Param("campaignId") String campaignId, @Param("dialect") String dialect);
    ControlRepository.DefinitionRow selectDefinition(@Param("tenantId") String tenantId,
            @Param("definitionId") String definitionId, @Param("version") long version);
    int updateDefinitionStatus(@Param("tenantId") String tenantId, @Param("definitionId") String definitionId,
            @Param("version") long version, @Param("status") String status, @Param("updatedAt") String updatedAt);
    int insertApprovalCase(ControlRepository.ApprovalCaseWrite write);
    int insertTerms(ControlRepository.TermsWrite write);
    int insertApprovalDecision(ControlRepository.ApprovalDecisionWrite write);
    int updateApprovalCase(@Param("tenantId") String tenantId, @Param("caseId") String caseId,
            @Param("status") String status, @Param("updatedAt") String updatedAt);
    ControlRepository.ApprovalCaseRow selectApprovalCase(@Param("tenantId") String tenantId,
            @Param("caseId") String caseId, @Param("lock") boolean lock);
    List<ControlRepository.ApprovalCaseRow> selectApprovalCases(@Param("tenantId") String tenantId,
            @Param("limit") int limit);
    List<ControlRepository.ApprovalDecisionRow> selectApprovalDecisions(@Param("tenantId") String tenantId,
            @Param("caseId") String caseId);
    ControlRepository.TermsRow selectLatestTerms(@Param("tenantId") String tenantId,
            @Param("definitionId") String definitionId, @Param("version") long version);
    int upsertAuditHead(@Param("tenantId") String tenantId, @Param("updatedAt") String updatedAt);
    ControlRepository.AuditHeadRow selectAuditHeadForUpdate(@Param("tenantId") String tenantId);
    int insertAudit(ControlRepository.AuditWrite write);
    int updateAuditHead(ControlRepository.AuditHeadWrite write);
    int insertManifest(ControlRepository.ManifestWrite write);
    int updateSlotLatestGeneration(ControlRepository.SlotLatestWrite write);
    int updateRuntimeAck(ControlRepository.RuntimeAckWrite write);
    int insertRuntimeAck(ControlRepository.RuntimeAckWrite write);
    int compareAndSetSlot(ControlRepository.SlotAdvanceWrite write);
    int updateManifestState(@Param("tenantId") String tenantId, @Param("manifestId") String manifestId,
            @Param("stateName") String stateName);
    int insertKillSwitch(ControlRepository.KillSwitchWrite write);
    Long selectKillSwitchSequenceForUpdate(@Param("tenantId") String tenantId,
            @Param("namespace") String namespace);
    int updateKillSwitch(ControlRepository.KillSwitchWrite write);
    List<ControlRepository.ManifestRow> selectManifests(@Param("tenantId") String tenantId,
            @Param("limit") int limit);
    ControlRepository.ReadinessRow selectReadiness(@Param("tenantId") String tenantId,
            @Param("manifestId") String manifestId, @Param("statusName") String statusName,
            @Param("leaseFloor") String leaseFloor);
    List<ControlRepository.ResourceScopeRow> selectDefinitionScopes(@Param("tenantId") String tenantId,
            @Param("definitionId") String definitionId, @Param("version") long version,
            @Param("approvedOnly") boolean approvedOnly);
    int countApprovedCase(@Param("tenantId") String tenantId, @Param("caseId") String caseId,
            @Param("definitionId") String definitionId, @Param("version") long version);
    String selectApprovedSemanticHash(@Param("tenantId") String tenantId,
            @Param("definitionId") String definitionId, @Param("version") long version);
    String selectApprovedGraphJson(@Param("tenantId") String tenantId,
            @Param("definitionId") String definitionId, @Param("version") long version);
    int insertReleaseSlot(ControlRepository.ReleaseSlotWrite write);
    ControlRepository.ReleaseSlotRow selectReleaseSlot(@Param("tenantId") String tenantId,
            @Param("environment") String environment, @Param("cell") String cell,
            @Param("runtime") String runtime, @Param("namespace") String namespace,
            @Param("lock") boolean lock);
    List<Long> selectRetainedGenerations(@Param("tenantId") String tenantId,
            @Param("environment") String environment, @Param("cell") String cell,
            @Param("runtime") String runtime, @Param("namespace") String namespace,
            @Param("limit") int limit);
    ControlRepository.ManifestRow selectManifest(@Param("tenantId") String tenantId,
            @Param("manifestId") String manifestId, @Param("lock") boolean lock);
    ControlRepository.ManifestRow selectManifestByGeneration(@Param("tenantId") String tenantId,
            @Param("environment") String environment, @Param("cell") String cell,
            @Param("runtime") String runtime, @Param("namespace") String namespace,
            @Param("generation") long generation);
    int insertDirective(ControlRepository.DirectiveWrite write);
    int insertOutbox(ControlRepository.OutboxWrite write);
    String selectLatestDirectiveJson(@Param("tenantId") String tenantId,
            @Param("environment") String environment, @Param("cell") String cell,
            @Param("runtime") String runtime, @Param("namespace") String namespace,
            @Param("generation") long generation);
    int insertCommand(ControlRepository.CommandWrite write);
    ControlRepository.CommandRow selectCommandForUpdate(@Param("tenantId") String tenantId,
            @Param("operationName") String operationName, @Param("idempotencyKey") String idempotencyKey);
    int deleteCommand(@Param("tenantId") String tenantId, @Param("operationName") String operationName,
            @Param("idempotencyKey") String idempotencyKey);
    int updateCommandCompleted(@Param("tenantId") String tenantId, @Param("operationName") String operationName,
            @Param("idempotencyKey") String idempotencyKey, @Param("responseJson") String responseJson);
    ReleaseOutboxRepository.PendingEvent selectNextOutbox(@Param("now") String now);
    int updateOutboxPublished(@Param("tenantId") String tenantId, @Param("eventId") String eventId,
            @Param("publishedAt") String publishedAt, @Param("attempts") int attempts);
    int updateOutboxRetry(@Param("tenantId") String tenantId, @Param("eventId") String eventId,
            @Param("nextAttemptAt") String nextAttemptAt, @Param("attempts") int attempts,
            @Param("error") String error);
}
