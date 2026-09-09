package com.acme.marketing.referral.application.fulfillment;

import com.acme.marketing.platform.crypto.Digests;
import com.acme.marketing.platform.error.ConflictException;
import com.acme.marketing.platform.identity.TenantScope;
import com.acme.marketing.referral.application.authorization.ReferralAuthorizationProofPort.Reward;
import com.acme.marketing.referral.application.fulfillment.ReferralFulfillmentProofPort.*;
import com.acme.marketing.referral.application.quota.ReferralQuotaService;
import com.acme.marketing.referral.domain.quota.ReferralQuota.FinalFact;
import com.acme.marketing.referral.infrastructure.persistence.mapper.*;
import com.acme.marketing.referral.infrastructure.persistence.mapper.ReferralFulfillmentMapper.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import tools.jackson.databind.ObjectMapper;

/**
 * 已认证累计终态入箱，成功与追回分别保留。202/UNKNOWN只记录已知状态，不释放数量配额。
 * 本类没有公开HTTP或消息订阅；真实渠道验签/取消栅栏仍由受信适配器和联调证明。
 */
@Service
public class ReferralFulfillmentService {
    private static final DateTimeFormatter SQL=DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS").withZone(ZoneOffset.UTC);
    private final ReferralFulfillmentMapper mapper;private final ReferralAuthorizationMapper rewards;private final ReferralRewardMapper events;private final ReferralQuotaService quotas;
    private final ReferralFulfillmentProofPort proofs;private final Clock clock;private final ObjectMapper json;private final long maxSeconds;private final TransactionTemplate tx;
    /** 来源寿命默认零拒绝，不内置实际渠道或测试验签通过实现。 */
    public ReferralFulfillmentService(ReferralFulfillmentMapper mapper,ReferralAuthorizationMapper rewards,ReferralRewardMapper events,ReferralQuotaService quotas,
            ReferralFulfillmentProofPort proofs,Clock clock,ObjectMapper json,PlatformTransactionManager manager,@Value("${marketing.referral.fulfillment.max-proof-seconds:0}") long maxSeconds){
        this.mapper=mapper;this.rewards=rewards;this.events=events;this.quotas=quotas;this.proofs=proofs;this.clock=clock;this.json=json;this.maxSeconds=maxSeconds;
        tx=new TransactionTemplate(manager);tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);tx.setTimeout(5);
    }
    /** 验证来源在事务外，永久Inbox/历史/当前状态/配额/审计/Outbox一次提交。 */
    public Receipt accept(TenantScope scope,Envelope envelope){
        Objects.requireNonNull(scope);Objects.requireNonNull(envelope);scope.requirePermission("referral:fulfillment");require(!TransactionSynchronizationManager.isActualTransactionActive() && maxSeconds>0);
        var v=new HashMap<String,Object>();v.put("tenant",scope.tenantId().value());v.put("reward",envelope.rewardId());v.put("lock",false);
        Reward hint=rewards.reward(v);checkScope(scope,hint);Proof proof;
        try{proof=proofs.verify(scope,envelope,hint);}catch(RuntimeException unavailable){throw denied();}
        require(proof!=null && proof.envelope().equals(envelope) && proof.reward().equals(hint));valid(proof,clock.instant());Snapshot s=proof.snapshot();
        require(s.tenantId().equals(hint.tenantId()) && s.rewardId().equals(hint.rewardId()) && s.sourceRequestId().equals(hint.sourceRequestId()));
        v.put("participant",hint.participantId());v.put("provider",s.providerId());v.put("providerRevision",s.providerRevision());v.put("event",envelope.eventId());v.put("digest",s.businessDigest());
        return tx.execute(status->{
            require(rewards.participant(v)!=null);v.put("lock",true);Reward current=rewards.reward(v);checkScope(scope,current);
            // 资格/取消在验签期间可变化，但永久受益人/规则/来源身份绝不能漂移。
            require(sameIdentity(hint,current));valid(proof,clock.instant());
            Receipt original=mapper.inbox(v);if(original!=null){require(original.rewardId().equals(current.rewardId()) && original.digest().equals(s.businessDigest()) && original.revision()==s.providerRevision());return original;}
            require(rewards.receipt(v)!=null);Current old=mapper.current(v);if(old!=null)require(old.providerId().equals(s.providerId()));
            String history=mapper.history(v);if(history!=null)require(history.equals(s.businessDigest()));
            String outcome="APPLIED";Instant now=clock.instant();v.put("now",SQL.format(now));
            if(history==null)one(mapper.insertHistory(v));
            if(old!=null && s.providerRevision()<=old.providerRevision()){historical(old,s);outcome="REPLAYED_OLD_REVISION";}
            else {
                monotonic(old,s);String compensation=s.compensationState();
                if(current.entitlementState().equals("INVALIDATED") && !Set.of("CANCELLED","REVERSED","MANUAL_REVIEW").contains(compensation))compensation="PENDING";
                if(old!=null && old.compensationState().equals("MANUAL_REVIEW") && !compensation.equals("REVERSED") && !compensation.equals("CANCELLED"))compensation="MANUAL_REVIEW";
                v.put("delivery",s.deliveryState());v.put("compensation",compensation);v.put("success",s.successDigest());v.put("terminal",s.terminalDigest());v.put("previous",current.revision());v.put("revision",Math.incrementExact(current.revision()));one(mapper.updateReward(v));int saved=mapper.saveCurrent(v);require(saved==1 || saved==2);
                if(s.deliveryState().equals("SUCCEEDED")){
                    quotas.applyFinalFact(current.tenantId(),current.rewardId(),FinalFact.SUCCEEDED,s.successDigest(),scope.actorId(),"fulfillment");
                    if(compensation.equals("REVERSED"))quotas.applyFinalFact(current.tenantId(),current.rewardId(),FinalFact.REVERSED_AFTER_ISSUE,s.terminalDigest(),scope.actorId(),"fulfillment");
                }else if(s.deliveryState().equals("FAILED_FINAL"))quotas.applyFinalFact(current.tenantId(),current.rewardId(),FinalFact.CONFIRMED_NOT_ISSUED,s.terminalDigest(),scope.actorId(),"fulfillment");
                event(v,scope);
            }
            v.put("outcome",outcome);one(mapper.insertInbox(v));valid(proof,clock.instant());return new Receipt(current.rewardId(),s.businessDigest(),s.providerRevision(),outcome);
        });
    }
    private static boolean sameIdentity(Reward a,Reward b){return a.tenantId().equals(b.tenantId()) && a.rewardId().equals(b.rewardId()) && a.sourceRequestId().equals(b.sourceRequestId()) && a.participantId().equals(b.participantId()) && a.beneficiaryKey().equals(b.beneficiaryKey()) && a.policyHash().equals(b.policyHash()) && a.ruleJson().equals(b.ruleJson()) && a.organizationId().equals(b.organizationId()) && a.shopId().equals(b.shopId()) && a.campaignId().equals(b.campaignId()) && Objects.equals(a.relationId(),b.relationId())
        && a.keyVersion()==b.keyVersion() && a.role().equals(b.role()) && a.mode().equals(b.mode()) && a.threshold()==b.threshold()
        && a.ruleId().equals(b.ruleId()) && a.definitionId().equals(b.definitionId()) && a.definitionVersion()==b.definitionVersion()
        && a.generation()==b.generation() && a.artifactId().equals(b.artifactId());}
    private static void monotonic(Current old,Snapshot next){if(old==null)return;
        if(old.deliveryState().equals("SUCCEEDED"))require(next.deliveryState().equals("SUCCEEDED") && Objects.equals(old.successDigest(),next.successDigest()));
        if(old.deliveryState().equals("FAILED_FINAL"))require(next.deliveryState().equals("FAILED_FINAL") && Objects.equals(old.terminalDigest(),next.terminalDigest()));
        if(old.compensationState().equals("REVERSED"))require(next.compensationState().equals("REVERSED") && Objects.equals(old.terminalDigest(),next.terminalDigest()));
        if(old.compensationState().equals("CANCELLED"))require(next.compensationState().equals("CANCELLED"));
    }
    /** 旧终态也必须符合最高累计事实，不能因版本较小掩盖成功或追回冲突。 */
    private static void historical(Current current,Snapshot older){
        if(older.deliveryState().equals("SUCCEEDED"))require(current.deliveryState().equals("SUCCEEDED") && Objects.equals(current.successDigest(),older.successDigest()));
        if(older.deliveryState().equals("FAILED_FINAL"))require(current.deliveryState().equals("FAILED_FINAL") && Objects.equals(current.terminalDigest(),older.terminalDigest()));
        if(older.compensationState().equals("REVERSED"))require(current.compensationState().equals("REVERSED") && Objects.equals(current.terminalDigest(),older.terminalDigest()));
        if(older.compensationState().equals("CANCELLED"))require(current.compensationState().equals("CANCELLED"));
    }
    private static void checkScope(TenantScope scope,Reward r){require(r!=null && r.tenantId().equals(scope.tenantId().value()));scope.requireOrganization(r.organizationId());scope.requireShop(r.shopId());}
    private void valid(Proof proof,Instant now){require(!now.isBefore(proof.issuedAt()) && now.isBefore(proof.expiresAt()) && proof.expiresAt().isAfter(proof.issuedAt()) && Duration.between(proof.issuedAt(),proof.expiresAt()).compareTo(Duration.ofSeconds(maxSeconds))<=0);}
    private void event(Map<String,Object> v,TenantScope scope){v.put("type","REWARD_FULFILLMENT_CHANGED");v.put("reason","TRUSTED_PROVIDER_FACT");v.put("actor",scope.actorId());v.put("trace","fulfillment");v.put("eventId",UUID.randomUUID().toString());v.put("auditId",UUID.randomUUID().toString());String payload=json.writeValueAsString(Map.of("schemaVersion",1,"rewardId",v.get("reward"),"deliveryState",v.get("delivery"),"compensationState",v.get("compensation"),"providerRevision",v.get("providerRevision")));v.put("payload",payload);v.put("hash",Digests.sha256Hex(payload));one(events.outbox(v));one(events.audit(v));}
    private static void one(int rows){require(rows==1);}
    private static void require(boolean ok){if(!ok)throw denied();}
    private static ConflictException denied(){return new ConflictException("REFERRAL_FULFILLMENT_UNAVAILABLE","referral fulfillment proof conflicts or is unavailable");}
}
