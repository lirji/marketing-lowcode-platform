package com.acme.marketing.benefit.application;

import com.acme.marketing.platform.web.archive.AuditArchiveWorkflow.Batch;
import com.acme.marketing.platform.web.archive.AuditArchiveWorkflow.Event;
import java.time.Instant;
import java.util.List;

/** 资金归档持久化端口限定当前申请、原生交易绑定和精确审计切换。 */
public interface FundingAuditArchiveRepository {
    /** 按明确资金申请寻找满足保留年龄的有界证据。 */
    List<Event> eligible(String tenant, String applicationId,
                         Instant cutoff, int limit);

    /** 切换只锁本批次和审计行，保持预算与账本写入的锁序。 */
    String lockBatchState(String tenant, String batchId);

    /** 记录授权依据和审批覆盖的最后事实时点。 */
    record Grant(String approvalReference, Instant reviewedThrough) {}

    /** 读取当前有效的批次审批，切换时锁定审批行。 */
    Grant cutoverGrant(String tenant, String batchId, boolean lock);

    /** 锁定当前租户和资金申请的有效保全范围。 */
    List<String> lockHolds(String tenant, String applicationId);

    /** 仅锁定固定证据及其尝试，避开资金与组织账本。 */
    List<Event> lockedEvidence(String tenant, String applicationId,
                               List<Long> resultIds);

    /** 在同一事务为成功效果保留幂等查找引用。 */
    int retainEffects(String tenant, String applicationId,
                      String batchId, List<Long> resultIds);

    /** 按批次核验过的结果标识移出在线副本。 */
    int removeResults(String tenant, String applicationId,
                      List<Long> resultIds);

    /** 移出已证明和本批结果成对的尝试。 */
    int removeAttempts(String tenant, List<Long> attemptIds);

    /** 仅推进指定版本且已核验的清单。 */
    int markOnlinePurged(String tenant, String batchId, String version);

    /** 保存同一次事务确认的批次行为。 */
    int recordCutover(String tenant, String batchId,
                      String approval, int count);

    /** 记录切换事实以及外部提交凭证的精确版本。 */
    record Receipt(String approvalReference, int movedCount, Instant movedAt, String recoveryVersion,
                   String recoverySha256, Integer recoveryBytes) {}

    /** 读取已提交事实以便补发或者恢复核对。 */
    Receipt receipt(String tenant, String batchId);

    /** 按来源领取一项尚未发布外部证明的回执。 */
    String pendingProof(String tenant);

    /** 固化首次经过回读校验的外部凭证引用。 */
    int sealProof(String tenant, String batchId, String version,
                  String sha256, int bytes);

    /** 确认旧库中仍存在的原始结果流水。 */
    List<Long> lockExistingResults(String tenant, List<Long> ids);

    /** 确认旧库中与结果对应的原始尝试流水。 */
    List<Long> lockExistingAttempts(String tenant, List<Long> ids);

    /** 从核验过的外部证明重建缺失清单。 */
    int restoreBatch(String tenant, Batch batch);

    /** 推进旧备份中尚未记录在线切换的精确批次。 */
    int closeRestoredBatch(String tenant, Batch batch);

    /** 为已移出的成功效果保存查找所需的最少引用。 */
    record EffectLocator(long effectId, String applicationId, String actionCode, String commandHash, String batchId) {}

    /** 按外部证明复原已移出结果的幂等定位。 */
    void restoreEffects(String tenant, List<EffectLocator> effects);

    /** 验证定位记录与外部凭证的绑定完全一致。 */
    List<EffectLocator> restoredEffects(String tenant, List<Long> ids);

    /** 写入已查明的源库回执与备份域外版本。 */
    int restoreReceipt(String tenant, String batchId, Receipt receipt);
}
