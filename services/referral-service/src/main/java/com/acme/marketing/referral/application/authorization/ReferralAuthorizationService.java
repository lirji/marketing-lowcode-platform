package com.acme.marketing.referral.application.authorization;

import com.acme.marketing.contracts.referral.ReferralAwardIdentity;
import com.acme.marketing.platform.crypto.Digests;
import com.acme.marketing.platform.error.ConflictException;
import com.acme.marketing.platform.identity.TenantScope;
import com.acme.marketing.referral.application.ReferralRepository;
import com.acme.marketing.referral.application.authorization.ReferralAuthorizationProofPort.*;
import com.acme.marketing.referral.domain.authorization.ReferralRewardAuthorization;
import com.acme.marketing.referral.domain.authorization.ReferralRewardAuthorization.*;
import com.acme.marketing.referral.infrastructure.persistence.mapper.ReferralAuthorizationMapper;
import com.acme.marketing.referral.infrastructure.persistence.mapper.ReferralAuthorizationMapper.*;
import com.acme.marketing.referral.infrastructure.persistence.mapper.ReferralRewardMapper;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import tools.jackson.databind.ObjectMapper;

/**
 * 永久授权确认及恢复，不发券、不签发公网凭证。适配器负责在返回给外部前签名或受信传输。
 * 原确认可永久回放，但历史receipt不是当前发送许可；投递方必须另外执行当前资格/运行复核。
 */
