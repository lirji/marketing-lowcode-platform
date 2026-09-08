package com.acme.marketing.referral.application.qualification;
import com.acme.marketing.referral.domain.ReferralRelation;
import com.acme.marketing.referral.domain.qualification.ReferralProgressTransition.Progress;
import com.acme.marketing.referral.application.evidence.ReferralEvidencePreparation.OrderKey;
import com.acme.marketing.referral.application.evidence.ReferralEvidenceRepository.StoredOrder;
import java.time.Instant;
/** 内部资格任务与投影仓储；任务无父行外键，避免证据fanout与资格锁序成环。 */
public interface ReferralQualificationRepository extends ReferralProjectionEnqueuePort {
    /** 只锁单task的短事务，不同时持有关系或订单锁。 */
    Task lockTask(String tenant,String relation);
    /** 在task锁下领取显式租约，旧worker只能靠fence+requestedRevision最终CAS。 */
    Task claim(Task before,String owner,Instant now,Instant until);
    /** 事务外预读或在participant之后锁定完整关系及密文主体。 */
    Relation readRelation(String tenant,String relation,boolean lock);
    /** 已有订单只读加锁，不创建V4空占位；必须位于关系后、task前。 */
    StoredOrder lockExistingOrder(OrderKey key);
    /** 同relation锁保护永久goal，首次未接可信计划时goal允许空但不能计数。 */
    Qualification qualification(String tenant,String relation,boolean lock);
    /** participant锁下初始化并锁定人数。 */
    Progress progress(String tenant,String participant,Instant now);
    /** 资格、人数、审计、Outbox、任务完成必须在同一调用方事务。 */
    void save(Task task,Qualification previous,Qualification next,Progress before,Progress after,String actor,String trace,Instant now,Instant retryAt);
    /** 只允许内部逐键调用；调度器/HTTP入口不在本切片自动启用。 */
    record Task(String tenantId,String relationId,String participantId,String organizationId,String shopId,long requestedRevision,
            long processedRevision,String status,Instant availableAt,String leaseOwner,Instant leaseUntil,long fence) {}
    /** 密文及HMAC不进入服务返回/日志。 */
    record Relation(ReferralRelation relation,String subjectKey,long keyVersion,byte[] cipher,String encryptionKeyId) {
        public Relation { cipher=cipher.clone(); }
        @Override public byte[] cipher(){return cipher.clone();}
        @Override public String toString(){return "QualificationRelation[redacted]";}
    }
    /** 单关系唯一当前资格；everQualified永久记忆不随退款清除，proof仅脱敏引用。 */
    record Qualification(String tenantId,String relationId,String participantId,String goalType,String policyHash,String state,String reason,
            boolean counted,boolean everQualified,long revision,String evidenceResourceId,long evidenceVersion,long memberRevision,
            String memberPolicy,String firstOrderPolicy,String proofId,Instant dueAt,long taskRevision,String memberEvidenceDigest,boolean memberQuarantined,String registrationAnchorDigest) {}
}
