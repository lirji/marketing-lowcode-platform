package com.acme.marketing.referral.infrastructure.persistence.mapper;
import com.acme.marketing.referral.application.qualification.ReferralQualificationRepository.Qualification;
import com.acme.marketing.referral.domain.qualification.ReferralProgressTransition.Progress;
import java.time.Instant;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
/** 所有SQL只处理本地主库；没有外部调用、任务父行外键或隐式新事务。 */
@Mapper
public interface ReferralQualificationMapper {
    /** 非锁定元组核验；最终处理时关系在participant之后加锁。 */
    RelationRow relation(Map<String,Object> values);
    /** 每关系独立推进requestedRevision，触发订单水位只作线索。 */
    int enqueue(Map<String,Object> values);
    /** 只锁任务本身。 */
    TaskRow task(Map<String,Object> values);
    /** 领取旧栅栏CAS；明确租期且不获取其他行锁。 */
    int claim(Map<String,Object> values);
    /** 单关系资格读取，可在关系锁后FOR UPDATE。 */
    QualificationRow qualification(Map<String,Object> values);
    /** 只在participant锁下创建人数零值。 */
    int reserveProgress(Map<String,Object> values);
    /** 参与锁内人数当前读。 */
    Progress progress(Map<String,Object> values);
    /** 资格写入受原revision限制；不改永久goal或规则哈希。 */
    int insertQualification(Map<String,Object> values);
    /** 更新既有资格投影。 */
    int updateQualification(Map<String,Object> values);
    /** 人数水位CAS，与资格同事务。 */
    int updateProgress(Map<String,Object> values);
    /** task最终CAS，不得清除等待期间新到达的请求。 */
    int completeTask(Map<String,Object> values);
    /** 去主体化审计。 */
    int audit(Map<String,Object> values);
    /** 内部资格/人数变更意图，无奖励执行语义。 */
    int outbox(Map<String,Object> values);
    /** 内部ORM记录防日志泄露HMAC/密文。 */
    record RelationRow(String tenantId,String relationId,String campaignId,String organizationId,String shopId,String participantId,String tokenId,
            String definitionId,long definitionVersion,long generation,String artifactId,String policyHash,Instant boundAt,Instant qualifyDeadline,
            String consentVersion,String consentHash,String state,String subjectKey,long keyVersion,byte[] cipher,String encryptionKeyId) {
        @Override public String toString(){return "QualificationRelationRow[redacted]";}
    }
    /** 纳秒精确可执行时刻由仓储重建，不交MySQL微秒列作最终裁决。 */
    record TaskRow(String tenantId,String relationId,String participantId,String organizationId,String shopId,long requestedRevision,long processedRevision,
            String status,long availableSeconds,int availableNanos,String leaseOwner,Instant leaseUntil,long fence) {}
    /** 观察期纳秒保留；null由仓储显式处理，避免数据库精度延长授权。 */
    record QualificationRow(String tenantId,String relationId,String participantId,String goalType,String policyHash,String state,String reason,
            boolean counted,boolean everQualified,long revision,String evidenceResourceId,long evidenceVersion,long memberRevision,
            String memberPolicy,String firstOrderPolicy,String proofId,Long dueSeconds,Integer dueNanos,long taskRevision,String memberEvidenceDigest,boolean memberQuarantined,String registrationAnchorDigest) {}
}
