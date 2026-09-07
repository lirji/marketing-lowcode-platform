package com.acme.marketing.benefit.infrastructure.persistence;

import com.acme.marketing.benefit.application.AwardIntentRepository;
import com.acme.marketing.benefit.infrastructure.persistence.mapper.AwardIntentMapper;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

/** 基于 MyBatis 的发奖意图仓储适配器。 */
@Repository
public class MybatisAwardIntentRepository implements AwardIntentRepository {
    private final AwardIntentMapper mapper;

    public MybatisAwardIntentRepository(AwardIntentMapper mapper) { this.mapper = mapper; }

    @Override public Optional<BenefitBindingRow> findBenefitBinding(String tenantId, String benefitId,
            long version) { return Optional.ofNullable(mapper.selectBenefitBinding(tenantId, benefitId, version)); }
    @Override public Optional<DedupeStateRow> findDedupeForUpdate(String tenantId, String sourceSystem,
            String sourceRequestId) {
        return Optional.ofNullable(mapper.selectDedupeForUpdate(tenantId, sourceSystem, sourceRequestId));
    }
    @Override public boolean tryBeginEvaluation(EvaluationWrite write) {
        try { return mapper.insertEvaluation(write) == 1; }
        catch (DuplicateKeyException duplicate) { return false; }
    }
    @Override public Optional<ClaimStateRow> findEvaluation(String tenantId, String sourceSystem,
            String sourceRequestId) {
        return Optional.ofNullable(mapper.selectEvaluation(tenantId, sourceSystem, sourceRequestId));
    }
    @Override public int takeEvaluationLease(EvaluationLeaseWrite write) {
        return mapper.updateEvaluationLease(write);
    }
    @Override public int completeEvaluation(EvaluationCompletionWrite write) {
        return mapper.updateEvaluationCompleted(write);
    }
    @Override public int abandonEvaluation(EvaluationOwnerKey key) { return mapper.deleteEvaluation(key); }
    @Override public void saveIntent(IntentWrite write) { one(mapper.insertIntent(write), "保存发奖意图"); }
    @Override public void saveBlock(BlockWrite write) { one(mapper.insertBlock(write), "保存风控拦截"); }
    @Override public Optional<IntentRow> findIntentBySource(String tenantId, String sourceSystem,
            String sourceRequestId) {
        return Optional.ofNullable(mapper.selectIntentBySource(tenantId, sourceSystem, sourceRequestId));
    }
    @Override public Optional<IntentRow> findBlockBySource(String tenantId, String sourceSystem,
            String sourceRequestId) {
        return Optional.ofNullable(mapper.selectBlockBySource(tenantId, sourceSystem, sourceRequestId));
    }
    @Override public Optional<CursorRow> findCursor(String tenantId, String campaignId, String intentId) {
        return Optional.ofNullable(mapper.selectCursor(tenantId, campaignId, intentId));
    }
    @Override public List<IntentRow> findByCampaign(String tenantId, String campaignId, String createdBefore,
            String cursorIntentId, int limit) {
        return mapper.selectByCampaign(tenantId, campaignId, createdBefore, cursorIntentId, limit);
    }
    @Override public void saveExpectedPosition(String tenantId, String aggregateId, long lastSequence,
            String updatedAt) {
        one(mapper.insertExpectedPosition(tenantId, aggregateId, lastSequence, updatedAt), "保存发奖事件序列");
    }
    @Override public void saveExpectedEvent(ExpectedEventWrite write) {
        one(mapper.insertExpectedEvent(write), "保存发奖预期事件");
    }

    private static void one(int affected, String operation) {
        if (affected != 1) throw new IllegalStateException(operation + "的受影响行数不是 1");
    }
}
