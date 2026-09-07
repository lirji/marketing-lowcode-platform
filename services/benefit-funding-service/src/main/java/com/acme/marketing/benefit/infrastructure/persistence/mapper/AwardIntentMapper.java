package com.acme.marketing.benefit.infrastructure.persistence.mapper;

import com.acme.marketing.benefit.application.AwardIntentRepository;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 发奖意图 MyBatis Mapper；SQL 统一维护在同名 XML 中。 */
@Mapper
public interface AwardIntentMapper {
    AwardIntentRepository.BenefitBindingRow selectBenefitBinding(@Param("tenantId") String tenantId,
            @Param("benefitId") String benefitId, @Param("version") long version);
    AwardIntentRepository.DedupeStateRow selectDedupeForUpdate(@Param("tenantId") String tenantId,
            @Param("sourceSystem") String sourceSystem, @Param("sourceRequestId") String sourceRequestId);
    int insertEvaluation(AwardIntentRepository.EvaluationWrite write);
    AwardIntentRepository.ClaimStateRow selectEvaluation(@Param("tenantId") String tenantId,
            @Param("sourceSystem") String sourceSystem, @Param("sourceRequestId") String sourceRequestId);
    int updateEvaluationLease(AwardIntentRepository.EvaluationLeaseWrite write);
    int updateEvaluationCompleted(AwardIntentRepository.EvaluationCompletionWrite write);
    int deleteEvaluation(AwardIntentRepository.EvaluationOwnerKey key);
    int insertIntent(AwardIntentRepository.IntentWrite write);
    int insertBlock(AwardIntentRepository.BlockWrite write);
    AwardIntentRepository.IntentRow selectIntentBySource(@Param("tenantId") String tenantId,
            @Param("sourceSystem") String sourceSystem, @Param("sourceRequestId") String sourceRequestId);
    AwardIntentRepository.IntentRow selectBlockBySource(@Param("tenantId") String tenantId,
            @Param("sourceSystem") String sourceSystem, @Param("sourceRequestId") String sourceRequestId);
    AwardIntentRepository.CursorRow selectCursor(@Param("tenantId") String tenantId,
            @Param("campaignId") String campaignId, @Param("intentId") String intentId);
    List<AwardIntentRepository.IntentRow> selectByCampaign(@Param("tenantId") String tenantId,
            @Param("campaignId") String campaignId, @Param("createdBefore") String createdBefore,
            @Param("cursorIntentId") String cursorIntentId, @Param("limit") int limit);
    int insertExpectedPosition(@Param("tenantId") String tenantId, @Param("aggregateId") String aggregateId,
            @Param("lastSequence") long lastSequence, @Param("updatedAt") String updatedAt);
    int insertExpectedEvent(AwardIntentRepository.ExpectedEventWrite write);
}
