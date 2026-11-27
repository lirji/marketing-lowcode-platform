package com.acme.marketing.benefit.infrastructure.persistence.mapper;

import com.acme.marketing.platform.web.archive.AuditArchiveWorkflow.Batch;
import com.acme.marketing.platform.web.archive.AuditArchiveWorkflow.Event;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 资金档案只读取当前申请和原生交易绑定的审计事实。 */
@Mapper
public interface FundingAuditArchiveMapper {
    List<Event> eligible(@Param("tenant") String tenant, @Param("applicationId") String applicationId,
                         @Param("cutoff") Instant cutoff, @Param("limit") int limit);


    /** 切换只锁本批次和审计行，保持预算与账本写入的锁序。 */
    String lockBatchState(@Param("tenant") String tenant, @Param("batchId") String batchId);

    record Grant(String approvalReference, Instant reviewedThrough) {}

    Grant cutoverGrant(@Param("tenant") String tenant, @Param("batchId") String batchId, @Param("lock") boolean lock);

    List<String> lockHolds(@Param("tenant") String tenant, @Param("applicationId") String applicationId);

    List<Event> lockedEvidence(@Param("tenant") String tenant, @Param("applicationId") String applicationId,
                               @Param("resultIds") List<Long> resultIds);

    int retainEffects(@Param("tenant") String tenant, @Param("applicationId") String applicationId,
                      @Param("batchId") String batchId, @Param("resultIds") List<Long> resultIds);

    int removeResults(@Param("tenant") String tenant, @Param("applicationId") String applicationId,
                      @Param("resultIds") List<Long> resultIds);

    int removeAttempts(@Param("tenant") String tenant, @Param("attemptIds") List<Long> attemptIds);

    int markOnlinePurged(@Param("tenant") String tenant, @Param("batchId") String batchId, @Param("version") String version);

    int recordCutover(@Param("tenant") String tenant, @Param("batchId") String batchId,
                      @Param("approval") String approval, @Param("count") int count);

    record Receipt(String approvalReference, int movedCount, Instant movedAt, String recoveryVersion,
                   String recoverySha256, Integer recoveryBytes) {}

    Receipt receipt(@Param("tenant") String tenant, @Param("batchId") String batchId);

    String pendingProof(@Param("tenant") String tenant);

    int sealProof(@Param("tenant") String tenant, @Param("batchId") String batchId, @Param("version") String version,
                  @Param("sha256") String sha256, @Param("bytes") int bytes);


    List<Long> lockExistingResults(@Param("tenant") String tenant, @Param("ids") List<Long> ids);

    List<Long> lockExistingAttempts(@Param("tenant") String tenant, @Param("ids") List<Long> ids);

    int restoreBatch(@Param("tenant") String tenant, @Param("batch") Batch batch);

    int closeRestoredBatch(@Param("tenant") String tenant, @Param("batch") Batch batch);

    record EffectLocator(long effectId, String applicationId, String actionCode, String commandHash, String batchId) {}

    void restoreEffects(@Param("tenant") String tenant, @Param("effects") List<EffectLocator> effects);

    List<EffectLocator> restoredEffects(@Param("tenant") String tenant, @Param("ids") List<Long> ids);

    int restoreReceipt(@Param("tenant") String tenant, @Param("batchId") String batchId, @Param("receipt") Receipt receipt);
}
