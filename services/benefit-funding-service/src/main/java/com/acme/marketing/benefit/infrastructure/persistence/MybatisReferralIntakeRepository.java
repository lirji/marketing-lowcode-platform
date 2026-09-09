package com.acme.marketing.benefit.infrastructure.persistence;

import com.acme.marketing.benefit.application.*;
import com.acme.marketing.benefit.application.ReferralIntakeIdentityPort.Binding;
import com.acme.marketing.benefit.application.ReferralIntakeConfirmationPort.*;
import com.acme.marketing.benefit.infrastructure.persistence.mapper.ReferralIntakeMapper;
import com.acme.marketing.platform.crypto.Digests;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

/** 固定上下文、当前确认水位及HELD意图原子仓储；取消栅栏不会被低水位或刷新时间覆盖。 */
@Repository
// Spring 的异常转换使用类代理，仓储实现必须允许代理继承。
public class MybatisReferralIntakeRepository implements ReferralIntakeRepository {
    private final ReferralIntakeMapper mapper;private final ObjectMapper json;
    public MybatisReferralIntakeRepository(ReferralIntakeMapper mapper,ObjectMapper json){this.mapper=mapper;this.json=json;}
    @Override public Context reserve(Binding binding,Instant issued,Instant expires) {
        transaction();var values=key(binding);values.put("binding_json",json.writeValueAsString(binding));time(values,"auth_issued",issued);time(values,"auth_expires",expires);
        mapper.reserve(values);return lock(binding);
    }
    @Override public Context lock(Binding binding){transaction();var current=read(mapper.lock(binding.identity().tenantId(),binding.identity().sourceRequestId()));require(current!=null && current.binding().equals(binding));return current;}
    @Override public Context find(String tenant,String request){return read(mapper.find(tenant,request));}
    @Override public Context observe(Binding binding,Result result) {
        Context old=lock(binding);var previous=old.confirmation();require(binding.identity().equals(result.identity()));
        if(old.quarantined())return old;
        // 较低水位不能更新取消或回执；调用者将返回待恢复，不据旧响应受理。
        if(previous!=null && result.currentRevision()<previous.currentRevision())return old;
        boolean conflict=previous!=null && (result.currentRevision()==previous.currentRevision() && !stable(previous).equals(stable(result))
                || previous.confirmationId()!=null && (!Objects.equals(previous.confirmationId(),result.confirmationId())
                    || previous.authorizationSequence()!=result.authorizationSequence() || !Objects.equals(previous.confirmedAt(),result.confirmedAt()))
                || previous.currentState()==State.REJECTED && result.currentState()!=State.REJECTED
                || previous.cancelRevision()>0 && (result.cancelRevision()!=previous.cancelRevision() || result.currentState()!=State.CANCEL_REQUESTED));
        var m=key(binding);m.put("expected_revision",previous==null?0:previous.currentRevision());m.put("quarantined",conflict);
        Result selected=conflict?previous:result;putResult(m,selected);require(mapper.observation(m)==1);
        if(selected.currentState()==State.CANCEL_REQUESTED || conflict){mapper.cancelHeld(m);mapper.cancelExpected(m);}
        return lock(binding);
    }
    @Override public void recordRisk(Binding binding,ReferralIntakeRiskPort.Decision decision) {
        Context current=lock(binding);if("REJECT".equals(current.riskAction()))return;
        var m=key(binding);m.put("risk_action",decision==null?"UNAVAILABLE":decision.action().name());m.put("risk_decision_id",decision==null?null:decision.decisionId());
        time(m,"risk_issued",decision==null?null:decision.issuedAt());time(m,"risk_expires",decision==null?null:decision.expiresAt());require(mapper.risk(m)==1);
    }
    @Override public void hold(Binding binding,String intent,Instant now) {
        var current=lock(binding);require(!current.quarantined() && !current.cancelled() && current.confirmation()!=null
                && current.confirmation().currentState()==State.CONFIRMED && "ALLOW".equals(current.riskAction()) && current.acceptedIntentId()==null);
        var m=key(binding);m.put("intent_id",intent);m.put("payload_hash",binding.identity().payloadHash());m.put("reward_id",binding.identity().rewardId());m.put("campaign_id",binding.campaignId());m.put("rule_id",binding.ruleId());
        time(m,"created",now);require(mapper.accepted(m)==1);require(mapper.heldOutbox(m)==1);require(mapper.expectedFact(m)==1);
    }
    private Map<String,Object> key(Binding b) {
        var m=new HashMap<String,Object>();m.put("tenant",b.identity().tenantId());m.put("source",b.identity().sourceSystem());m.put("request",b.identity().sourceRequestId());m.put("binding_digest",Digests.sha256Hex(json.writeValueAsString(b)));return m;
    }
    private Context read(Map<String,Object> row) {
        if(row==null)return null;
        String text=(String)row.get("binding_json");require(Digests.sha256Hex(text).equals(row.get("binding_digest")));
        Binding binding=json.readValue(text,Binding.class);Result result=null;
        if(row.get("confirmation_state")!=null)result=new Result(binding.identity(),State.valueOf((String)row.get("confirmation_state")),number(row,"current_revision"),number(row,"cancel_revision"),
                (String)row.get("confirmation_id"),number(row,"authorization_sequence"),time(row,"confirmed"),(String)row.get("rejection_id"),time(row,"response_issued"),time(row,"response_expires"));
        Object isolated=row.get("quarantined");boolean quarantined=isolated instanceof Boolean b?b:((Number)isolated).intValue()!=0;
        return new Context(binding,time(row,"auth_issued"),time(row,"auth_expires"),(String)row.get("risk_action"),(String)row.get("risk_decision_id"),result,quarantined,(String)row.get("accepted_intent_id"));
    }
    private static List<Object> stable(Result r){return Arrays.asList(r.identity(),r.currentState(),r.currentRevision(),r.cancelRevision(),r.confirmationId(),r.authorizationSequence(),r.confirmedAt(),r.rejectionId());}
    private static void putResult(Map<String,Object> m,Result r) {
        m.put("confirmation_state",r.currentState().name());m.put("current_revision",r.currentRevision());m.put("cancel_revision",r.cancelRevision());m.put("confirmation_id",r.confirmationId());m.put("authorization_sequence",r.authorizationSequence());m.put("rejection_id",r.rejectionId());
        time(m,"confirmed",r.confirmedAt());time(m,"response_issued",r.issuedAt());time(m,"response_expires",r.expiresAt());
    }
    private static void time(Map<String,Object> m,String key,Instant i){m.put(key,ReferralStorageTime.micros(i));m.put(key+"_nanos",ReferralStorageTime.remainder(i));}
    private static Instant time(Map<String,Object> m,String key){Object raw=m.get(key);LocalDateTime value=raw==null?null:raw instanceof LocalDateTime d?d:((java.sql.Timestamp)raw).toLocalDateTime();Object n=m.get(key+"_nanos");return ReferralStorageTime.restore(value,n==null?null:((Number)n).intValue());}
    private static long number(Map<String,Object> m,String k){Object v=m.get(k);return v==null?0:((Number)v).longValue();}
    private static void transaction(){require(TransactionSynchronizationManager.isActualTransactionActive());}
    private static void require(boolean valid){if(!valid)throw new IllegalStateException("referral intake persistence conflict");}
}
