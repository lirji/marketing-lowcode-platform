package com.acme.marketing.benefit.application;

import com.acme.marketing.benefit.domain.ResourceAccount;
import java.util.List;
import java.util.Optional;

/**
 * 权益定义、资源账户、预留结算和资金流水持久化端口。
 *
 * <p>记录类型只表达数据库边界所需的数据；业务不变量仍由应用服务和领域对象维护。
 */
public interface BenefitFundingRepository {
    void saveAccount(AccountWrite write);
    List<ResourceAccount> findAccounts(String tenantId, boolean ordered);
    Optional<ResourceAccount> findAccount(String tenantId, String resourceKey, boolean lock);
    int updateAccountFence(AccountFenceWrite write);
    int updateAccountBalance(AccountBalanceWrite write);

    List<BenefitRow> findLatestBenefits(String tenantId);
    Optional<BenefitRow> findLatestBenefit(String tenantId, String benefitId);
    Optional<ReleaseBenefitRow> findReleaseBenefit(String tenantId, String benefitId, long version);
    void ensureBenefitHead(String tenantId, String benefitId);
    Optional<Long> findBenefitHeadForUpdate(String tenantId, String benefitId);
    int countResource(String tenantId, String resourceKey);
    void saveBenefit(BenefitWrite write);
    void updateBenefitHead(String tenantId, String benefitId, long version);

    int countBuckets(String tenantId, String resourceKey);
    int updateBucketFence(BucketFenceWrite write);
    void saveBucket(BucketWrite write);
    List<Integer> findEligibleBucketIds(String tenantId, String resourceKey, long fencingEpoch, long amount);
    int reserveBucket(BucketReserveWrite write);
    Optional<Long> findBucketVersion(String tenantId, String resourceKey, int bucketId);
    List<BudgetBucketRow> findBucketsForUpdate(String tenantId, String resourceKey);
    Optional<BudgetBucketRow> findBucketForUpdate(String tenantId, String resourceKey, int bucketId);
    int updateBucketBalance(BucketBalanceWrite write);
    BucketTotals summarizeBuckets(String tenantId, String resourceKey);

    void saveJourneyGrant(JourneyGrantWrite write);
    boolean trySaveApplication(ApplicationWrite write);
    void saveReservationItem(ReservationItemWrite write);
    void saveEscrowAllocation(EscrowAllocationWrite write);
    List<ExpiredApplicationRow> findExpiredApplications(String tenantId, String now, int limit);
    int expireApplication(String tenantId, String applicationId, String updatedAt);
    Optional<Integer> findExpiryAttemptsForUpdate(String tenantId, String applicationId);
    int updateExpiryFailure(ExpiryFailureWrite write);
    int updateApplicationState(String tenantId, String applicationId, String stateName, String updatedAt);
    Optional<StoredApplicationRow> findApplicationForUpdate(String tenantId, String applicationId);
    List<ReservationItemRow> findReservationItems(String tenantId, String applicationId, boolean lock);
    Optional<ApplicationRow> findApplication(String tenantId, String applicationId);
    List<ItemRow> findItems(String tenantId, String applicationId);
    List<EscrowAllocationRow> findEscrowAllocationsForUpdate(String tenantId, String applicationId,
            String resourceKey);
    int updateReservationAmounts(ReservationMutation write);
    int updateEscrowAllocationAmounts(EscrowMutation write);

    List<LedgerRow> findLedger(String tenantId);
    List<ReconcileItemRow> findReconcileItems(String tenantId);
    List<EscrowReconcileRow> findEscrowReconcileItems(String tenantId);
    List<OutboxTypeRow> findOutboxTypes(String tenantId);
    List<ApplicationStateRow> findApplicationStates(String tenantId);

    boolean tryBeginCommand(CommandWrite write);
    Optional<CommandRow> findCommandForUpdate(String tenantId, String commandId);
    void completeCommand(String tenantId, String commandId, String responseJson);

    void saveLedger(LedgerWrite write);
    void saveOutbox(OutboxWrite write);
    int incrementOutboxPosition(String tenantId, String aggregateId, String updatedAt);
    boolean tryCreateOutboxPosition(String tenantId, String aggregateId, String updatedAt);
    Optional<Long> findOutboxPosition(String tenantId, String aggregateId);

