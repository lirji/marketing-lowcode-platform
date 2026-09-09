package com.acme.marketing.referral.application.evidence;

import com.acme.marketing.platform.crypto.Digests;
import com.acme.marketing.platform.error.ConflictException;
import com.acme.marketing.platform.identity.TenantScope;
import com.acme.marketing.referral.ReferralOrderEvidence.*;
import com.acme.marketing.referral.application.ReferralRepository;
import com.acme.marketing.referral.application.evidence.ReferralEvidencePreparation.*;
import com.acme.marketing.referral.application.evidence.ProtectedReferralEvidencePort.*;
import com.acme.marketing.referral.application.evidence.ReferralEvidenceRepository.*;
import com.acme.marketing.referral.application.evidence.TrustedReferralEvidencePort.*;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;

/** 事实接收短事务只更新单订单账本及待投影任务，不持锁调用KMS、不直接改变资格或发奖。 */
@Service
public class ReferralEvidenceIntakeService {
    private final ReferralEvidenceRepository repository;
    private final ReferralRepository participants;
    private final TrustedReferralEvidencePort sources;
    private final ProtectedReferralEvidencePort protection;
    private final Clock clock;
    private final long keyVersion;
    private final Duration maximumLifetime;
    private final TransactionTemplate transactions;
    /** 真实证据寿命默认0拒绝，不能沿用开发配置作为生产准入。 */
    public ReferralEvidenceIntakeService(ReferralEvidenceRepository repository,ReferralRepository participants,TrustedReferralEvidencePort sources,
            ProtectedReferralEvidencePort protection,Clock clock,PlatformTransactionManager manager,
            @Value("${marketing.referral.subject-key-version:0}") long keyVersion,@Value("${marketing.referral.evidence-max-lifetime-seconds:0}") long lifetimeSeconds) {
        this.repository=repository;this.participants=participants;this.sources=sources;this.protection=protection;this.clock=clock;this.keyVersion=keyVersion;
        maximumLifetime=lifetimeSeconds>0?Duration.ofSeconds(lifetimeSeconds):null;
        transactions=new TransactionTemplate(manager);transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);transactions.setTimeout(3);
    }
    /** 原事件同内容回放原回执；异内容先提交原订单隔离/审计，再返回受控冲突，不能回滚隔离。 */
    public Result accept(TenantScope scope,Envelope envelope) {
        if(TransactionSynchronizationManager.isActualTransactionActive())throw rejected("REFERRAL_OUTER_TRANSACTION_FORBIDDEN");
        Objects.requireNonNull(scope);Objects.requireNonNull(envelope);scope.requirePermission("referral:evidence");
        try{return acceptOutside(scope,envelope);}catch(ConflictException safe){throw safe;}catch(RuntimeException failure){throw unavailable();}
    }
    private Result acceptOutside(TenantScope scope,Envelope envelope) {
        var binding=new RequestBinding(scope.tenantId().value(),envelope.eventId(),Digests.sha256Hex(envelope.payload()),"EVIDENCE_INTAKE_V1","INTERNAL","referral.evidence.accept");
        Accepted accepted=external(()->sources.verify(scope,envelope,binding));
        if(accepted==null || !binding.equals(accepted.binding()) || !envelope.eventId().equals(accepted.eventId()))throw unavailable();
        var input=accepted.input();var snapshot=input.observation().snapshot();OrderKey order=OrderKey.of(snapshot.scope());
        if(!order.tenantId().equals(scope.tenantId().value()) || input.keyVersion()!=keyVersion || !current(input,clock.instant()))throw unavailable();
        var event=new EventKey(order.tenantId(),accepted.issuer(),order.sourceSystem(),accepted.eventId());
        Header incomingHeader=header(order,input.subjectKey(),input.keyVersion(),snapshot,Purpose.HISTORY,false);
        Sealed incoming=sealSnapshot(incomingHeader,snapshot);
        // 每次CAS失败都回到事务外重新读取/解密/加密，有界重试不携带数据库锁。
        for(int attempt=0;attempt<3;attempt++) {
            Inbox previous=repository.readInbox(event);
            if(previous!=null && previous.result()==null)throw unavailable();
            boolean conflict=previous!=null && (!previous.businessDigest().equals(incoming.businessDigest()) || !previous.originalOrder().equals(order));
            OrderKey target=conflict?previous.originalOrder():order;
            if(previous!=null && !conflict) {
                scope.requireOrganization(snapshot.scope().organizationId());scope.requireShop(snapshot.scope().shopId());
                try{return replay(event,incoming,order,input);}catch(RetryPreparationException retry){continue;}
            }
            StoredOrder observed=repository.readOrder(target);
            if(observed!=null && observed.rowVersion()==0)throw unavailable();
            // 已有订单按原Scope授权：漂移只能隔离原授权资源，绝不授予新组织/店铺访问。
            if(observed==null) { scope.requireOrganization(snapshot.scope().organizationId());scope.requireShop(snapshot.scope().shopId()); }
            else { scope.requireOrganization(observed.state().header().organizationId());scope.requireShop(observed.state().header().shopId()); }
            State old=observed==null?null:openState(observed.state());
            if(!current(input,clock.instant()))throw unavailable();
            if(conflict) {
                if(observed==null || old==null)throw unavailable();
                var quarantined=new State(old.latest(),old.firstReceivedAt(),old.settledAtAnchor(),old.firstSettlementReceivedAt(),true,old.historyPendingRevision());
                Sealed sealed=quarantined.equals(old)?observed.state():sealState(header(target,observed.state().header().subjectKey(),observed.state().header().keyVersion(),quarantined.latest(),Purpose.CURRENT,true),quarantined);
                try { quarantineConflict(scope,envelope,event,previous,observed,sealed,input); }
                catch(RetryPreparationException retry){continue;}
                throw rejected("REFERRAL_EVIDENCE_EVENT_CONFLICT");
            }
            Sealed priorHistory=repository.readHistory(order,snapshot.revision());
            HistoryRead history=priorHistory==null?new HistoryRead(snapshot.revision(),new NotSeen(),null):new HistoryRead(snapshot.revision(),new Seen(openSnapshot(priorHistory)),priorHistory.businessDigest());
            CurrentRead read=observed==null?CurrentRead.absent():new CurrentRead(observed.rowVersion(),observed.state().header().subjectKey(),observed.state().header().keyVersion(),old);
            Prepared prepared=ReferralEvidencePreparation.prepare(read,history,input,clock.instant(),maximumLifetime);
            if(prepared.result().state()==null || prepared.result().reason()==com.acme.marketing.referral.ReferralOrderEvidenceMerger.Reason.FUTURE_OR_INCONSISTENT_TIME)throw unavailable();
            State next=prepared.result().state();boolean changed=!Objects.equals(old,next);
            Sealed nextSealed=!changed?observed.state():sealState(header(order,observed==null?input.subjectKey():observed.state().header().subjectKey(),input.keyVersion(),next.latest(),Purpose.CURRENT,next.quarantined()),next);
            try{return apply(scope,envelope,event,order,incoming,observed,priorHistory,prepared,nextSealed,changed,input);}catch(RetryPreparationException retry){/* 下一轮在事务外重新准备。 */}
        }
        throw rejected("REFERRAL_EVIDENCE_CONCURRENT_CHANGE");
    }
    private Result replay(EventKey event,Sealed incoming,OrderKey order,VerifiedInput input) {
        return transactions.execute(tx->{anchor(event.tenantId(),input.keyVersion());Inbox locked=repository.lockInbox(event,incoming.businessDigest(),order,clock.instant());
            if(!locked.businessDigest().equals(incoming.businessDigest()) || !locked.originalOrder().equals(order) || locked.result()==null)throw new RetryPreparationException();return locked.result();});
    }
    private Result apply(TenantScope scope,Envelope envelope,EventKey event,OrderKey order,Sealed incoming,StoredOrder observed,Sealed priorHistory,
            Prepared prepared,Sealed next,boolean changed,VerifiedInput input) {
        return transactions.execute(tx->{
            long anchor=anchor(order.tenantId(),input.keyVersion());Inbox receipt=repository.lockInbox(event,incoming.businessDigest(),order,clock.instant());
            if(!receipt.businessDigest().equals(incoming.businessDigest()) || !receipt.originalOrder().equals(order))throw new RetryPreparationException();
            if(receipt.result()!=null)return receipt.result();
            StoredOrder locked=repository.lockOrder(order,UUID.randomUUID().toString(),clock.instant());Sealed history=repository.lockHistory(order,incoming.header().revision());
            Instant now=clock.instant();
            if(history!=null && (!history.header().order().equals(order) || history.header().revision()!=incoming.header().revision()))throw unavailable();
            if(!same(observed,locked) || !prepared.mayCommit(lockedVersion(locked,history,incoming.header().revision()),anchor,now))throw new RetryPreparationException();
            if(priorHistory==null)repository.insertHistory(incoming,input.observation().receivedAt(),now);
            long version=changed?repository.replaceOrder(locked,next,now):locked.rowVersion();
            Result result=new Result(locked.resourceId(),prepared.result().outcome().name(),prepared.result().reason().name(),version);
            repository.completeInbox(event,result,now);
            if(changed)repository.changed(locked,version,result.reason(),scope.actorId(),envelope.traceId(),now);
            return result;
        });
    }
    private void quarantineConflict(TenantScope scope,Envelope envelope,EventKey event,Inbox previous,StoredOrder observed,Sealed next,VerifiedInput input) {
        transactions.executeWithoutResult(tx->{
            anchor(event.tenantId(),input.keyVersion());Inbox lockedReceipt=repository.lockInbox(event,previous.businessDigest(),previous.originalOrder(),clock.instant());
            if(!lockedReceipt.equals(previous))throw new RetryPreparationException();
            StoredOrder locked=repository.lockOrder(previous.originalOrder(),UUID.randomUUID().toString(),clock.instant());
            Instant now=clock.instant();
            if(!same(observed,locked) || !current(input,now) || locked.state().header().keyVersion()!=input.keyVersion())throw new RetryPreparationException();
            // 隔离是独立安全状态，不能假定业务摘要会随隔离元数据变化。
            if(!observed.state().header().quarantined()) {
                long version=repository.replaceOrder(locked,next,now);repository.changed(locked,version,"INBOX_CONTENT_CONFLICT",scope.actorId(),envelope.traceId(),now);
            }
            repository.conflict(locked,scope.actorId(),envelope.traceId(),now);
        });
    }
    private long anchor(String tenant,long acceptedVersion) { Long version=participants.subjectIndexVersion(tenant);if(version==null || version!=keyVersion || version!=acceptedVersion)throw unavailable();return version; }
    private boolean current(VerifiedInput input,Instant now) { return maximumLifetime!=null && Duration.between(input.issuedAt(),input.expiresAt()).compareTo(maximumLifetime)<=0 && !now.isBefore(input.issuedAt()) && now.isBefore(input.expiresAt()); }
    private static boolean same(StoredOrder expected,StoredOrder actual) {
        if(expected==null)return actual.rowVersion()==0;
        return expected.key().equals(actual.key()) && expected.resourceId().equals(actual.resourceId()) && expected.rowVersion()==actual.rowVersion()
                && actual.state()!=null && expected.state().header().equals(actual.state().header()) && expected.state().businessDigest().equals(actual.state().businessDigest());
    }
    private static LockedVersion lockedVersion(StoredOrder order,Sealed history,long revision) {
        var state=order.state();var stamp=new HistoryStamp(revision,history==null?Presence.ABSENT:Presence.PRESENT,history==null?null:history.businessDigest());
        return new LockedVersion(order.key(),order.rowVersion(),state==null?null:state.header().subjectKey(),state==null?0:state.header().keyVersion(),stamp);
    }
    private static Header header(OrderKey key,String subject,long version,Snapshot snapshot,Purpose purpose,boolean quarantined) { return new Header(key,subject,version,snapshot.scope().organizationId(),snapshot.scope().shopId(),snapshot.revision(),purpose,quarantined); }
    private Sealed sealSnapshot(Header header,Snapshot snapshot) { Sealed result=external(()->protection.sealSnapshot(header,snapshot));checkSealed(header,result);return result; }
    private Sealed sealState(Header header,State state) { Sealed result=external(()->protection.sealState(header,state));checkSealed(header,result);return result; }
    private State openState(Sealed sealed) { if(sealed==null || sealed.header().purpose()!=Purpose.CURRENT)throw unavailable();State result=external(()->protection.openState(sealed));if(result==null || result.quarantined()!=sealed.header().quarantined())throw unavailable();checkPlain(sealed.header(),result.latest());return result; }
    private Snapshot openSnapshot(Sealed sealed) { if(sealed.header().purpose()!=Purpose.HISTORY)throw unavailable();Snapshot result=external(()->protection.openSnapshot(sealed));checkPlain(sealed.header(),result);return result; }
    private static void checkPlain(Header header,Snapshot snapshot) { if(snapshot==null || !header.order().equals(OrderKey.of(snapshot.scope())) || header.revision()!=snapshot.revision() || !header.organizationId().equals(snapshot.scope().organizationId()) || !header.shopId().equals(snapshot.scope().shopId()))throw unavailable(); }
    private static void checkSealed(Header header,Sealed sealed) { if(sealed==null || !header.equals(sealed.header()))throw unavailable(); }
    private static <T> T external(Supplier<T> call) { try{return call.get();}catch(RuntimeException failure){throw unavailable();} }
    private static ConflictException unavailable(){return rejected("REFERRAL_EVIDENCE_UNAVAILABLE");}
    private static ConflictException rejected(String code){return new ConflictException(code,"referral evidence unavailable or conflicts with prior receipt");}
    private static final class RetryPreparationException extends RuntimeException { @java.io.Serial private static final long serialVersionUID=1L; }
}
