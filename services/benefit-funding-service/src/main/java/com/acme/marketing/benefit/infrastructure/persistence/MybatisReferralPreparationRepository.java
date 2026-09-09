package com.acme.marketing.benefit.infrastructure.persistence;

import com.acme.marketing.benefit.application.*;
import com.acme.marketing.benefit.application.ReferralCandidateSnapshotService.Snapshot;
import com.acme.marketing.benefit.domain.ReferralAwardPreparation;
import com.acme.marketing.benefit.domain.ReferralAwardPreparation.*;
import com.acme.marketing.benefit.infrastructure.persistence.mapper.ReferralPreparationMapper;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 当前读+封闭领域转换+全身份CAS；仓储不解密、不访问KMS、不提交独立事务。 */
@Repository
// Spring 的异常转换使用类代理，仓储实现必须允许代理继承。
public class MybatisReferralPreparationRepository implements ReferralPreparationRepository {
    private final ReferralPreparationMapper mapper;private final Clock clock;
    public MybatisReferralPreparationRepository(ReferralPreparationMapper mapper,Clock clock){this.mapper=mapper;this.clock=clock;}
    @Override public Stored prepare(Identity identity,Snapshot snapshot,String owner,Instant leaseUntil) {
        transaction();require(snapshot!=null && ReferralCandidateSnapshotService.binding(identity).equals(snapshot.bindingDigest()));
        var initial=ReferralAwardPreparation.prepare(identity,owner,clock.instant(),leaseUntil);
        mapper.reserve(values(new Stored(initial,snapshot)));
        Stored existing=read(mapper.lock(identity.tenantId(),identity.sourceRequestId()));
        require(existing!=null && existing.state().identity().equals(identity));
        // 首次写等待后租约若已失效则整事务回滚；既有永久状态仍可以读出用于明确接管。
        if(existing.state().equals(initial))require(clock.instant().isBefore(leaseUntil));
        return existing;
    }
    @Override public Stored apply(Identity identity,Command command) {
        transaction();Stored current=read(mapper.lock(identity.tenantId(),identity.sourceRequestId()));
        require(current!=null && current.state().identity().equals(identity));
        var before=current.state();Instant now=clock.instant();
        var next=switch(command) {
            case TakeOver c->before.takeOver(identity,c.owner(),now,c.until());
            case Begin c->before.beginConfirmation(identity,c.owner(),c.admission(),now);
            case Unknown c->before.confirmationUnknown(identity,c.owner(),now);
            case Confirm c->before.recordConfirmation(identity,c.owner(),c.receipt(),now);
            case Reject c->before.rejectConfirmation(identity,c.owner(),c.rejection(),now);
            case Accept c->before.acceptLocally(identity,c.owner(),c.intentId(),c.admission(),now);
        };
        if(next==before)return current;
        var stored=new Stored(next,current.snapshot());var values=values(stored);
        values.put("expected_version",before.stateVersion());values.put("expected_fence",before.lease().fence());values.put("expected_owner",before.lease().owner());
        require(mapper.compareAndSet(values)==1);
        // 冲突receipt以隔离状态返回，不能在这里抛异常撤销刚保存的quarantine。
        return stored;
    }
    @Override public Stored lock(Identity identity){transaction();Stored s=read(mapper.lock(identity.tenantId(),identity.sourceRequestId()));require(s!=null && s.state().identity().equals(identity));return s;}
    @Override public Optional<Stored> find(String tenant,String sourceRequestId){return Optional.ofNullable(read(mapper.find(tenant,sourceRequestId)));}

    private static Map<String,Object> values(Stored stored) {
        var s=stored.state();var i=s.identity();var m=new HashMap<String,Object>();
        m.put("tenant_id",i.tenantId());m.put("source_system",i.sourceSystem());m.put("source_request_id",i.sourceRequestId());m.put("reward_id",i.rewardId());
        m.put("claims_digest",i.stableClaimsDigest());m.put("payload_hash",i.payloadHash());m.put("qualification_revision",i.qualificationRevision());
        m.put("phase",s.phase().name());m.put("state_version",s.stateVersion());m.put("lease_owner",s.lease().owner());m.put("lease_fence",s.lease().fence());
        time(m,"lease_acquired",s.lease().acquiredAt());time(m,"lease_expires",s.lease().expiresAt());
        m.put("confirmation_id",s.receipt()==null?null:s.receipt().confirmationId());time(m,"confirmed_at",s.receipt()==null?null:s.receipt().confirmedAt());
        m.put("rejection_id",s.rejection()==null?null:s.rejection().decisionId());time(m,"rejected_at",s.rejection()==null?null:s.rejection().decidedAt());
        m.put("accepted_intent_id",s.acceptedIntentId());m.put("quarantined",s.quarantined());
        m.put("snapshot_binding",stored.snapshot().bindingDigest());m.put("encryption_key_id",stored.snapshot().encrypted().keyId());m.put("candidate_cipher",stored.snapshot().encrypted().ciphertext());
        return m;
    }
    private static Stored read(Map<String,Object> m) {
        if(m==null)return null;
        var i=new Identity(text(m,"tenant_id"),text(m,"source_system"),text(m,"source_request_id"),text(m,"reward_id"),text(m,"claims_digest"),text(m,"payload_hash"),number(m,"qualification_revision"));
        var lease=new Lease(text(m,"lease_owner"),number(m,"lease_fence"),time(m,"lease_acquired"),time(m,"lease_expires"));
        Receipt receipt=m.get("confirmation_id")==null?null:new Receipt(i,text(m,"confirmation_id"),time(m,"confirmed_at"));
        Rejection rejection=m.get("rejection_id")==null?null:new Rejection(i,text(m,"rejection_id"),time(m,"rejected_at"));
        Object isolated=m.get("quarantined");boolean quarantined=isolated instanceof Boolean b?b:((Number)isolated).intValue()!=0;
        var state=new ReferralAwardPreparation(i,Phase.valueOf(text(m,"phase")),lease,number(m,"state_version"),receipt,text(m,"accepted_intent_id"),rejection,quarantined);
        var snapshot=new Snapshot(text(m,"snapshot_binding"),new ReferralCandidateProtectionPort.Encrypted(text(m,"encryption_key_id"),(byte[])m.get("candidate_cipher")));
        require(ReferralCandidateSnapshotService.binding(i).equals(snapshot.bindingDigest()));return new Stored(state,snapshot);
    }
    private static void time(Map<String,Object> m,String key,Instant value){m.put(key,ReferralStorageTime.micros(value));m.put(key+"_nanos",ReferralStorageTime.remainder(value));}
    private static Instant time(Map<String,Object> m,String key) {
        Object value=m.get(key);LocalDateTime micros=value==null?null:value instanceof LocalDateTime d?d:((java.sql.Timestamp)value).toLocalDateTime();
        Object remainder=m.get(key+"_nanos");return ReferralStorageTime.restore(micros,remainder==null?null:((Number)remainder).intValue());
    }
    private static String text(Map<String,Object> m,String key){return (String)m.get(key);}
    private static long number(Map<String,Object> m,String key){return ((Number)m.get(key)).longValue();}
    private static void transaction(){require(TransactionSynchronizationManager.isActualTransactionActive());}
    private static void require(boolean value){if(!value)throw new IllegalStateException("referral preparation persistence conflict");}
}
