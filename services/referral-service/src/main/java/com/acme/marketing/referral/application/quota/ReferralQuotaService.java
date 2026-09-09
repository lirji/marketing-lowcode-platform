package com.acme.marketing.referral.application.quota;

import com.acme.marketing.platform.crypto.Digests;
import com.acme.marketing.platform.error.ConflictException;
import com.acme.marketing.platform.identity.TenantScope;
import com.acme.marketing.referral.ReferralRewardRule;
import com.acme.marketing.referral.domain.quota.ReferralQuota;
import com.acme.marketing.referral.domain.quota.ReferralQuota.*;
import com.acme.marketing.referral.infrastructure.persistence.mapper.ReferralQuotaMapper;
import com.acme.marketing.referral.infrastructure.persistence.mapper.ReferralQuotaMapper.*;
import com.acme.marketing.referral.infrastructure.persistence.mapper.ReferralRewardMapper;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Function;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import tools.jackson.databind.ObjectMapper;

/**
 * 永久数量配额服务，不代表资金或权益库存。范围和上限从已冻结奖励读取，调用方不能提交新的额度。
 * 未知履约始终保留预占；此阶段不提供按时间释放或人工伪造成功的入口。
 */
@Service
public class ReferralQuotaService {
    private static final DateTimeFormatter SQL=DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS").withZone(ZoneOffset.UTC);
    private final ReferralQuotaMapper mapper;private final ReferralRewardMapper events;private final Clock clock;private final ObjectMapper json;private final TransactionTemplate tx;
    /** 所有锁和事件使用同一主库事务，网络来源必须由外层事务外适配。 */
    public ReferralQuotaService(ReferralQuotaMapper mapper,ReferralRewardMapper events,Clock clock,ObjectMapper json,PlatformTransactionManager manager){
        this.mapper=mapper;this.events=events;this.clock=clock;this.json=json;tx=new TransactionTemplate(manager);tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);tx.setTimeout(5);
    }
    /** 按已固定奖励规则显式初始化分桶；桶数无默认值，重复初始化不能重置余额或修改上限。 */
    public void initialize(TenantScope scope,String rewardId,int buckets){
        entry(scope,"referral:quota-configure");require(buckets>0 && buckets<=1024);
        locked(scope,rewardId,values->{var reward=(RewardRow)values.get("r");var rule=rule(reward);values.put("lock",true);values.put("limit",rule.campaignLimit());values.put("buckets",buckets);values.put("ruleJson",reward.ruleJson());
            int inserted=mapper.reserveAccount(values);AccountRow account=mapper.account(values);require(account!=null && account.bucketCount()==buckets);checkAccount(reward,rule,account);
            if(inserted==1 && mapper.bucket(withBucket(values,0))==null){
                for(int i=0;i<buckets;i++){values.put("bucket",i);values.put("allocated",rule.campaignLimit()/buckets+(i<rule.campaignLimit()%buckets?1:0));one(mapper.insertBucket(values));}
            }
            return null;});
    }
    /** 返回当前预占状态；WAIT_QUOTA/WAIT_AUTHORITY/WAIT_SUBJECT_LIMIT均不表示全活动售罄或发奖成功。 */
    public String reserve(TenantScope scope,String rewardId){
        entry(scope,"referral:quota");return locked(scope,rewardId,values->{
            var reward=(RewardRow)values.get("r");
            if(!reward.quotaState().equals("WAIT_QUOTA"))return reward.quotaState();
            if(!reward.entitlementState().equals("ELIGIBLE"))return "INVALIDATED";
            var rule=rule(reward);AccountRow account=mapper.account(values);if(account==null)return "WAIT_QUOTA";checkAccount(reward,rule,account);
            Long valid=mapper.validCount(values);if(valid==null || (reward.mode().equals("MILESTONE") && valid<reward.threshold()) || mapper.staleQualifications(values)>0)return "WAIT_AUTHORITY";
            values.put("subject",reward.beneficiaryKey());values.put("subjectLimit",rule.perSubjectLimit());mapper.reserveSubject(values);SubjectRow subject=Objects.requireNonNull(mapper.subject(values));require(subject.quotaLimit()==rule.perSubjectLimit());
            if(Math.addExact(subject.reserved(),subject.consumed())>=subject.quotaLimit())return "WAIT_SUBJECT_LIMIT";
            int bucket=chooseBucket(reward.participantId(),account.bucketCount());values.put("bucket",bucket);BucketRow row=Objects.requireNonNull(mapper.bucket(values));
            require(mapper.reservation(values)==null);Key key=new Key(reward.tenantId(),reward.campaignId(),reward.ruleId(),bucket);
            Bucket current=new Bucket(key,row.allocated(),row.available(),row.reserved(),row.consumed(),row.epoch(),row.version());
            Change result=ReferralQuota.reserve(current,current.stamp(),reward.rewardId(),null,ReversalPolicy.RETAIN_CONSUMED);
            if(result.decision()==Decision.WAIT_QUOTA)return "WAIT_QUOTA";
            values.put("before",current);values.put("after",result.bucket());one(mapper.updateBucket(values));
            values.put("subjectVersion",subject.version());values.put("reserved",Math.incrementExact(subject.reserved()));values.put("consumed",subject.consumed());one(mapper.updateSubject(values));
            values.put("epoch",row.epoch());one(mapper.insertReservation(values));one(mapper.reserveReward(values));event(values,"REWARD_QUOTA_RESERVED");return "RESERVED";
        });
    }
    /** 冷路径调拨仅移动空闲额度，按桶编号锁定，跨桶总额不变且双epoch共同推进。 */
    public void transfer(TenantScope scope,String rewardId,int from,int to,long quantity){
        entry(scope,"referral:quota-configure");require(from>=0 && to>=0 && from!=to && quantity>0);
        locked(scope,rewardId,values->{var reward=(RewardRow)values.get("r");values.put("lock",true);AccountRow account=mapper.account(values);require(account!=null && from<account.bucketCount() && to<account.bucketCount());checkAccount(reward,rule(reward),account);
            BucketRow first=mapper.bucket(withBucket(values,Math.min(from,to))),last=mapper.bucket(withBucket(values,Math.max(from,to)));
            Bucket source=bucket(reward,from,from<to?first:last),destination=bucket(reward,to,from<to?last:first);
            var moved=ReferralQuota.transfer(source,source.stamp(),destination,destination.stamp(),quantity);
            update(values,source,moved.source());update(values,destination,moved.destination());return null;});
    }
    /**
     * 仅由持参与者/奖励锁的资格事务调用：未授权且从未发送时才可确定本地取消成功。
     * 已确认、已投递和UNKNOWN一律保留占用，等待真实外部终态；没有按租约到期释放的分支。
     */
    public void releaseUnsubmitted(String tenant,String rewardId,String actor,String trace){
        require(TransactionSynchronizationManager.isActualTransactionActive());var values=new HashMap<String,Object>();
        values.put("tenant",tenant);values.put("reward",rewardId);values.put("lock",false);RewardRow hint=mapper.reward(values);require(hint!=null);
        values.put("participant",hint.participantId());require(mapper.lockParticipant(values)!=null);values.put("lock",true);RewardRow reward=mapper.reward(values);require(reward!=null && reward.participantId().equals(hint.participantId()));
        if(!reward.entitlementState().equals("INVALIDATED") || !reward.authorizationState().equals("NONE") || !reward.deliveryState().equals("NOT_SUBMITTED") || !reward.quotaState().equals("RESERVED"))return;
        values.put("r",reward);values.put("campaign",reward.campaignId());values.put("rule",reward.ruleId());values.put("participant",reward.participantId());values.put("subject",reward.beneficiaryKey());
        values.put("actor",actor);values.put("trace",trace);values.put("now",SQL.format(clock.instant()));
        SubjectRow subject=Objects.requireNonNull(mapper.subject(values));ReservationRow reservation=Objects.requireNonNull(mapper.reservation(values));
        require(reservation.participantId().equals(reward.participantId()) && reservation.beneficiaryKey().equals(reward.beneficiaryKey()) && reservation.state().equals("RESERVED"));
        values.put("bucket",reservation.bucketId());Bucket current=bucket(reward,reservation.bucketId(),mapper.bucket(values));
        var held=new Reservation(current.key(),reward.rewardId(),reservation.createdEpoch(),ReservationState.RESERVED,null,null,null,ReversalPolicy.RETAIN_CONSUMED);
        String digest="sha256:"+Digests.sha256Hex("local-cancel:"+reward.rewardId());
        Change result=ReferralQuota.applyFinal(current,current.stamp(),held,new FinalProof(current.key(),reward.rewardId(),FinalFact.CANCELLED_BEFORE_ISSUE,digest));
        update(values,current,result.bucket());values.put("subjectVersion",subject.version());values.put("reserved",Math.subtractExact(subject.reserved(),1));values.put("consumed",subject.consumed());
        values.put("terminalDigest",digest);one(mapper.updateSubject(values));one(mapper.releaseReservation(values));one(mapper.releaseReward(values));event(values,"REWARD_QUOTA_RELEASED");
    }
    /**
     * 仅供已验签终态入箱事务使用，调用方已锁参与者/奖励且必须一起提交来源历史。
     * 此接口不暴露HTTP参数绑定；UNKNOWN、202和404没有对应FinalFact，不能释放占用。
     */
    public void applyFinalFact(String tenant,String rewardId,FinalFact fact,String digest,String actor,String trace){
        require(TransactionSynchronizationManager.isActualTransactionActive());var v=new HashMap<String,Object>();v.put("tenant",tenant);v.put("reward",rewardId);v.put("lock",false);
        RewardRow hint=mapper.reward(v);require(hint!=null);v.put("participant",hint.participantId());require(mapper.lockParticipant(v)!=null);v.put("lock",true);RewardRow reward=mapper.reward(v);require(reward!=null && reward.participantId().equals(hint.participantId()));
        v.put("r",reward);v.put("campaign",reward.campaignId());v.put("rule",reward.ruleId());v.put("subject",reward.beneficiaryKey());v.put("actor",actor);v.put("trace",trace);v.put("now",SQL.format(clock.instant()));
        SubjectRow subject=Objects.requireNonNull(mapper.subject(v));ReservationRow row=Objects.requireNonNull(mapper.reservation(v));require(row.participantId().equals(reward.participantId()) && row.beneficiaryKey().equals(reward.beneficiaryKey()));
        v.put("bucket",row.bucketId());Bucket current=bucket(reward,row.bucketId(),mapper.bucket(v));
        var previous=new Reservation(current.key(),rewardId,row.createdEpoch(),ReservationState.valueOf(row.state()),row.terminalFact()==null?null:FinalFact.valueOf(row.terminalFact()),row.terminalDigest(),row.successDigest(),ReversalPolicy.RETAIN_CONSUMED);
        Change next=ReferralQuota.applyFinal(current,current.stamp(),previous,new FinalProof(current.key(),rewardId,fact,digest));
        if(next.decision()==Decision.REPLAY)return;
        update(v,current,next.bucket());v.put("subjectVersion",subject.version());v.put("reserved",Math.addExact(subject.reserved(),Math.subtractExact(next.bucket().reserved(),current.reserved())));v.put("consumed",Math.addExact(subject.consumed(),Math.subtractExact(next.bucket().consumed(),current.consumed())));one(mapper.updateSubject(v));
        v.put("final",next.reservation());v.put("reservationRevision",row.revision());one(mapper.finalizeReservation(v));one(mapper.finalizeReward(v));event(v,"REWARD_QUOTA_FINALIZED");
    }
    private void update(Map<String,Object> values,Bucket before,Bucket after){values.put("bucket",before.key().bucketId());values.put("before",before);values.put("after",after);one(mapper.updateBucket(values));}
    private static Bucket bucket(RewardRow reward,int id,BucketRow b){Objects.requireNonNull(b);return new Bucket(new Key(reward.tenantId(),reward.campaignId(),reward.ruleId(),id),b.allocated(),b.available(),b.reserved(),b.consumed(),b.epoch(),b.version());}
    private <T>T locked(TenantScope scope,String rewardId,Function<Map<String,Object>,T> action){
        require(rewardId!=null && rewardId.matches("[a-f0-9]{64}"));var values=new HashMap<String,Object>();values.put("tenant",scope.tenantId().value());values.put("reward",rewardId);values.put("lock",false);
        RewardRow hint=mapper.reward(values);require(hint!=null);scope.requireOrganization(hint.organizationId());scope.requireShop(hint.shopId());values.put("participant",hint.participantId());
        return tx.execute(status->{require("ACTIVE".equals(mapper.lockParticipant(values)));values.put("lock",true);RewardRow reward=mapper.reward(values);require(reward!=null && reward.participantId().equals(hint.participantId()) && reward.organizationId().equals(hint.organizationId()) && reward.shopId().equals(hint.shopId()));
            values.put("r",reward);values.put("campaign",reward.campaignId());values.put("rule",reward.ruleId());values.put("relation",reward.relationId());values.put("lock",false);values.put("now",SQL.format(clock.instant()));values.put("actor",scope.actorId());values.put("trace","quota-"+UUID.randomUUID());return action.apply(values);});
    }
    private ReferralRewardRule rule(RewardRow reward){return json.readValue(reward.ruleJson(),ReferralRewardRule.class);}
    private void checkAccount(RewardRow reward,ReferralRewardRule rule,AccountRow account){require(account.organizationId().equals(reward.organizationId()) && account.shopId().equals(reward.shopId()) && account.quotaLimit()==rule.campaignLimit() && json.readValue(account.ruleJson(),ReferralRewardRule.class).equals(rule));}
    private void event(Map<String,Object> values,String type){var reward=(RewardRow)values.get("r");values.put("type",type);values.put("reason",type.equals("REWARD_QUOTA_RESERVED")?"QUOTA_RESERVED_NOT_SENT":type.equals("REWARD_QUOTA_FINALIZED")?"TRUSTED_TERMINAL_FACT":"LOCAL_CANCEL_NOT_SENT");values.put("revision",Math.incrementExact(reward.revision()));values.put("eventId",UUID.randomUUID().toString());values.put("auditId",UUID.randomUUID().toString());String payload=json.writeValueAsString(Map.of("schemaVersion",1,"rewardId",reward.rewardId(),"quotaState",type.equals("REWARD_QUOTA_RESERVED")?"RESERVED":type.equals("REWARD_QUOTA_FINALIZED")?((Reservation)values.get("final")).state().name():"RELEASED"));values.put("payload",payload);values.put("hash",Digests.sha256Hex(payload));one(events.outbox(values));one(events.audit(values));}
    private static Map<String,Object> withBucket(Map<String,Object> values,int bucket){values.put("bucket",bucket);return values;}
    private static int chooseBucket(String participant,int count){return (int)(Long.parseUnsignedLong(Digests.sha256Hex(participant).substring(0,8),16)%count);}
    private static void entry(TenantScope scope,String permission){Objects.requireNonNull(scope);scope.requirePermission(permission);require(!TransactionSynchronizationManager.isActualTransactionActive());}
    private static void one(int count){require(count==1);}
    private static void require(boolean ok){if(!ok)throw new ConflictException("REFERRAL_QUOTA_UNAVAILABLE","referral quota configuration or persistent state unavailable");}
}