    record AccountWrite(String tenantId, String resourceKey, String resourceType, String currencyCode,
            long authorizedAmount, long availableAmount, long reservedAmount, long consumedAmount,
            long returnedAmount, long fencingEpoch, long versionNo, String stateName, String updatedAt) { }
    record AccountFenceWrite(String tenantId, String resourceKey, long fencingEpoch, long versionNo,
            String stateName, String updatedAt, long expectedFencingEpoch, long expectedVersionNo) { }
    record AccountBalanceWrite(String tenantId, String resourceKey, long availableAmount, long reservedAmount,
            long consumedAmount, long returnedAmount, long versionNo, long expectedVersionNo, String updatedAt) { }
    record BenefitRow(String benefitId, long version, String name, String status, String resourceKey,
            String benefitSkuId, String policyJson, String createdBy, String createdAt) { }
    record ReleaseBenefitRow(String status, String benefitSkuId, String policyJson) { }
    record BenefitWrite(String tenantId, String benefitId, long versionNo, String name, String status,
            String resourceKey, String benefitSkuId, String policyJson, String createdBy, String createdAt) { }
    record BucketFenceWrite(String tenantId, String resourceKey, long fencingEpoch, String stateName,
            long expectedFencingEpoch, String updatedAt) { }
    record BucketWrite(String tenantId, String resourceKey, int bucketId, long authorizedAmount,
            long availableAmount, long reservedAmount, long consumedAmount, long returnedAmount,
            long fencingEpoch, long versionNo, String stateName, String updatedAt) { }
    record BucketReserveWrite(String tenantId, String resourceKey, int bucketId, long fencingEpoch,
            long amount, String updatedAt) { }
    record BudgetBucketRow(int bucketId, long authorized, long available, long reserved, long consumed,
            long returned, long fencingEpoch, long version, String state) { }
    record BucketBalanceWrite(String tenantId, String resourceKey, int bucketId, long available,
            long reserved, long consumed, long returned, long version, long expectedVersion,
            long fencingEpoch, String updatedAt) { }
    record BucketTotals(int count, long authorized, long available, long reserved, long consumed,
            long returned, long maxVersion, long minFencingEpoch, long maxFencingEpoch,
            int distinctStates, String state) { }
    record JourneyGrantWrite(String tenantId, String commandId, String enrollmentId, String subjectToken,
            String journeyId, long journeyVersion, String resourceKey, String benefitId, long quantity,
            long fencingEpoch, String stateName, String createdAt) { }
    record ApplicationWrite(String tenantId, String applicationId, String quoteId, String decisionRequestId,
            String orderId, String organizationId, String shopIdsJson, String cartDigest, String tokenDigest,
            long generationNo, String stateName, long totalDiscount, String currencyCode, String expiresAt,
            String createdAt, String updatedAt, String expiryNextAttemptAt) { }
    record ReservationItemWrite(String tenantId, String applicationId, String resourceKey, String resourceType,
            String currencyCode, long originalAmount, long reservedAmount, long consumedAmount,
            long refundedAmount, long releasedAmount, long reservationEpoch) { }
    record EscrowAllocationWrite(String tenantId, String applicationId, String resourceKey, int bucketId,
            long originalAmount, long reservedAmount, long consumedAmount, long refundedAmount,
            long releasedAmount, long reservationEpoch) { }
    record ExpiredApplicationRow(String tenantId, String applicationId) { }
    record ExpiryFailureWrite(String tenantId, String applicationId, int attempts, String nextAttemptAt,
            String lastError) { }
    record StoredApplicationRow(String applicationId, String orderId, String organizationId,
            String shopIdsJson, String state, String expiresAt) { }
    record ReservationItemRow(String resourceKey, long originalAmount, long reservedAmount,
            long consumedAmount, long refundedAmount, long releasedAmount, long reservationEpoch) { }
    record ApplicationRow(String quoteId, String orderId, String cartDigest, long generation,
            String state, long totalDiscount, String currency, String expiresAt, String createdAt,
            String updatedAt) { }
    record ItemRow(String resourceKey, String type, String currency, long originalAmount, long reservedAmount,
            long consumedAmount, long refundedAmount, long releasedAmount, long reservationEpoch) { }
    record EscrowAllocationRow(int bucketId, long original, long reserved, long consumed,
            long refunded, long released, long reservationEpoch) { }
    record ReservationMutation(String tenantId, String applicationId, String resourceKey,
            String operation, long amount) { }
    record EscrowMutation(String tenantId, String applicationId, String resourceKey, int bucketId,
            String operation, long amount) { }
    record LedgerRow(String applicationId, String resourceKey, int escrowBucketId, String operation,
            String debitBucket, String creditBucket, long amount, long accountVersion) { }
    record ReconcileItemRow(String applicationId, String resourceKey, long original, long reserved,
            long consumed, long refunded, long released, long reservationEpoch) { }
    record EscrowReconcileRow(String applicationId, String resourceKey, long original, long reserved,
            long consumed, long refunded, long released, long minReservationEpoch,
            long maxReservationEpoch) { }
    record OutboxTypeRow(String aggregateId, String eventType) { }
    record ApplicationStateRow(String applicationId, String stateName) { }
    record CommandWrite(String tenantId, String commandId, String payloadHash, String stateName,
            String createdAt, String expiresAt) { }
    record CommandRow(String payloadHash, String state, String responseJson) { }
    record LedgerWrite(String tenantId, String ledgerId, String applicationId, String orderId,
            String resourceKey, int escrowBucketId, String operation, String debitBucket,
            String creditBucket, long amount, long accountVersion, String correctionOf, String occurredAt) { }
    record OutboxWrite(String tenantId, String eventId, String aggregateId, String eventType,
            String destinationTopic, String partitionKey, long streamSequence, String payloadJson,
            String nextAttemptAt, String createdAt) { }
}
