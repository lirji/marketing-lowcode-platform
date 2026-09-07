package com.acme.marketing.benefit.infrastructure.persistence;

import com.acme.marketing.benefit.application.BenefitFundingRepository;
import com.acme.marketing.benefit.domain.ResourceAccount;
import com.acme.marketing.benefit.infrastructure.persistence.mapper.BenefitFundingMapper;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

/** 基于 MyBatis 的权益资金主账仓储适配器。 */
@Repository
public class MybatisBenefitFundingRepository implements BenefitFundingRepository {
    private final BenefitFundingMapper mapper;

    public MybatisBenefitFundingRepository(BenefitFundingMapper mapper) { this.mapper = mapper; }

    @Override public void saveAccount(AccountWrite write) { one(mapper.insertAccount(write), "保存资源账户"); }
    @Override public List<ResourceAccount> findAccounts(String tenantId, boolean ordered) {
        return mapper.selectAccounts(tenantId, ordered);
    }
    @Override public Optional<ResourceAccount> findAccount(String tenantId, String resourceKey, boolean lock) {
        return Optional.ofNullable(mapper.selectAccount(tenantId, resourceKey, lock));
    }
    @Override public int updateAccountFence(AccountFenceWrite write) { return mapper.updateAccountFence(write); }
    @Override public int updateAccountBalance(AccountBalanceWrite write) { return mapper.updateAccountBalance(write); }
    @Override public List<BenefitRow> findLatestBenefits(String tenantId) { return mapper.selectLatestBenefits(tenantId); }
    @Override public Optional<BenefitRow> findLatestBenefit(String tenantId, String benefitId) {
        return Optional.ofNullable(mapper.selectLatestBenefit(tenantId, benefitId));
    }
    @Override public Optional<ReleaseBenefitRow> findReleaseBenefit(String tenantId, String benefitId,
            long version) { return Optional.ofNullable(mapper.selectReleaseBenefit(tenantId, benefitId, version)); }
    @Override public void ensureBenefitHead(String tenantId, String benefitId) {
        // 自赋值 upsert 在已存在时可能报告 0 行，后续加锁读取才是权威结果。
        mapper.upsertBenefitHead(tenantId, benefitId);
    }
    @Override public Optional<Long> findBenefitHeadForUpdate(String tenantId, String benefitId) {
        return Optional.ofNullable(mapper.selectBenefitHeadForUpdate(tenantId, benefitId));
    }
    @Override public int countResource(String tenantId, String resourceKey) {
        return mapper.countResource(tenantId, resourceKey);
    }
    @Override public void saveBenefit(BenefitWrite write) { one(mapper.insertBenefit(write), "保存权益定义"); }
    @Override public void updateBenefitHead(String tenantId, String benefitId, long version) {
        one(mapper.updateBenefitHead(tenantId, benefitId, version), "更新权益定义版本头");
    }
    @Override public int countBuckets(String tenantId, String resourceKey) {
        return mapper.countBuckets(tenantId, resourceKey);
    }
    @Override public int updateBucketFence(BucketFenceWrite write) { return mapper.updateBucketFence(write); }
    @Override public void saveBucket(BucketWrite write) { one(mapper.insertBucket(write), "保存预算分桶"); }
    @Override public List<Integer> findEligibleBucketIds(String tenantId, String resourceKey,
            long fencingEpoch, long amount) {
        return mapper.selectEligibleBucketIds(tenantId, resourceKey, fencingEpoch, amount);
    }
    @Override public int reserveBucket(BucketReserveWrite write) { return mapper.reserveBucket(write); }
    @Override public Optional<Long> findBucketVersion(String tenantId, String resourceKey, int bucketId) {
        return Optional.ofNullable(mapper.selectBucketVersion(tenantId, resourceKey, bucketId));
    }
    @Override public List<BudgetBucketRow> findBucketsForUpdate(String tenantId, String resourceKey) {
        return mapper.selectBucketsForUpdate(tenantId, resourceKey);
    }
    @Override public Optional<BudgetBucketRow> findBucketForUpdate(String tenantId, String resourceKey,
            int bucketId) { return Optional.ofNullable(mapper.selectBucketForUpdate(tenantId, resourceKey, bucketId)); }
    @Override public int updateBucketBalance(BucketBalanceWrite write) { return mapper.updateBucketBalance(write); }
    @Override public BucketTotals summarizeBuckets(String tenantId, String resourceKey) {
        return mapper.selectBucketTotals(tenantId, resourceKey);
    }
    @Override public void saveJourneyGrant(JourneyGrantWrite write) {
        one(mapper.insertJourneyGrant(write), "保存旅程权益发放");
    }
    @Override public boolean trySaveApplication(ApplicationWrite write) {
        return attempt(() -> mapper.insertApplication(write));
    }
    @Override public void saveReservationItem(ReservationItemWrite write) {
        one(mapper.insertReservationItem(write), "保存资源预留项");
    }
    @Override public void saveEscrowAllocation(EscrowAllocationWrite write) {
        one(mapper.insertEscrowAllocation(write), "保存预算分桶预留");
    }
    @Override public List<ExpiredApplicationRow> findExpiredApplications(String tenantId, String now, int limit) {
        return mapper.selectExpiredApplications(tenantId, now, limit);
    }
    @Override public int expireApplication(String tenantId, String applicationId, String updatedAt) {
        return mapper.expireApplication(tenantId, applicationId, updatedAt);
    }
    @Override public Optional<Integer> findExpiryAttemptsForUpdate(String tenantId, String applicationId) {
        return Optional.ofNullable(mapper.selectExpiryAttemptsForUpdate(tenantId, applicationId));
    }
    @Override public int updateExpiryFailure(ExpiryFailureWrite write) { return mapper.updateExpiryFailure(write); }
    @Override public int updateApplicationState(String tenantId, String applicationId, String stateName,
            String updatedAt) { return mapper.updateApplicationState(tenantId, applicationId, stateName, updatedAt); }
    @Override public Optional<StoredApplicationRow> findApplicationForUpdate(String tenantId,
            String applicationId) {
        return Optional.ofNullable(mapper.selectApplicationForUpdate(tenantId, applicationId));
    }
    @Override public List<ReservationItemRow> findReservationItems(String tenantId, String applicationId,
            boolean lock) { return mapper.selectReservationItems(tenantId, applicationId, lock); }
    @Override public Optional<ApplicationRow> findApplication(String tenantId, String applicationId) {
        return Optional.ofNullable(mapper.selectApplication(tenantId, applicationId));
    }
    @Override public List<ItemRow> findItems(String tenantId, String applicationId) {
        return mapper.selectItems(tenantId, applicationId);
    }
    @Override public List<EscrowAllocationRow> findEscrowAllocationsForUpdate(String tenantId,
            String applicationId, String resourceKey) {
        return mapper.selectEscrowAllocationsForUpdate(tenantId, applicationId, resourceKey);
    }
    @Override public int updateReservationAmounts(ReservationMutation write) {
        return mapper.updateReservationAmounts(write);
    }
    @Override public int updateEscrowAllocationAmounts(EscrowMutation write) {
        return mapper.updateEscrowAllocationAmounts(write);
    }
    @Override public List<LedgerRow> findLedger(String tenantId) { return mapper.selectLedger(tenantId); }
    @Override public List<ReconcileItemRow> findReconcileItems(String tenantId) {
        return mapper.selectReconcileItems(tenantId);
    }
    @Override public List<EscrowReconcileRow> findEscrowReconcileItems(String tenantId) {
        return mapper.selectEscrowReconcileItems(tenantId);
    }
    @Override public List<OutboxTypeRow> findOutboxTypes(String tenantId) {
        return mapper.selectOutboxTypes(tenantId);
    }
    @Override public List<ApplicationStateRow> findApplicationStates(String tenantId) {
        return mapper.selectApplicationStates(tenantId);
    }
    @Override public boolean tryBeginCommand(CommandWrite write) {
        return attempt(() -> mapper.insertCommand(write));
    }
    @Override public Optional<CommandRow> findCommandForUpdate(String tenantId, String commandId) {
        return Optional.ofNullable(mapper.selectCommandForUpdate(tenantId, commandId));
    }
    @Override public void completeCommand(String tenantId, String commandId, String responseJson) {
        one(mapper.updateCommandCompleted(tenantId, commandId, responseJson), "完成权益命令");
    }
    @Override public void saveLedger(LedgerWrite write) { one(mapper.insertLedger(write), "保存资金流水"); }
    @Override public void saveOutbox(OutboxWrite write) { one(mapper.insertOutbox(write), "保存权益事件"); }
    @Override public int incrementOutboxPosition(String tenantId, String aggregateId, String updatedAt) {
        return mapper.incrementOutboxPosition(tenantId, aggregateId, updatedAt);
    }
    @Override public boolean tryCreateOutboxPosition(String tenantId, String aggregateId, String updatedAt) {
        return attempt(() -> mapper.insertOutboxPosition(tenantId, aggregateId, updatedAt));
    }
    @Override public Optional<Long> findOutboxPosition(String tenantId, String aggregateId) {
        return Optional.ofNullable(mapper.selectOutboxPosition(tenantId, aggregateId));
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
