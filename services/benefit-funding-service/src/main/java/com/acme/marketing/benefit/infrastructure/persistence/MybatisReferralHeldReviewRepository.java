package com.acme.marketing.benefit.infrastructure.persistence;
import com.acme.marketing.benefit.application.*;
import com.acme.marketing.benefit.application.ReferralIntakeIdentityPort.Binding;
import com.acme.marketing.benefit.infrastructure.persistence.mapper.ReferralHeldReviewMapper;
import com.acme.marketing.platform.crypto.Digests;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;
/** 最近复核观察使用永久固定身份和序号CAS；取消/隔离不因一次成功查询被抹除。 */
@Repository
public final class MybatisReferralHeldReviewRepository implements ReferralHeldReviewRepository {
    private final ReferralHeldReviewMapper mapper;private final ReferralIntakeRepository intake;private final ObjectMapper json;
    public MybatisReferralHeldReviewRepository(ReferralHeldReviewMapper mapper,ReferralIntakeRepository intake,ObjectMapper json){this.mapper=mapper;this.intake=intake;this.json=json;}
    @Override public Observation record(Binding binding,Status status,long revision,long cancel,Instant now,Instant until) {
        require(TransactionSynchronizationManager.isActualTransactionActive());var context=intake.lock(binding);
        require(status!=null && now!=null && revision>=0 && cancel>=0 && cancel<=revision && (status!=Status.CHECKED || until!=null && now.isBefore(until)));
        require(context.confirmation()==null?revision==0:revision==context.confirmation().currentRevision());
        require(context.confirmation()==null?cancel==0:cancel==context.confirmation().cancelRevision());
        if(context.quarantined())status=Status.QUARANTINED;else if(context.cancelled())status=Status.CANCELLED;
        var m=new HashMap<String,Object>();m.put("tenant",binding.identity().tenantId());m.put("request",binding.identity().sourceRequestId());m.put("digest",Digests.sha256Hex(json.writeValueAsString(binding)));time(m,"checked",now);
        mapper.reserve(m);var row=mapper.lock(binding.identity().tenantId(),binding.identity().sourceRequestId());require(row!=null && m.get("digest").equals(row.get("binding_digest")));
        var previous=read(row);if(previous.status()==Status.QUARANTINED)status=Status.QUARANTINED;else if(previous.status()==Status.CANCELLED && status!=Status.QUARANTINED)status=Status.CANCELLED;
        require(revision>=previous.currentRevision() && cancel>=previous.cancelRevision());m.put("sequence",previous.sequence());m.put("next",Math.addExact(previous.sequence(),1));m.put("status",status.name());m.put("revision",revision);m.put("cancel",cancel);time(m,"valid_until",status==Status.CHECKED?until:null);
        require(mapper.update(m)==1);return read(mapper.lock(binding.identity().tenantId(),binding.identity().sourceRequestId()));
    }
    @Override public Observation find(String tenant,String request){return read(mapper.find(tenant,request));}
    private static Observation read(Map<String,Object> row){return row==null?null:new Observation(number(row,"check_sequence"),Status.valueOf((String)row.get("state_name")),number(row,"current_revision"),number(row,"cancel_revision"),time(row,"checked"),time(row,"valid_until"));}
    private static long number(Map<String,Object> row,String k){return ((Number)row.get(k)).longValue();}
    private static void time(Map<String,Object> row,String k,Instant value){row.put(k,ReferralStorageTime.micros(value));row.put(k+"_nanos",ReferralStorageTime.remainder(value));}
    private static Instant time(Map<String,Object> row,String k){var raw=row.get(k);LocalDateTime value=raw==null?null:raw instanceof LocalDateTime d?d:((java.sql.Timestamp)raw).toLocalDateTime();var n=row.get(k+"_nanos");return ReferralStorageTime.restore(value,n==null?null:((Number)n).intValue());}
    private static void require(boolean value){if(!value)throw new IllegalStateException("referral held review persistence conflict");}
}
