package com.acme.marketing.referral.application.qualification;
import com.acme.marketing.platform.identity.TenantScope;
import com.acme.marketing.platform.error.ConflictException;
import com.acme.marketing.referral.*;
import com.acme.marketing.referral.application.*;
import com.acme.marketing.referral.application.evidence.*;
import com.acme.marketing.referral.application.evidence.ReferralEvidenceRepository.StoredOrder;
import com.acme.marketing.referral.application.qualification.ReferralQualificationRepository.*;
import com.acme.marketing.referral.application.qualification.ReferralQualificationPermitPort.*;
import com.acme.marketing.referral.domain.ReferralParticipant;
import com.acme.marketing.referral.domain.qualification.ReferralProgressTransition;
import com.acme.marketing.referral.domain.qualification.ReferralProgressTransition.*;
import java.io.Serial;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
/**
 * 内部逐键资格重算；没有公开HTTP或自动Worker。先短事务领task，再事务外权威查询/解密，最后统一锁后重算。
 * 资格有效仅更新人数，不代替奖励在线授权；退款与派发之间的陈旧窗口仍需发奖水位门禁。
 */
@Service
public class ReferralQualificationService {
    private final com.acme.marketing.referral.application.reward.ReferralRewardProjectionPort rewards;
    private final ReferralQualificationRepository repository;private final ReferralInviteRepository participants;private final ReferralRepository anchors;
    private final ReferralEvidenceRepository evidence;private final ProtectedReferralEvidencePort protection;private final ReferralQualificationPermitPort permits;
    private final Clock clock;private final long keyVersion,leaseSeconds,retrySeconds,permitSeconds;private final TransactionTemplate tx;
    /** 所有生产时限必须显式配置且默认0拒绝，不内置真实来源或模拟资格。 */
    public ReferralQualificationService(ReferralQualificationRepository repository,ReferralInviteRepository participants,ReferralRepository anchors,
            ReferralEvidenceRepository evidence,ProtectedReferralEvidencePort protection,ReferralQualificationPermitPort permits,Clock clock,PlatformTransactionManager manager,
            com.acme.marketing.referral.application.reward.ReferralRewardProjectionPort rewards,
            @Value("${marketing.referral.subject-key-version:0}") long keyVersion,@Value("${marketing.referral.qualification.lease-seconds:0}") long leaseSeconds,
            @Value("${marketing.referral.qualification.retry-seconds:0}") long retrySeconds,@Value("${marketing.referral.qualification.permit-max-seconds:0}") long permitSeconds){
        this.rewards=Objects.requireNonNull(rewards);this.repository=repository;this.participants=participants;this.anchors=anchors;this.evidence=evidence;this.protection=protection;this.permits=permits;this.clock=clock;
        this.keyVersion=keyVersion;this.leaseSeconds=leaseSeconds;this.retrySeconds=retrySeconds;this.permitSeconds=permitSeconds;
        tx=new TransactionTemplate(manager);tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);tx.setTimeout(5);
    }
    /** 无任务/未到期/其他租约持有时返回null；缺权威来源仅持久PENDING，不生成资格成功。 */
    public Qualification process(TenantScope scope,String relationId,String worker,String trace){
        if(TransactionSynchronizationManager.isActualTransactionActive())throw rejected("REFERRAL_OUTER_TRANSACTION_FORBIDDEN");
        Objects.requireNonNull(scope);scope.requirePermission("referral:evaluate");text(relationId,64);text(worker,128);text(trace,64);text(scope.actorId(),128);
        if(keyVersion<=0 || leaseSeconds<=0 || retrySeconds<=0 || permitSeconds<=0)throw rejected("REFERRAL_QUALIFICATION_UNAVAILABLE");
        String tenant=scope.tenantId().value();Task task=tx.execute(status->{Task row=repository.lockTask(tenant,relationId);if(row==null)return null;scope(scope,row);
            Instant now=clock.instant();if(row.status().equals("DONE") || now.isBefore(row.availableAt()) || (row.status().equals("PROCESSING") && row.leaseUntil()!=null && now.isBefore(row.leaseUntil())))return null;
            return repository.claim(row,worker,now,now.plusSeconds(leaseSeconds));});
        if(task==null)return null;
        for(int attempt=0;attempt<3;attempt++){
            Prepared ready=prepare(scope,task);
            try{return tx.execute(status->commit(scope,task,ready,trace));}catch(RetryException changed){/* 回滚后重新事务外查询，不持锁调用来源。 */}
        }
        throw rejected("REFERRAL_QUALIFICATION_RETRY");
    }
    private Prepared prepare(TenantScope scope,Task task){
        Relation relation=repository.readRelation(task.tenantId(),task.relationId(),false);if(relation==null)throw rejected("REFERRAL_RELATION_REPAIR_REQUIRED");
        var owner=participants.participant(task.tenantId(),task.participantId(),false);if(owner==null)throw rejected("REFERRAL_RELATION_REPAIR_REQUIRED");
        Binding binding=new Binding(relation.relation(),owner.participant(),relation.subjectKey(),relation.keyVersion());validate(scope,task,binding);
        Permit permit;try{permit=permits.prepare(scope,binding,relation.cipher(),relation.encryptionKeyId());}catch(RuntimeException unavailable){permit=null;}
        if(!valid(permit,binding,clock.instant()))permit=null;
        StoredOrder order=null;ReferralOrderEvidence.State state=null;
        if(permit!=null && permit.plan().goalType()==ReferralPlan.GoalType.FIRST_ORDER_SETTLED && permit.order()!=null){
            order=evidence.readOrder(permit.order());
            if(order!=null && order.state()!=null && header(binding,order)){
                try{state=protection.openState(order.state());if(!content(order,state))state=null;}catch(RuntimeException unavailable){state=null;}
            }
        }
        return new Prepared(relation,binding,permit,order,state);
    }
    private Qualification commit(TenantScope scope,Task task,Prepared ready,String trace){
        Long anchor=anchors.subjectIndexVersion(task.tenantId());if(anchor==null || anchor!=keyVersion)throw rejected("REFERRAL_SUBJECT_INDEX_UNAVAILABLE");
        var owner=participants.participant(task.tenantId(),task.participantId(),true);if(owner==null)throw rejected("REFERRAL_RELATION_REPAIR_REQUIRED");
        Relation actualRelation=repository.readRelation(task.tenantId(),task.relationId(),true);if(actualRelation==null)throw rejected("REFERRAL_RELATION_REPAIR_REQUIRED");
        Binding binding=new Binding(actualRelation.relation(),owner.participant(),actualRelation.subjectKey(),actualRelation.keyVersion());validate(scope,task,binding);
        if(!binding.equals(ready.binding()) || !Arrays.equals(actualRelation.cipher(),ready.relation().cipher()) || !actualRelation.encryptionKeyId().equals(ready.relation().encryptionKeyId()))throw new RetryException();
        Qualification old=repository.qualification(task.tenantId(),task.relationId(),true);
        Progress progress=repository.progress(task.tenantId(),task.participantId(),clock.instant());
        StoredOrder actual=null;if(ready.permit()!=null && ready.permit().order()!=null){actual=repository.lockExistingOrder(ready.permit().order());if(!sameOrder(ready.order(),actual))throw new RetryException();}
        // 最后取task锁后才读原始时钟；任何等待均不能复活过期许可或旧worker。
        Task locked=repository.lockTask(task.tenantId(),task.relationId());Instant now=clock.instant();if(!owns(task,locked,now))throw rejected("REFERRAL_QUALIFICATION_LEASE_LOST");
        Permit permit=valid(ready.permit(),binding,now)?ready.permit():null;
        String goal=permit==null?(old==null?null:old.goalType()):permit.plan().goalType().name();
        if(old!=null && (!old.participantId().equals(task.participantId()) || !old.policyHash().equals(binding.relation().policyHash()) || (old.goalType()!=null && !old.goalType().equals(goal))))throw rejected("REFERRAL_QUALIFICATION_FIXED_PLAN_CONFLICT");
        Verdict verdict=decide(binding,permit,actual,ready.state(),now);
        // 会员高水位不能被旧查询复活；同revision异内容/固定口径漂移永久隔离，不自动恢复。
        boolean memberQuarantined=old!=null && old.memberQuarantined();
        boolean preserveMember=permit==null;
        if(old!=null && old.memberRevision()>0 && permit!=null){
            if(!Objects.equals(old.memberPolicy(),permit.memberPolicy()) || (old.memberRevision()==permit.memberRevision() && !Objects.equals(old.memberEvidenceDigest(),permit.memberEvidenceDigest()))){memberQuarantined=true;preserveMember=true;}
            else if(permit.memberRevision()<old.memberRevision()){preserveMember=true;verdict=new Verdict("PENDING","MEMBER_REVISION_STALE",null);}
        }
        if(old!=null && old.registrationAnchorDigest()!=null && permit!=null && !Objects.equals(old.registrationAnchorDigest(),permit.registrationAnchorDigest()))memberQuarantined=true;
        if(memberQuarantined){preserveMember=true;verdict=new Verdict("REVIEW","MEMBER_EVIDENCE_QUARANTINED",null);}
        long memberRevision=preserveMember?(old==null?0:old.memberRevision()):permit.memberRevision();
        String memberPolicy=preserveMember?(old==null?null:old.memberPolicy()):permit.memberPolicy();
        String memberDigest=preserveMember?(old==null?null:old.memberEvidenceDigest()):permit.memberEvidenceDigest();
        var countKey=new Key(task.tenantId(),task.participantId(),task.relationId());var flags=old==null?null:new Flags(countKey,old.counted(),old.everQualified());
        Mutation counts=ReferralProgressTransition.apply(countKey,flags,progress,verdict.state().equals("ELIGIBLE"));
        String resource=actual==null?(old==null?null:old.evidenceResourceId()):actual.resourceId();long version=actual==null?(old==null?0:old.evidenceVersion()):actual.rowVersion();
        Qualification next=new Qualification(task.tenantId(),task.relationId(),task.participantId(),goal,binding.relation().policyHash(),verdict.state(),verdict.reason(),counts.next().counted(),counts.next().everQualified(),old==null?1:Math.addExact(old.revision(),1),resource,version,
                memberRevision,memberPolicy,permit==null?null:permit.firstOrderPolicy(),permit==null?null:permit.proofId(),verdict.dueAt(),task.requestedRevision(),memberDigest,memberQuarantined,old!=null && old.registrationAnchorDigest()!=null?old.registrationAnchorDigest():permit==null?null:permit.registrationAnchorDigest());
        Instant retry=verdict.state().equals("PENDING")?(verdict.dueAt()==null?now.plusSeconds(retrySeconds):verdict.dueAt()):null;
        repository.save(locked,old,next,progress,counts.progress(),scope.actorId(),trace,now,retry);
        rewards.reconcile(binding,permit==null?null:permit.plan(),next,counts.progress(),scope.actorId(),trace,now);
        // INSERT/Outbox索引也可能等待；任何过期必须整体回滚，不能残留刚写的ELIGIBLE。
        Instant completedAt=clock.instant();if(!owns(task,locked,completedAt) || (permit!=null && !valid(permit,binding,completedAt)))throw rejected("REFERRAL_QUALIFICATION_EXPIRED_BEFORE_COMMIT");
        return next;
    }
    private Verdict decide(Binding binding,Permit permit,StoredOrder order,ReferralOrderEvidence.State state,Instant now){
        if(permit==null)return new Verdict("PENDING","AUTHORITY_UNAVAILABLE",null);
        if(!binding.participant().state().equals("ACTIVE") || !binding.relation().state().equals("BOUND"))return new Verdict("PENDING","ACTIVITY_NOT_ACTIVE",null);
        if(permit.risk()!=Risk.ALLOW)return new Verdict(permit.risk()==Risk.REJECT?"INELIGIBLE":permit.risk()==Risk.REVIEW?"REVIEW":"PENDING","RISK_"+permit.risk().name(),null);
        ReferralPolicyEvaluator.Evidence facts;
        if(permit.plan().goalType()==ReferralPlan.GoalType.REGISTERED_NEW_CUSTOMER){facts=new ReferralPolicyEvaluator.Evidence(true,permit.newCustomer(),ReferralPolicyEvaluator.Fact.UNKNOWN,permit.registeredAt(),permit.registrationReceivedAt(),0,0,permit.plan().currency());}
        else {
            if(order!=null && (!header(binding,order) || order.state().header().quarantined()))return new Verdict("REVIEW","ORDER_EVIDENCE_QUARANTINED",null);
            if(permit.firstOrder()==ReferralPolicyEvaluator.Fact.NO)facts=new ReferralPolicyEvaluator.Evidence(true,permit.newCustomer(),ReferralPolicyEvaluator.Fact.NO,null,null,0,0,permit.plan().currency());
            else {
                if(state==null || permit.firstOrder()!=ReferralPolicyEvaluator.Fact.YES || !Objects.equals(permit.firstOrderPolicy(),state.latest().firstOrderPolicyVersion()))return new Verdict("PENDING","EVIDENCE_UNAVAILABLE",null);
                var base=ReferralOrderEvidenceMerger.toEvaluatorEvidence(state);
                facts=new ReferralPolicyEvaluator.Evidence(base.verified(),permit.newCustomer(),base.firstValidOrder(),base.qualifyingFactAt(),base.qualifyingFactReceivedAt(),base.settledAmountMinor(),base.cumulativeRefundMinor(),base.currency());
            }
        }
        var decision=ReferralPolicyEvaluator.evaluate(permit.plan(),binding.relation().boundAt(),facts,now);return new Verdict(decision.state().name(),decision.reason().name(),decision.dueAt());
    }
    private boolean valid(Permit p,Binding b,Instant now){
        if(p==null || !p.binding().equals(b) || !b.relation().boundAt().equals(p.evaluatedAsOf()) || now.isBefore(p.issuedAt()) || !now.isBefore(p.expiresAt()) || Duration.between(p.issuedAt(),p.expiresAt()).compareTo(Duration.ofSeconds(permitSeconds))>0)return false;
        if(p.order()!=null && !p.order().tenantId().equals(b.relation().tenantId()))return false;
        if(p.plan().goalType()==ReferralPlan.GoalType.FIRST_ORDER_SETTLED && p.firstOrder()==ReferralPolicyEvaluator.Fact.YES && (p.order()==null || p.firstOrderPolicy()==null || p.firstOrderPolicy().isBlank() || p.firstOrderPolicy().length()>128))return false;
        if(p.plan().goalType()==ReferralPlan.GoalType.REGISTERED_NEW_CUSTOMER && (p.order()!=null || (p.newCustomer()==ReferralPolicyEvaluator.Fact.YES && (p.registeredAt()==null || p.registrationReceivedAt()==null || p.registrationAnchorDigest()==null))))return false;
        Instant deadline=b.relation().boundAt().plusSeconds(p.plan().qualificationWindowSeconds());if(p.plan().settlementEndsAt().isBefore(deadline))deadline=p.plan().settlementEndsAt();
        return deadline.truncatedTo(ChronoUnit.MICROS).equals(b.relation().qualifyDeadline());
    }
    private void validate(TenantScope scope,Task task,Binding b){scope(scope,task);var r=b.relation();ReferralParticipant p=b.participant();
        if(!r.tenantId().equals(task.tenantId()) || !r.relationId().equals(task.relationId()) || !r.participantId().equals(task.participantId()) || !r.organizationId().equals(task.organizationId()) || !r.shopId().equals(task.shopId()) || b.keyVersion()!=keyVersion
                || !p.tenantId().equals(r.tenantId()) || !p.participantId().equals(r.participantId()) || !p.campaignId().equals(r.campaignId()) || !p.organizationId().equals(r.organizationId()) || !p.shopId().equals(r.shopId()) || !p.definitionId().equals(r.definitionId()) || p.definitionVersion()!=r.definitionVersion() || p.generation()!=r.generation() || !p.artifactId().equals(r.artifactId()) || !p.policyHash().equals(r.policyHash()))throw rejected("REFERRAL_QUALIFICATION_SCOPE_MISMATCH");}
    private static void scope(TenantScope scope,Task task){if(!scope.tenantId().value().equals(task.tenantId()))throw rejected("REFERRAL_QUALIFICATION_SCOPE_MISMATCH");scope.requireOrganization(task.organizationId());scope.requireShop(task.shopId());}
    private static boolean header(Binding binding,StoredOrder order){var h=order.state().header();var r=binding.relation();return h.order().equals(order.key()) && h.subjectKey().equals(binding.subjectKey()) && h.keyVersion()==binding.keyVersion() && h.organizationId().equals(r.organizationId()) && h.shopId().equals(r.shopId());}
    private static boolean content(StoredOrder order,ReferralOrderEvidence.State state){if(state==null)return false;var h=order.state().header();var s=state.latest().scope();return state.latest().revision()==h.revision() && state.quarantined()==h.quarantined() && s.tenantId().equals(h.order().tenantId()) && s.sourceSystem().equals(h.order().sourceSystem()) && s.orderId().equals(h.order().orderId()) && s.organizationId().equals(h.organizationId()) && s.shopId().equals(h.shopId());}
    private static boolean sameOrder(StoredOrder a,StoredOrder b){if(a==null || b==null)return a==b;return a.key().equals(b.key()) && a.resourceId().equals(b.resourceId()) && a.rowVersion()==b.rowVersion() && a.state().header().equals(b.state().header()) && a.state().businessDigest().equals(b.state().businessDigest()) && a.state().keyId().equals(b.state().keyId()) && Arrays.equals(a.state().cipher(),b.state().cipher());}
    private static boolean owns(Task a,Task b,Instant now){return b!=null && b.tenantId().equals(a.tenantId()) && b.relationId().equals(a.relationId()) && b.participantId().equals(a.participantId()) && b.organizationId().equals(a.organizationId()) && b.shopId().equals(a.shopId()) && b.requestedRevision()==a.requestedRevision() && b.fence()==a.fence() && b.status().equals("PROCESSING") && Objects.equals(b.leaseOwner(),a.leaseOwner()) && b.leaseUntil()!=null && now.isBefore(b.leaseUntil());}
    private static void text(String x,int max){if(x==null || !x.matches("[A-Za-z0-9._:-]{1,"+max+"}"))throw rejected("REFERRAL_QUALIFICATION_INVALID_REQUEST");}
    private static ConflictException rejected(String code){return new ConflictException(code,"referral qualification unavailable or requires controlled retry");}
    private record Prepared(Relation relation,Binding binding,Permit permit,StoredOrder order,ReferralOrderEvidence.State state){}
    private record Verdict(String state,String reason,Instant dueAt){}
    private static final class RetryException extends RuntimeException {@Serial private static final long serialVersionUID=1L;}
}
