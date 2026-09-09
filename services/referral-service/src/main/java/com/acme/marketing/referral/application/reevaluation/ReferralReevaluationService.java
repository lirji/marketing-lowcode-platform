package com.acme.marketing.referral.application.reevaluation;

import com.acme.marketing.platform.crypto.Digests;
import com.acme.marketing.platform.error.ConflictException;
import com.acme.marketing.platform.identity.TenantScope;
import com.acme.marketing.referral.application.authorization.ReferralAuthorizationProofPort.Reward;
import com.acme.marketing.referral.application.qualification.ReferralProjectionEnqueuePort;
import com.acme.marketing.referral.application.qualification.ReferralProjectionEnqueuePort.Signal;
import com.acme.marketing.referral.infrastructure.persistence.mapper.*;
import com.acme.marketing.referral.infrastructure.persistence.mapper.ReferralReevaluationMapper.Stored;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import tools.jackson.databind.ObjectMapper;

/** 管理员只能请求重新计算；资格、永久失效身份、配额、在线授权仍由原流程判断。 */
@Service
public class ReferralReevaluationService {
    private static final DateTimeFormatter SQL=DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS").withZone(ZoneOffset.UTC);
    private final ReferralAuthorizationMapper rewards;
    private final ReferralReevaluationMapper commands;
    private final ReferralProjectionEnqueuePort queue;
    private final ReferralRewardMapper events;
    private final ObjectMapper json;
    private final Clock clock;
    private final int maximum;
    private final TransactionTemplate tx;

    /** 同步排队数量有上界，超出整笔拒绝，避免部分阶梯关系被静默漏评。 */
    public ReferralReevaluationService(ReferralAuthorizationMapper rewards,ReferralReevaluationMapper commands,
            ReferralProjectionEnqueuePort queue,ReferralRewardMapper events,ObjectMapper json,Clock clock,
            PlatformTransactionManager manager,@Value("${marketing.referral.reevaluation.max-relations:1000}") int maximum){
        this.rewards=rewards;this.commands=commands;this.queue=queue;this.events=events;this.json=json;this.clock=clock;this.maximum=maximum;
        if(maximum<1 || maximum>1000)throw new IllegalArgumentException("reevaluation bound must be 1..1000");
        tx=new TransactionTemplate(manager);tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);tx.setTimeout(5);
    }
    /** 相同操作者同键永远返回原排队收据；不同内容拒绝，HTTP重试不会再次唤醒资格任务。 */
    public Receipt submit(TenantScope scope,String rewardId,String key,String reason){
        Objects.requireNonNull(scope);scope.requirePermission("referral:reevaluate");require(!TransactionSynchronizationManager.isActualTransactionActive());
        if(rewardId==null || !rewardId.matches("[a-f0-9]{64}") || key==null || !key.matches("[A-Za-z0-9._:-]{8,128}")
                || reason==null || reason.isBlank() || reason.length()>128)throw new IllegalArgumentException("reward, idempotency key and bounded reason required");
        String normalized=reason.strip();var v=new HashMap<String,Object>();v.put("tenant",scope.tenantId().value());v.put("reward",rewardId);v.put("lock",false);
        Reward hint=rewards.reward(v);check(scope,hint);v.put("participant",hint.participantId());v.put("actor",scope.actorId());v.put("key",key);v.put("reason",normalized);
        v.put("digest",Digests.sha256Hex(json.writeValueAsString(new TreeMap<>(Map.of("rewardId",rewardId,"reason",normalized)))));
        return tx.execute(status->{
            require(rewards.participant(v)!=null);v.put("lock",true);Reward current=rewards.reward(v);check(scope,current);require(current.participantId().equals(hint.participantId()));
            Stored original=commands.receipt(v);if(original!=null){require(original.rewardId().equals(rewardId) && original.digest().equals(v.get("digest")));return receipt(original);}
            v.put("relation",current.relationId());v.put("limit",maximum+1);List<String> relations=commands.relations(v);require(!relations.isEmpty() && relations.size()<=maximum);
            v.put("operation",UUID.randomUUID().toString());v.put("now",SQL.format(clock.instant()));v.put("count",relations.size());one(commands.insert(v));
            for(String relation:relations)queue.enqueue(new Signal(current.tenantId(),current.participantId(),relation,null,0,"MANUAL_REEVALUATION"));
            event(v,rewardId);return receipt(Objects.requireNonNull(commands.receipt(v)));
        });
    }
    private void event(Map<String,Object> values,String rewardId){
        var v=new HashMap<>(values);v.put("reward",values.get("operation"));v.put("revision",1);v.put("type","REWARD_REEVALUATION_REQUESTED");v.put("trace",values.get("operation"));
        v.put("eventId",UUID.randomUUID().toString());v.put("auditId",UUID.randomUUID().toString());
        String payload=json.writeValueAsString(Map.of("schemaVersion",1,"operationId",values.get("operation"),"rewardId",rewardId,"state","QUEUED","relationCount",values.get("count")));
        v.put("payload",payload);v.put("hash",Digests.sha256Hex(payload));one(events.outbox(v));one(events.audit(v));
    }
    private static Receipt receipt(Stored row){return new Receipt(row.operationId(),row.rewardId(),row.state(),row.relationCount(),row.createdAt());}
    private static void check(TenantScope scope,Reward r){require(r!=null && r.tenantId().equals(scope.tenantId().value()));scope.requireOrganization(r.organizationId());scope.requireShop(r.shopId());}
    private static void one(int rows){require(rows==1);}
    private static void require(boolean valid){if(!valid)throw new ConflictException("REFERRAL_REEVALUATION_UNAVAILABLE","reevaluation conflicts or exceeds available scope/budget");}
    /** 收据不包含用户原文、原因、密文或任何发奖许可。 */
    public record Receipt(String operationId,String rewardId,String state,int relationCount,Instant createdAt) {}
}
