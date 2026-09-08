package com.acme.marketing.referral.application;
import com.acme.marketing.platform.crypto.Digests;
import com.acme.marketing.platform.error.ConflictException;
import com.acme.marketing.platform.identity.TenantScope;
import com.acme.marketing.referral.domain.*;
import com.acme.marketing.referral.application.TrustedReferralSubjectPort.*;
import com.acme.marketing.referral.application.ReferralBindingPermitPort.Permit;
import com.acme.marketing.referral.application.ReferralBindingRepository.Role;
import com.acme.marketing.referral.application.ReferralInviteRepository.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import tools.jackson.databind.ObjectMapper;
/** 首次有效绑定只固定归因并置为BOUND，不以绑定成功代替新客/首单/风控或奖励。 */
@Service
public class ReferralBindingService {
    private final ReferralBindingRepository repository;
    private final ReferralRepository participants;
    private final ReferralInviteRepository invites;
    private final TokenBindingReadPort tokens;
    private final TrustedReferralSubjectPort subjects;
    private final ReferralBindingPermitPort permits;
    private final Clock clock;
    private final ObjectMapper json;
    private final long keyVersion;
    private final TransactionTemplate transactions;
    /** 同一主库RC事务；受信来源与用户验签永远在其外部执行。 */
    public ReferralBindingService(ReferralBindingRepository repository,ReferralRepository participants,ReferralInviteRepository invites,
            TokenBindingReadPort tokens,TrustedReferralSubjectPort subjects,ReferralBindingPermitPort permits,Clock clock,ObjectMapper json,
            PlatformTransactionManager manager,@Value("${marketing.referral.subject-key-version:0}") long keyVersion) {
        this.repository=repository;this.participants=participants;this.invites=invites;this.tokens=tokens;this.subjects=subjects;this.permits=permits;this.clock=clock;this.json=json;this.keyVersion=keyVersion;
        transactions=new TransactionTemplate(manager);transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);transactions.setTimeout(3);
    }
    /** 原成功同key永久回放；新关系/新回执均须在最终锁后检查身份、token、历史条款与绝对截止。 */
    public ReferralRelation bind(TenantScope scope,Bind request) {
        if(TransactionSynchronizationManager.isActualTransactionActive()) throw rejected("REFERRAL_OUTER_TRANSACTION_FORBIDDEN");
        Objects.requireNonNull(scope);Objects.requireNonNull(request);scope.requirePermission("referral:participate");ReferralInputs.key(scope.actorId(),128);
        String tenant=scope.tenantId().value(),tokenHash=Digests.sha256Hex(request.inviteToken());
        Token located=tokens.locate(tenant,tokenHash);if(located==null) throw rejected("REFERRAL_TOKEN_INVALID");
        Owned owner=owned(scope,located.participantId(),false);var p=owner.participant();
        String bodyHash=Digests.sha256Hex(json.writeValueAsString(new TreeMap<>(Map.of("inviteToken",request.inviteToken(),"consentVersion",request.consentVersion(),"consentHash",request.consentHash()))));
        var binding=new RequestBinding(tenant,p.campaignId(),p.organizationId(),p.shopId(),"BIND_V1",request.idempotencyKey(),bodyHash,"INTERNAL","referral.binding.bind");
        Subject invitee;
        try { invitee=subjects.resolve(scope,request.assertion(),binding); } catch(RuntimeException unavailable) { throw unavailable(); }
        if(invitee==null || !tenant.equals(invitee.tenantId()) || keyVersion<=0 || invitee.keyVersion()!=keyVersion || !binding.equals(invitee.binding()) || !identityCurrent(invitee,clock.instant())) throw unavailable();
        Permit permit;
        try { permit=permits.forParticipant(p); } catch(RuntimeException unavailable) { permit=null; }
        String hash=Digests.sha256Hex(json.writeValueAsString(List.of("BIND_V1",tenant,invitee.subjectKey(),invitee.keyVersion(),tokenHash,p.participantId(),p.campaignId(),p.organizationId(),p.shopId(),request.consentVersion(),request.consentHash())));
        Permit observed=permit;
        return Objects.requireNonNull(transactions.execute(tx->bindLocked(scope,request,tokenHash,located,owner,invitee,observed,hash)));
    }
    private ReferralRelation bindLocked(TenantScope scope,Bind request,String tokenHash,Token located,Owned owner,Subject invitee,Permit permit,String requestHash) {
        String tenant=scope.tenantId().value();var p=owner.participant();
        Long anchor=participants.subjectIndexVersion(tenant);if(anchor==null || anchor!=invitee.keyVersion() || owner.keyVersion()!=anchor) throw unavailable();
        var command=repository.lockCommand(tenant,invitee.subjectKey(),request.idempotencyKey(),requestHash,clock.instant());
        if(!Digests.constantTimeEquals(command.requestHash(),requestHash)) throw rejected("IDEMPOTENCY_PAYLOAD_CONFLICT");
        if(command.relationId()!=null) return checkedRelation(scope,repository.relationById(tenant,command.relationId()));
        if(owner.subjectKey().equals(invitee.subjectKey())) throw rejected("REFERRAL_SELF_INVITATION");
        // 所有双主体锁严格按同一HMAC字典序，邀请人角色只读已有行，不自动创建。
        Role inviterRole,inviteeRole;
        if(owner.subjectKey().compareTo(invitee.subjectKey())<0) {
            inviterRole=repository.lockInviter(tenant,p.campaignId(),owner.subjectKey());inviteeRole=repository.lockInvitee(tenant,p.campaignId(),invitee,clock.instant());
        } else {
            inviteeRole=repository.lockInvitee(tenant,p.campaignId(),invitee,clock.instant());inviterRole=repository.lockInviter(tenant,p.campaignId(),owner.subjectKey());
        }
        if(inviterRole==null || !"INVITER".equals(inviterRole.role()) || !p.participantId().equals(inviterRole.participantId()) || inviterRole.keyVersion()!=invitee.keyVersion()
                || !"INVITEE".equals(inviteeRole.role()) || inviteeRole.keyVersion()!=invitee.keyVersion()) throw rejected("REFERRAL_SUBJECT_ROLE_CONFLICT");
        Owned current=owned(scope,p.participantId(),true);
        if(!p.equals(current.participant()) || !owner.subjectKey().equals(current.subjectKey()) || current.keyVersion()!=invitee.keyVersion()) throw unavailable();
        Token locked=tokens.lock(tenant,tokenHash);
        if(locked==null || !located.tokenId().equals(locked.tokenId()) || !p.participantId().equals(locked.participantId()) || !tokenHash.equals(locked.tokenHash())) throw rejected("REFERRAL_TOKEN_INVALID");
        var prior=repository.relation(tenant,p.campaignId(),invitee.subjectKey());
        // 查询relation也可能等待，因此所有最终时间判断都在最后一次锁定读取之后。
        Instant now=clock.instant();
        if(!identityCurrent(invitee,now)) throw unavailable();
        if(locked.revokedAt()!=null || !now.isBefore(locked.expiresAt())) throw rejected("REFERRAL_TOKEN_INVALID");
        Instant deadline=ReferralBindingTiming.deadline(current.participant(),permit,request.consentVersion(),request.consentHash(),now);
        if(prior!=null) {
            checkedRelation(scope,prior);
            if(!prior.participantId().equals(p.participantId())) throw rejected("REFERRAL_ALREADY_BOUND");
            if(!prior.tokenId().equals(locked.tokenId()) || !prior.consentVersion().equals(request.consentVersion()) || !prior.consentHash().equals(request.consentHash())) throw rejected("REFERRAL_BINDING_CONTENT_CONFLICT");
            if(!Objects.equals(inviteeRole.relationId(),prior.relationId())) throw unavailable();
            repository.complete(tenant,invitee.subjectKey(),request.idempotencyKey(),prior.relationId(),now);return prior;
        }
        if(inviteeRole.relationId()!=null) throw unavailable();
        var relation=new ReferralRelation(tenant,UUID.randomUUID().toString(),p.campaignId(),p.organizationId(),p.shopId(),p.participantId(),locked.tokenId(),p.definitionId(),p.definitionVersion(),p.generation(),p.artifactId(),p.policyHash(),now.truncatedTo(ChronoUnit.MICROS),deadline,request.consentVersion(),request.consentHash(),"BOUND");
        String payload=json.writeValueAsString(Map.of("schemaVersion",1,"type","RELATION_BOUND","qualificationState","PENDING","relation",relation));
        repository.save(relation,invitee,scope.actorId(),request.traceId(),payload,Digests.sha256Hex(payload));
        repository.complete(tenant,invitee.subjectKey(),request.idempotencyKey(),relation.relationId(),now);return relation;
    }
    private Owned owned(TenantScope scope,String id,boolean lock) {
        Owned result=invites.participant(scope.tenantId().value(),id,lock);if(result==null || result.participant()==null) throw unavailable();
        scope.requireOrganization(result.participant().organizationId());scope.requireShop(result.participant().shopId());return result;
    }
    private static ReferralRelation checkedRelation(TenantScope scope,ReferralRelation relation) {
        if(relation==null || !scope.tenantId().value().equals(relation.tenantId())) throw unavailable();
        scope.requireOrganization(relation.organizationId());scope.requireShop(relation.shopId());return relation;
    }
    private static boolean identityCurrent(Subject subject,Instant now) { return !now.isBefore(subject.issuedAt()) && now.isBefore(subject.expiresAt()); }
    private static ConflictException unavailable() { return rejected("REFERRAL_BINDING_UNAVAILABLE"); }
    private static ConflictException rejected(String code) { return new ConflictException(code,"referral binding is unavailable or conflicts with prior attribution"); }
    /** 明文token/断言只在调用栈使用，持久化的是tokenHash、同意凭据和受保护好友身份。 */
    public record Bind(String inviteToken,String consentVersion,String consentHash,String assertion,String idempotencyKey,String traceId) {
        /** token只允许发行格式；业务字段完整校验，不归一化主体或令牌。 */
        public Bind {
            if(inviteToken==null || !inviteToken.matches("[A-Za-z0-9_-]{43}") || assertion==null || assertion.isBlank() || assertion.length()>16384) throw new IllegalArgumentException("invalid binding input");
            ReferralInputs.key(consentVersion,128);ReferralInputs.digest(consentHash);ReferralInputs.key(idempotencyKey,128);ReferralInputs.key(traceId,64);
            if(idempotencyKey.length()<8) throw new IllegalArgumentException("invalid binding key");
        }
        /** 默认诊断不得包含token或断言。 */
        @Override public String toString() { return "ReferralBind[token/assertion=<redacted>]"; }
    }
}
