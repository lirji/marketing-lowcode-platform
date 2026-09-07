package com.acme.marketing.benefit.application;

import java.util.List;
import java.util.Optional;

/**
 * 发奖意图持久化端口。
 *
 * <p>应用层通过稳定的业务行模型表达幂等、租约和统一查询语义，不感知 MyBatis 与 SQL。
 */
public interface AwardIntentRepository {
    Optional<BenefitBindingRow> findBenefitBinding(String tenantId, String benefitId, long version);

    Optional<DedupeStateRow> findDedupeForUpdate(String tenantId, String sourceSystem, String sourceRequestId);
    boolean tryBeginEvaluation(EvaluationWrite write);
    Optional<ClaimStateRow> findEvaluation(String tenantId, String sourceSystem, String sourceRequestId);
    int takeEvaluationLease(EvaluationLeaseWrite write);
    int completeEvaluation(EvaluationCompletionWrite write);
    int abandonEvaluation(EvaluationOwnerKey key);

    void saveIntent(IntentWrite write);
    void saveBlock(BlockWrite write);
    Optional<IntentRow> findIntentBySource(String tenantId, String sourceSystem, String sourceRequestId);
    Optional<IntentRow> findBlockBySource(String tenantId, String sourceSystem, String sourceRequestId);
    Optional<CursorRow> findCursor(String tenantId, String campaignId, String intentId);
    List<IntentRow> findByCampaign(String tenantId, String campaignId, String createdBefore,
            String cursorIntentId, int limit);

    void saveExpectedPosition(String tenantId, String aggregateId, long lastSequence, String updatedAt);
    void saveExpectedEvent(ExpectedEventWrite write);

    record BenefitBindingRow(String benefitId, long version, String status, String benefitSkuId,
            String policyJson) { }
    record DedupeStateRow(String requestHash, String resultType, String leaseOwner, long leaseVersion) { }
    record ClaimStateRow(String requestHash, String resultType, String leaseUntil, long leaseVersion) { }
    record EvaluationWrite(String tenantId, String sourceSystem, String sourceRequestId, String requestHash,
            String resultType, String leaseOwner, String leaseUntil, long leaseVersion,
            String createdAt, String updatedAt) { }
    record EvaluationLeaseWrite(String tenantId, String sourceSystem, String sourceRequestId,
            String requestHash, String leaseOwner, String leaseUntil, long nextLeaseVersion,
            long expectedLeaseVersion, String now, String updatedAt) { }
    record EvaluationCompletionWrite(String tenantId, String sourceSystem, String sourceRequestId,
            String resultType, String leaseOwner, long leaseVersion, String updatedAt) { }
    record EvaluationOwnerKey(String tenantId, String sourceSystem, String sourceRequestId,
            String leaseOwner, long leaseVersion) { }
    record IntentWrite(String tenantId, String intentId, String sourceSystem, String sourceRequestId,
            String campaignId, long definitionVersion, String subjectHash, String deliveryMode,
            String requestHash, String payloadHash, String payloadJson, String statusName,
            String deliveryResult, String nextAttemptAt, String createdAt, String updatedAt) { }
    record BlockWrite(String tenantId, String intentId, String sourceSystem, String sourceRequestId,
            String campaignId, long definitionVersion, String subjectHash, String deliveryMode,
            String requestHash, String riskAction, String riskReason, String riskDecisionId,
            String createdAt, String updatedAt) { }
    record IntentRow(String intentId, String sourceSystem, String sourceRequestId, String campaignId,
            long definitionVersion, String subjectHash, String deliveryMode, String requestHash,
            String internalStatus, String deliveryResult, int attempts, String benefitOrderNo,
            String lastError, String createdAt, String updatedAt, String sentAt,
            String riskAction, String riskReason, String riskDecisionId) { }
    record CursorRow(String createdAt, String intentId) { }
    record ExpectedEventWrite(String tenantId, String eventId, String aggregateId, String eventType,
            String destinationTopic, String partitionKey, long streamSequence, String payloadJson,
            String nextAttemptAt, String createdAt) { }
}