@Service
public class ReferralAuthorizationService {
    private static final DateTimeFormatter SQL=DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS").withZone(ZoneOffset.UTC);
    private final ReferralAuthorizationMapper mapper;private final ReferralRewardMapper events;private final ReferralRepository anchors;private final ReferralAuthorizationProofPort proofs;
    private final Clock clock;private final ObjectMapper json;private final TransactionTemplate tx;private final int maxDependencies;private final long maxProofSeconds;
    /** 时限/依赖上界显式配置，默认零禁止首次确认；恢复已确认回执不依赖旧短期许可。 */
    public ReferralAuthorizationService(ReferralAuthorizationMapper mapper,ReferralRewardMapper events,ReferralRepository anchors,ReferralAuthorizationProofPort proofs,Clock clock,ObjectMapper json,
            PlatformTransactionManager manager,@Value("${marketing.referral.authorization.max-dependencies:0}") int maxDependencies,@Value("${marketing.referral.authorization.max-proof-seconds:0}") long maxProofSeconds){
        this.mapper=mapper;this.events=events;this.anchors=anchors;this.proofs=proofs;this.clock=clock;this.json=json;this.maxDependencies=maxDependencies;this.maxProofSeconds=maxProofSeconds;
        tx=new TransactionTemplate(manager);tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);tx.setTimeout(5);
    }
    /** 先找原确认；首次确认才在事务外请求真实证明，不能持数据库锁调用KMS/风控。 */
    public Result confirm(TenantScope scope,Request request){
        entry(scope);Objects.requireNonNull(request);Map<String,Object> values=values(scope,request.rewardId(),request.sourceRequestId(),request.stableClaimsDigest());
        Reward before=read(scope,values);Result original=recover(scope,request.rewardId(),request.sourceRequestId(),request.stableClaimsDigest());if(original!=null)return original;
        require(maxDependencies>0 && maxDependencies<=10000 && maxProofSeconds>0);Proof proof;
        try{proof=proofs.verify(scope,request,before);}catch(RuntimeException unavailable){throw denied();}
        require(proof!=null && proof.request().equals(request) && proof.reward().equals(before));valid(proof,clock.instant());
        return tx.execute(status->{
            Long version=anchors.subjectIndexVersion(before.tenantId());require(version!=null && version==before.keyVersion());
            values.put("participant",before.participantId());require("ACTIVE".equals(mapper.participant(values)));
            ReceiptRow replay=mapper.receipt(values);if(replay!=null)return result(replay,lockReward(scope,values),request.stableClaimsDigest(),true);
            values.put("relation",before.relationId());values.put("limit",maxDependencies+1);List<Basis> basis=mapper.qualifications(values);require(!basis.isEmpty() && basis.size()<=maxDependencies);
            Long count=mapper.progress(values);require(count!=null && (before.mode().equals("PER_RELATION") || count>=before.threshold()));
            if(before.mode().equals("MILESTONE"))require(count==basis.size());
            for(String resource:basis.stream().map(Basis::evidenceResourceId).filter(Objects::nonNull).distinct().sorted().toList()){
                values.put("resource",resource);Evidence actual=mapper.evidence(values);require(actual!=null && !actual.quarantined());
                for(Basis q:basis)if(resource.equals(q.evidenceResourceId()))require(actual.version()==q.evidenceVersion());
            }
            for(Basis q:basis){require(q.counted() && q.state().equals("ELIGIBLE"));values.put("basisRelation",q.relationId());Task task=mapper.task(values);
                require(task!=null && task.status().equals("DONE") && task.requested()==task.processed() && task.processed()==q.taskRevision());}
            Reward current=lockReward(scope,values);require(current.equals(before) && current.entitlementState().equals("ELIGIBLE") && current.authorizationState().equals("NONE")
                    && current.quotaState().equals("RESERVED") && current.qualificationRevision()==request.qualificationRevision());
            Instant now=clock.instant();valid(proof,now);
            Identity identity=new Identity(current.tenantId(),current.rewardId(),current.sourceRequestId(),request.stableClaimsDigest(),request.qualificationRevision());
            String evidenceDigest="sha256:"+Digests.sha256Hex(json.writeValueAsString(basis));EvidenceBasis watermarks=new EvidenceBasis(identity,evidenceDigest);
            var confirmed=ReferralRewardAuthorization.eligible(identity).confirm(identity,1,new NewConfirmation(identity,request.qualificationRevision(),watermarks,watermarks,
                    proof.candidate(),proof.runtime(),proof.risk(),UUID.randomUUID().toString(),1),now);
            var receipt=confirmed.receipt();values.put("receipt",new ReceiptRow(current.rewardId(),current.sourceRequestId(),request.stableClaimsDigest(),request.qualificationRevision(),receipt.confirmationId(),receipt.authorizationSequence(),now.getEpochSecond(),now.getNano()));
            values.put("revision",Math.incrementExact(current.revision()));values.put("previous",current.revision());values.put("now",SQL.format(now));one(mapper.insertReceipt(values));one(mapper.confirm(values));event(values,scope);
            valid(proof,clock.instant());return result((ReceiptRow)values.get("receipt"),lockReward(scope,values),request.stableClaimsDigest(),false);
        });
    }
    /** 只按已认证机器及原稳定摘要查询，不调用旧签名或远端；返回当前取消水位并固定禁止直接用历史回执发送。 */
    public Result recover(TenantScope scope,String rewardId,String sourceRequestId,String stableDigest){
        entry(scope);var values=values(scope,rewardId,sourceRequestId,stableDigest);Reward before=read(scope,values);
        return tx.execute(status->{values.put("participant",before.participantId());require(mapper.participant(values)!=null);Reward current=lockReward(scope,values);ReceiptRow receipt=mapper.receipt(values);return receipt==null?null:result(receipt,current,stableDigest,true);});
    }
    private Reward read(TenantScope scope,Map<String,Object> values){values.put("lock",false);Reward reward=mapper.reward(values);check(scope,values,reward);return reward;}
    private Reward lockReward(TenantScope scope,Map<String,Object> values){values.put("lock",true);Reward reward=mapper.reward(values);check(scope,values,reward);return reward;}
    private static void check(TenantScope scope,Map<String,Object> values,Reward r){require(r!=null && r.tenantId().equals(scope.tenantId().value()) && r.sourceRequestId().equals(values.get("source")));scope.requireOrganization(r.organizationId());scope.requireShop(r.shopId());}
    private void valid(Proof proof,Instant now){for(Window window:List.of(proof.candidate(),proof.runtime(),proof.risk()))require(!now.isBefore(window.issuedAt()) && now.isBefore(window.expiresAt()) && Duration.between(window.issuedAt(),window.expiresAt()).compareTo(Duration.ofSeconds(maxProofSeconds))<=0);}
    private Result result(ReceiptRow receipt,Reward current,String digest,boolean replay){require(receipt.stableClaimsDigest().equals(digest) && receipt.rewardId().equals(current.rewardId()) && receipt.sourceRequestId().equals(current.sourceRequestId()));
        return new Result(receipt.rewardId(),receipt.sourceRequestId(),receipt.stableClaimsDigest(),receipt.qualificationRevision(),receipt.confirmationId(),receipt.authorizationSequence(),Instant.ofEpochSecond(receipt.confirmedSeconds(),receipt.confirmedNanos()),current.authorizationState(),current.revision(),current.cancelRevision(),replay,false);}
    private void event(Map<String,Object> v,TenantScope scope){v.put("type","REWARD_AUTHORIZED");v.put("reason","CONFIRMED_NOT_SENT");v.put("actor",scope.actorId());v.put("trace","auth-"+UUID.randomUUID());v.put("eventId",UUID.randomUUID().toString());v.put("auditId",UUID.randomUUID().toString());String payload=json.writeValueAsString(Map.of("schemaVersion",1,"rewardId",v.get("reward"),"authorizationState","CONFIRMED"));v.put("payload",payload);v.put("hash",Digests.sha256Hex(payload));one(events.outbox(v));one(events.audit(v));}
    private static Map<String,Object> values(TenantScope scope,String reward,String source,String digest){require(reward!=null && reward.matches("[a-f0-9]{64}") && ReferralAwardIdentity.sourceRequestId(scope.tenantId().value(),reward).equals(source) && digest!=null && digest.matches("sha256:[a-f0-9]{64}"));var v=new HashMap<String,Object>();v.put("tenant",scope.tenantId().value());v.put("reward",reward);v.put("source",source);return v;}
    private static void entry(TenantScope scope){Objects.requireNonNull(scope);scope.requirePermission("referral:confirm-authorization");require(!TransactionSynchronizationManager.isActualTransactionActive());}
    private static void one(int count){require(count==1);}
    private static void require(boolean ok){if(!ok)throw denied();}
    private static ConflictException denied(){return new ConflictException("REFERRAL_AUTHORIZATION_UNAVAILABLE","referral authorization requires fresh trusted proof and persistent eligibility");}
    /** 原永久回执与当前状态分离；dispatchAllowed固定false，必须走benefit的当前复核和独立投递协议。 */
    public record Result(String rewardId,String sourceRequestId,String stableClaimsDigest,long qualificationRevision,String confirmationId,long authorizationSequence,Instant confirmedAt,
            String currentState,long currentRevision,long cancelRevision,boolean replay,boolean dispatchAllowed) {}
}
