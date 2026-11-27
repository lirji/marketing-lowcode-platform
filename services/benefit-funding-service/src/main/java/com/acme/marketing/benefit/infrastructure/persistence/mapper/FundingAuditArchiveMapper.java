package com.acme.marketing.benefit.infrastructure.persistence.mapper;

import com.acme.marketing.benefit.application.FundingAuditArchiveRepository;
import com.acme.marketing.platform.web.archive.AuditArchiveWorkflow.Batch;
import com.acme.marketing.platform.web.archive.AuditArchiveWorkflow.Event;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 资金档案只读取当前申请和原生交易绑定的审计事实。 */
@Mapper
public interface FundingAuditArchiveMapper extends FundingAuditArchiveRepository {
    @Override
    List<Event> eligible(@Param("tenant") String tenant, @Param("applicationId") String applicationId,
                         @Param("cutoff") Instant cutoff, @Param("limit") int limit);

    /** 切换只锁本批次和审计行，保持预算与账本写入的锁序。 */
    @Override
    String lockBatchState(@Param("tenant") String tenant, @Param("batchId") String batchId);

    @Override
    Grant cutoverGrant(@Param("tenant") String tenant, @Param("batchId") String batchId, @Param("lock") boolean lock);

    @Override
    List<String> lockHolds(@Param("tenant") String tenant, @Param("applicationId") String applicationId);

    @Override
    List<Event> lockedEvidence(@Param("tenant") String tenant, @Param("applicationId") String applicationId,
                               @Param("resultIds") List<Long> resultIds);

    @Override
    int retainEffects(@Param("tenant") String tenant, @Param("applicationId") String applicationId,
                      @Param("batchId") String batchId, @Param("resultIds") List<Long> resultIds);

    @Override
    int removeResults(@Param("tenant") String tenant, @Param("applicationId") String applicationId,
                      @Param("resultIds") List<Long> resultIds);

    @Override
    int removeAttempts(@Param("tenant") String tenant, @Param("attemptIds") List<Long> attemptIds);

    @Override
    int markOnlinePurged(@Param("tenant") String tenant, @Param("batchId") String batchId, @Param("version") String version);

    @Override
    int recordCutover(@Param("tenant") String tenant, @Param("batchId") String batchId,
                      @Param("approval") String approval, @Param("count") int count);

    @Override
    Receipt receipt(@Param("tenant") String tenant, @Param("batchId") String batchId);

    @Override
    String pendingProof(@Param("tenant") String tenant);

    @Override
    int sealProof(@Param("tenant") String tenant, @Param("batchId") String batchId, @Param("version") String version,
                  @Param("sha256") String sha256, @Param("bytes") int bytes);

    @Override
    List<Long> lockExistingResults(@Param("tenant") String tenant, @Param("ids") List<Long> ids);

    @Override
    List<Long> lockExistingAttempts(@Param("tenant") String tenant, @Param("ids") List<Long> ids);

    @Override
    int restoreBatch(@Param("tenant") String tenant, @Param("batch") Batch batch);

    @Override
    int closeRestoredBatch(@Param("tenant") String tenant, @Param("batch") Batch batch);

    @Override
    void restoreEffects(@Param("tenant") String tenant, @Param("effects") List<EffectLocator> effects);

    @Override
    List<EffectLocator> restoredEffects(@Param("tenant") String tenant, @Param("ids") List<Long> ids);

    @Override
    int restoreReceipt(@Param("tenant") String tenant, @Param("batchId") String batchId, @Param("receipt") Receipt receipt);
}
