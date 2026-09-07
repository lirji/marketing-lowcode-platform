package com.acme.marketing.benefit.infrastructure.persistence.mapper;

import com.acme.marketing.benefit.application.BenefitFundingRepository;
import com.acme.marketing.benefit.domain.ResourceAccount;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 权益资金主账 MyBatis Mapper；所有资金 SQL 统一维护在同名 XML 中。 */
@Mapper
public interface BenefitFundingMapper {
    int insertAccount(BenefitFundingRepository.AccountWrite write);
    List<ResourceAccount> selectAccounts(@Param("tenantId") String tenantId, @Param("ordered") boolean ordered);
    ResourceAccount selectAccount(@Param("tenantId") String tenantId,
            @Param("resourceKey") String resourceKey, @Param("lock") boolean lock);
    int updateAccountFence(BenefitFundingRepository.AccountFenceWrite write);
    int updateAccountBalance(BenefitFundingRepository.AccountBalanceWrite write);
    List<BenefitFundingRepository.BenefitRow> selectLatestBenefits(@Param("tenantId") String tenantId);
    BenefitFundingRepository.BenefitRow selectLatestBenefit(@Param("tenantId") String tenantId,
            @Param("benefitId") String benefitId);
    BenefitFundingRepository.ReleaseBenefitRow selectReleaseBenefit(@Param("tenantId") String tenantId,
            @Param("benefitId") String benefitId, @Param("version") long version);
    int upsertBenefitHead(@Param("tenantId") String tenantId, @Param("benefitId") String benefitId);
    Long selectBenefitHeadForUpdate(@Param("tenantId") String tenantId, @Param("benefitId") String benefitId);
    int countResource(@Param("tenantId") String tenantId, @Param("resourceKey") String resourceKey);
    int insertBenefit(BenefitFundingRepository.BenefitWrite write);
    int updateBenefitHead(@Param("tenantId") String tenantId, @Param("benefitId") String benefitId,
            @Param("version") long version);
    int countBuckets(@Param("tenantId") String tenantId, @Param("resourceKey") String resourceKey);
    int updateBucketFence(BenefitFundingRepository.BucketFenceWrite write);
    int insertBucket(BenefitFundingRepository.BucketWrite write);
    List<Integer> selectEligibleBucketIds(@Param("tenantId") String tenantId,
            @Param("resourceKey") String resourceKey, @Param("fencingEpoch") long fencingEpoch,
            @Param("amount") long amount);
    int reserveBucket(BenefitFundingRepository.BucketReserveWrite write);
    Long selectBucketVersion(@Param("tenantId") String tenantId, @Param("resourceKey") String resourceKey,
            @Param("bucketId") int bucketId);
    List<BenefitFundingRepository.BudgetBucketRow> selectBucketsForUpdate(@Param("tenantId") String tenantId,
            @Param("resourceKey") String resourceKey);
    BenefitFundingRepository.BudgetBucketRow selectBucketForUpdate(@Param("tenantId") String tenantId,
            @Param("resourceKey") String resourceKey, @Param("bucketId") int bucketId);
    int updateBucketBalance(BenefitFundingRepository.BucketBalanceWrite write);
    BenefitFundingRepository.BucketTotals selectBucketTotals(@Param("tenantId") String tenantId,
            @Param("resourceKey") String resourceKey);
    int insertJourneyGrant(BenefitFundingRepository.JourneyGrantWrite write);
    int insertApplication(BenefitFundingRepository.ApplicationWrite write);
    int insertReservationItem(BenefitFundingRepository.ReservationItemWrite write);
    int insertEscrowAllocation(BenefitFundingRepository.EscrowAllocationWrite write);
    List<BenefitFundingRepository.ExpiredApplicationRow> selectExpiredApplications(
            @Param("tenantId") String tenantId, @Param("now") String now, @Param("limit") int limit);
    int expireApplication(@Param("tenantId") String tenantId, @Param("applicationId") String applicationId,
            @Param("updatedAt") String updatedAt);
    Integer selectExpiryAttemptsForUpdate(@Param("tenantId") String tenantId,
            @Param("applicationId") String applicationId);
    int updateExpiryFailure(BenefitFundingRepository.ExpiryFailureWrite write);
    int updateApplicationState(@Param("tenantId") String tenantId,
            @Param("applicationId") String applicationId, @Param("stateName") String stateName,
            @Param("updatedAt") String updatedAt);
    BenefitFundingRepository.StoredApplicationRow selectApplicationForUpdate(@Param("tenantId") String tenantId,
            @Param("applicationId") String applicationId);
    List<BenefitFundingRepository.ReservationItemRow> selectReservationItems(@Param("tenantId") String tenantId,
            @Param("applicationId") String applicationId, @Param("lock") boolean lock);
    BenefitFundingRepository.ApplicationRow selectApplication(@Param("tenantId") String tenantId,
            @Param("applicationId") String applicationId);
    List<BenefitFundingRepository.ItemRow> selectItems(@Param("tenantId") String tenantId,
            @Param("applicationId") String applicationId);
    List<BenefitFundingRepository.EscrowAllocationRow> selectEscrowAllocationsForUpdate(
            @Param("tenantId") String tenantId, @Param("applicationId") String applicationId,
            @Param("resourceKey") String resourceKey);
    int updateReservationAmounts(BenefitFundingRepository.ReservationMutation write);
    int updateEscrowAllocationAmounts(BenefitFundingRepository.EscrowMutation write);
    List<BenefitFundingRepository.LedgerRow> selectLedger(@Param("tenantId") String tenantId);
    List<BenefitFundingRepository.ReconcileItemRow> selectReconcileItems(@Param("tenantId") String tenantId);
    List<BenefitFundingRepository.EscrowReconcileRow> selectEscrowReconcileItems(
            @Param("tenantId") String tenantId);
    List<BenefitFundingRepository.OutboxTypeRow> selectOutboxTypes(@Param("tenantId") String tenantId);
    List<BenefitFundingRepository.ApplicationStateRow> selectApplicationStates(@Param("tenantId") String tenantId);
    int insertCommand(BenefitFundingRepository.CommandWrite write);
    BenefitFundingRepository.CommandRow selectCommandForUpdate(@Param("tenantId") String tenantId,
            @Param("commandId") String commandId);
    int updateCommandCompleted(@Param("tenantId") String tenantId, @Param("commandId") String commandId,
            @Param("responseJson") String responseJson);
    int insertLedger(BenefitFundingRepository.LedgerWrite write);
    int insertOutbox(BenefitFundingRepository.OutboxWrite write);
    int incrementOutboxPosition(@Param("tenantId") String tenantId,
            @Param("aggregateId") String aggregateId, @Param("updatedAt") String updatedAt);
    int insertOutboxPosition(@Param("tenantId") String tenantId,
            @Param("aggregateId") String aggregateId, @Param("updatedAt") String updatedAt);
    Long selectOutboxPosition(@Param("tenantId") String tenantId, @Param("aggregateId") String aggregateId);
}
