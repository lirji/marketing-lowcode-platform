package com.acme.marketing.referral.infrastructure.persistence;
import com.acme.marketing.platform.crypto.Digests;
import com.acme.marketing.referral.application.qualification.*;
import com.acme.marketing.referral.application.qualification.ReferralQualificationRepository.*;
import com.acme.marketing.referral.application.evidence.ReferralEvidencePreparation.OrderKey;
import com.acme.marketing.referral.application.evidence.ReferralEvidenceRepository.StoredOrder;
import com.acme.marketing.referral.application.evidence.ProtectedReferralEvidencePort.*;
import com.acme.marketing.referral.domain.ReferralRelation;
import com.acme.marketing.referral.domain.qualification.ReferralProgressTransition.Progress;
import com.acme.marketing.referral.infrastructure.persistence.mapper.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;
/** 资格、计数、审计、Outbox和task都加入同一主库事务；密文只读，不在锁内调用KMS。 */
@Repository
public class MybatisReferralQualificationRepository implements ReferralQualificationRepository {
    private final ReferralQualificationMapper mapper;private final ReferralEvidenceMapper evidence;private final Clock clock;private final ObjectMapper json;
    private static final DateTimeFormatter SQL=DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS").withZone(ZoneOffset.UTC);
    /** 注入独立SQL mapper，禁止在任务锁后查询外部系统或带锁关系。 */
    public MybatisReferralQualificationRepository(ReferralQualificationMapper mapper,ReferralEvidenceMapper evidence,Clock clock,ObjectMapper json){this.mapper=mapper;this.evidence=evidence;this.clock=clock;this.json=json;}
    /** 先无锁读取真实关系，再写无FK任务；新BOUND可见本事务刚插入关系。 */
    @Override public void enqueue(Signal signal){
        tx();var relation=readRelation(signal.tenantId(),signal.relationId(),false);if(relation==null || !relation.relation().participantId().equals(signal.participantId()))throw invariant();
        var r=relation.relation();Instant now=clock.instant();var v=key(signal.tenantId(),signal.relationId());v.put("participant",signal.participantId());v.put("organization",r.organizationId());v.put("shop",r.shopId());
        v.put("resource",signal.resourceId());v.put("version",signal.desiredVersion());v.put("reason",signal.reason());time(v,now);mapper.enqueue(v);
        Task actual=lockTask(signal.tenantId(),signal.relationId());if(!actual.participantId().equals(r.participantId()) || !actual.organizationId().equals(r.organizationId()) || !actual.shopId().equals(r.shopId()))throw invariant();
    }
    /** 仅task行锁；无join因此无父行隐式锁。 */
    @Override public Task lockTask(String tenant,String relation){tx();return task(mapper.task(key(tenant,relation)));}
    /** 租约截止向下取微秒，不因数据库舍入延长旧worker授权。 */
    @Override public Task claim(Task before,String owner,Instant now,Instant until){tx();until=until.truncatedTo(ChronoUnit.MICROS);if(!until.isAfter(now))throw invariant();var v=key(before.tenantId(),before.relationId());v.put("owner",owner);v.put("fence",before.fence());v.put("requested",before.requestedRevision());v.put("until",SQL.format(until));time(v,now);one(mapper.claim(v));return Objects.requireNonNull(lockTask(before.tenantId(),before.relationId()));}
    /** 固定字段和HMAC从永久首绑读取；不得把task中的提示当作业务关系。 */
    @Override public Relation readRelation(String tenant,String relation,boolean lock){if(lock)tx();var v=key(tenant,relation);v.put("lock",lock);var x=mapper.relation(v);if(x==null)return null;
        return new Relation(new ReferralRelation(x.tenantId(),x.relationId(),x.campaignId(),x.organizationId(),x.shopId(),x.participantId(),x.tokenId(),x.definitionId(),x.definitionVersion(),x.generation(),x.artifactId(),x.policyHash(),x.boundAt(),x.qualifyDeadline(),x.consentVersion(),x.consentHash(),x.state()),x.subjectKey(),x.keyVersion(),x.cipher(),x.encryptionKeyId());}
    /** 只锁存在的订单，缺事实返回null；不创建空current污染V4完整性。 */
    @Override public StoredOrder lockExistingOrder(OrderKey k){tx();var x=evidence.current(Map.of("tenant",k.tenantId(),"source",k.sourceSystem(),"orderId",k.orderId(),"lock",true));if(x==null)return null;if(x.rowVersion()<=0)throw invariant();
        return new StoredOrder(k,x.resourceId(),x.rowVersion(),new Sealed(new Header(k,x.subject(),x.keyVersion(),x.organization(),x.shop(),x.revision(),Purpose.CURRENT,x.quarantined()),x.cipher(),x.keyId(),x.businessDigest()));}
    /** 首次null不是已通过；应用写PENDING或可信裁决后再保存。 */
    @Override public Qualification qualification(String tenant,String relation,boolean lock){if(lock)tx();var v=key(tenant,relation);v.put("lock",lock);var q=mapper.qualification(v);return q==null?null:new Qualification(q.tenantId(),q.relationId(),q.participantId(),q.goalType(),q.policyHash(),q.state(),q.reason(),q.counted(),q.everQualified(),q.revision(),q.evidenceResourceId(),q.evidenceVersion(),q.memberRevision(),q.memberPolicy(),q.firstOrderPolicy(),q.proofId(),q.dueSeconds()==null?null:Instant.ofEpochSecond(q.dueSeconds(),q.dueNanos()),q.taskRevision(),q.memberEvidenceDigest(),q.memberQuarantined(),q.registrationAnchorDigest());}
    /** participant已锁下的零值初始化，不是线上数据修复入口。 */
    @Override public Progress progress(String tenant,String participant,Instant now){tx();var v=new HashMap<String,Object>();v.put("tenant",tenant);v.put("participant",participant);time(v,now);mapper.reserveProgress(v);return Objects.requireNonNull(mapper.progress(v));}
    /** 任一SQL或Outbox失败均回滚人数、资格与租约完成。 */
    @Override public void save(Task task,Qualification previous,Qualification next,Progress before,Progress after,String actor,String trace,Instant now,Instant retryAt){
        tx();var v=key(task.tenantId(),task.relationId());time(v,now);v.put("q",next);v.put("dueSeconds",next.dueAt()==null?null:next.dueAt().getEpochSecond());v.put("dueNanos",next.dueAt()==null?null:next.dueAt().getNano());
        if(previous==null)one(mapper.insertQualification(v));else{v.put("previousRevision",previous.revision());one(mapper.updateQualification(v));}
        if(!after.equals(before)){v.put("p",after);v.put("progressRevision",before.revision());one(mapper.updateProgress(v));}
        event(v,task.participantId(),next.relationId(),next.revision(),"QUALIFICATION_CHANGED",next.reason(),actor,trace,Map.of("schemaVersion",1,"relationId",next.relationId(),"qualificationRevision",next.revision(),"state",next.state(),"counted",next.counted(),"everQualified",next.everQualified(),"rewardState","SEE_REWARD_LEDGER"));
        if(!after.equals(before))event(v,task.participantId(),task.participantId(),after.revision(),"PROGRESS_CHANGED","QUALIFICATION_REEVALUATED",actor,trace,Map.of("schemaVersion",1,"participantId",task.participantId(),"progressRevision",after.revision(),"validCount",after.validCount(),"everQualifiedCount",after.everQualifiedCount(),"rewardState","SEE_REWARD_LEDGER"));
        Instant available=retryAt==null?now:retryAt;v.put("requested",task.requestedRevision());v.put("fence",task.fence());v.put("owner",task.leaseOwner());v.put("status",retryAt==null?"DONE":"PENDING");v.put("available",SQL.format(available));v.put("seconds",available.getEpochSecond());v.put("nanos",available.getNano());one(mapper.completeTask(v));
    }
    private void event(Map<String,Object> v,String participant,String aggregate,long revision,String type,String reason,String actor,String trace,Map<String,Object> data){String payload=json.writeValueAsString(data);v.put("participant",participant);v.put("aggregate",aggregate);v.put("revision",revision);v.put("type",type);v.put("reason",reason);v.put("actor",actor);v.put("trace",trace);v.put("payload",payload);v.put("hash",Digests.sha256Hex(payload));v.put("auditId",UUID.randomUUID().toString());v.put("eventId",UUID.randomUUID().toString());one(mapper.audit(v));one(mapper.outbox(v));}
    private static Task task(ReferralQualificationMapper.TaskRow x){return x==null?null:new Task(x.tenantId(),x.relationId(),x.participantId(),x.organizationId(),x.shopId(),x.requestedRevision(),x.processedRevision(),x.status(),Instant.ofEpochSecond(x.availableSeconds(),x.availableNanos()),x.leaseOwner(),x.leaseUntil(),x.fence());}
    private static HashMap<String,Object> key(String tenant,String relation){var v=new HashMap<String,Object>();v.put("tenant",tenant);v.put("relation",relation);return v;}
    private static void time(Map<String,Object> v,Instant now){v.put("now",SQL.format(now));v.put("seconds",now.getEpochSecond());v.put("nanos",now.getNano());}
    private static void tx(){if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("qualification requires owning transaction");}
    private static void one(int count){if(count!=1)throw invariant();}
    private static IllegalStateException invariant(){return new IllegalStateException("qualification persistence invariant failed");}
}
