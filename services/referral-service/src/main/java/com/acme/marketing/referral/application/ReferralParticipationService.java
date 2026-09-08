package com.acme.marketing.referral.application;
import com.acme.marketing.platform.crypto.Digests;
import com.acme.marketing.platform.error.ConflictException;
import com.acme.marketing.platform.identity.TenantScope;
import com.acme.marketing.referral.domain.ReferralParticipant;
import com.acme.marketing.referral.application.TrustedReferralSubjectPort.Subject;
import com.acme.marketing.referral.application.TrustedReferralSubjectPort.RequestBinding;
import com.acme.marketing.referral.application.ReferralParticipationPermitPort.Permit;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import tools.jackson.databind.ObjectMapper;
/** 加入活动只负责永久参与与固定版本，事务外验证信任来源，事务内维持命令/角色/审计/Outbox原子性。 */
@Service
public class ReferralParticipationService {
    private final ReferralRepository repository;
    private final TrustedReferralSubjectPort subjects;
    private final ReferralParticipationPermitPort permits;
    private final Clock clock;
    private final ObjectMapper json;
    private final long keyVersion;
    private final TransactionTemplate transactions;
    /** 索引版本0默认关闭；不允许部署直接轮换HMAC key导致同主体第二条记录。 */
    public ReferralParticipationService(ReferralRepository repository,TrustedReferralSubjectPort subjects,ReferralParticipationPermitPort permits,
            Clock clock,ObjectMapper json,PlatformTransactionManager manager,@Value("${marketing.referral.subject-key-version:0}") long keyVersion) {
        this.repository=repository; this.subjects=subjects; this.permits=permits; this.clock=clock; this.json=json; this.keyVersion=keyVersion;
        transactions=new TransactionTemplate(manager); transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED); transactions.setTimeout(3);
    }
    /** 无HTTP入口；scope来自已认证机器上下文，assertion必须由受信Port解析，重放不重新选择规则版本。 */
    public ReferralParticipant join(TenantScope scope,Join request) {
        if(TransactionSynchronizationManager.isActualTransactionActive()) throw rejected("REFERRAL_OUTER_TRANSACTION_FORBIDDEN");
        Objects.requireNonNull(scope); Objects.requireNonNull(request);
        scope.requirePermission("referral:participate"); scope.requireOrganization(request.organizationId()); scope.requireShop(request.shopId());
        ReferralInputs.key(scope.actorId(),128);
        String bodyDigest=Digests.sha256Hex(json.writeValueAsString(new TreeMap<>(Map.of("campaignId",request.campaignId(),"organizationId",request.organizationId(),"shopId",request.shopId()))));
        var binding=new RequestBinding(scope.tenantId().value(),request.campaignId(),request.organizationId(),request.shopId(),"JOIN_V1",request.idempotencyKey(),bodyDigest,"INTERNAL","referral.participation.join");
        Subject subject;
        try { subject=subjects.resolve(scope,request.assertion(),binding); } catch(RuntimeException unavailable) { throw rejected("REFERRAL_IDENTITY_UNAVAILABLE"); }
        if(subject==null || !subject.tenantId().equals(scope.tenantId().value()) || keyVersion<=0 || subject.keyVersion()!=keyVersion
                || !binding.equals(subject.binding()) || !identityCurrent(subject,clock.instant()))
            throw rejected("REFERRAL_IDENTITY_UNAVAILABLE");
        Permit permit;
        try { permit=permits.current(scope.tenantId().value(),request.campaignId(),request.organizationId(),request.shopId()); }
        catch(RuntimeException unavailable) { permit=null; }
        Permit observed=permit;
        // 随机密文、断言和trace不构成业务内容；主体由HMAC+版本固定，避免刷新断言造成伪冲突。
        String requestHash=Digests.sha256Hex(json.writeValueAsString(List.of("JOIN_V1",scope.tenantId().value(),subject.subjectKey(),subject.keyVersion(),request.campaignId(),request.organizationId(),request.shopId())));
        return Objects.requireNonNull(transactions.execute(tx->joinLocked(scope,request,subject,observed,requestHash)));
    }
    private ReferralParticipant joinLocked(TenantScope scope,Join request,Subject subject,Permit permit,String requestHash) {
        String tenant=scope.tenantId().value(); Instant now=clock.instant().truncatedTo(ChronoUnit.MICROS);
        Long anchoredVersion=repository.subjectIndexVersion(tenant);
        if(anchoredVersion==null || anchoredVersion!=subject.keyVersion()) throw rejected("REFERRAL_SUBJECT_INDEX_UNAVAILABLE");
        var command=repository.lockCommand(tenant,subject.subjectKey(),request.idempotencyKey(),requestHash,now);
        if(!Digests.constantTimeEquals(command.requestHash(),requestHash)) throw rejected("IDEMPOTENCY_PAYLOAD_CONFLICT");
        if(command.participantId()!=null) return existing(tenant,command.participantId(),request);
        var role=repository.lockRole(tenant,request.campaignId(),subject,now);
        if(!"INVITER".equals(role.role()) || role.keyVersion()!=subject.keyVersion()) throw rejected("REFERRAL_SUBJECT_ROLE_CONFLICT");
        if(role.participantId()!=null) {
            var original=existing(tenant,role.participantId(),request);
            // 新幂等键会新写回执，即使参与者已存在也须重检锁等待后的身份时效。
            if(!identityCurrent(subject,clock.instant())) throw rejected("REFERRAL_IDENTITY_UNAVAILABLE");
            repository.complete(tenant,subject.subjectKey(),request.idempotencyKey(),original.participantId(),clock.instant()); return original;
        }
        // 在命令/角色锁等待结束后重新取时间；不得把已过期许可延长为新参与机会。
        now=clock.instant();
        if(!identityCurrent(subject,now)) throw rejected("REFERRAL_IDENTITY_UNAVAILABLE");
        if(permit==null || !permit.joinsAllowed() || !tenant.equals(permit.tenantId()) || !request.campaignId().equals(permit.campaignId())
                || !request.organizationId().equals(permit.organizationId()) || !request.shopId().equals(permit.shopId())
                || now.isBefore(permit.issuedAt()) || !now.isBefore(permit.expiresAt())
                || now.isBefore(permit.campaignStartAt()) || !now.isBefore(permit.campaignEndAt())) throw rejected("REFERRAL_PARTICIPATION_UNAVAILABLE");
        // 权威时效使用完整精度；只有通过校验后的持久化时间才降为数据库微秒精度。
        Instant persistedAt=now.truncatedTo(ChronoUnit.MICROS);
        var participant=new ReferralParticipant(tenant,UUID.randomUUID().toString(),request.campaignId(),request.organizationId(),request.shopId(),
                permit.definitionId(),permit.definitionVersion(),permit.generation(),permit.artifactId(),permit.policyHash(),permit.routeEpoch(),"ACTIVE",persistedAt);
        repository.insert(participant,subject);
        String payload=json.writeValueAsString(Map.of("schemaVersion",1,"type","PARTICIPANT_JOINED","participant",participant));
        repository.appendJoined(participant,scope.actorId(),request.traceId(),payload,Digests.sha256Hex(payload));
        repository.complete(tenant,subject.subjectKey(),request.idempotencyKey(),participant.participantId(),persistedAt);
        return participant;
    }
    private ReferralParticipant existing(String tenant,String id,Join request) {
        var p=repository.participant(tenant,id);
        if(p==null) throw rejected("REFERRAL_PERSISTENCE_INCONSISTENT");
        if(!p.campaignId().equals(request.campaignId()) || !p.organizationId().equals(request.organizationId()) || !p.shopId().equals(request.shopId()))
            throw rejected("REFERRAL_PARTICIPATION_SCOPE_CONFLICT");
        return p;
    }
    private static boolean identityCurrent(Subject subject,Instant now) { return !now.isBefore(subject.issuedAt()) && now.isBefore(subject.expiresAt()); }
    private static ConflictException rejected(String code) { return new ConflictException(code,"referral participation is unavailable or conflicts with a prior request"); }
    /** 无外部主体字段；assertion只经过Port且不进入请求摘要、日志或数据库。 */
    public record Join(String campaignId,String organizationId,String shopId,String assertion,String idempotencyKey,String traceId) {
        /** 输入Scope机器键必须完整；业务去重键最少8字符，与平台旧写接口习惯兼容。 */
        public Join {
            ReferralInputs.key(campaignId,64); ReferralInputs.key(organizationId,64); ReferralInputs.key(shopId,64); ReferralInputs.key(idempotencyKey,128); ReferralInputs.key(traceId,64);
            if(idempotencyKey.length()<8 || assertion==null || assertion.isBlank() || assertion.length()>16384) throw new IllegalArgumentException("invalid referral join request");
        }
        /** 避免调用方调试日志意外输出用户断言。 */
        @Override public String toString() { return "ReferralJoin[assertion=<redacted>]"; }
    }
}
