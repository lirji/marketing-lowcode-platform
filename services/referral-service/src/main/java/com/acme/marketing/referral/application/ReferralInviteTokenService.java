package com.acme.marketing.referral.application;
import com.acme.marketing.platform.crypto.Digests;
import com.acme.marketing.platform.error.ConflictException;
import com.acme.marketing.platform.identity.TenantScope;
import com.acme.marketing.referral.domain.ReferralParticipant;
import com.acme.marketing.referral.application.TrustedReferralSubjectPort.*;
import com.acme.marketing.referral.application.ReferralInviteRepository.*;
import com.acme.marketing.referral.application.ReferralInviteProtectionPort.*;
import com.acme.marketing.referral.application.ReferralHistoricalInvitePermitPort.Permit;
import java.security.SecureRandom;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import tools.jackson.databind.ObjectMapper;
/** 内部邀请token发行/解析；明文仅停留在调用栈，保护及受信来源全部在事务外调用。 */
@Service
public class ReferralInviteTokenService {
    private final ReferralInviteRepository tokens;
    private final ReferralRepository participants;
    private final TrustedReferralSubjectPort subjects;
    private final ReferralHistoricalInvitePermitPort permits;
    private final ReferralInviteProtectionPort protection;
    private final ReferralInviteSummaryPort summaries;
    private final Clock clock;
    private final ObjectMapper json;
    private final long keyVersion,replaySeconds;
    private final SecureRandom random=new SecureRandom();
    private final TransactionTemplate transactions;
    /** 回显窗口缺省0关闭发行；业务token期限只来自历史签名许可，不自行推测。 */
    public ReferralInviteTokenService(ReferralInviteRepository tokens,ReferralRepository participants,TrustedReferralSubjectPort subjects,
            ReferralHistoricalInvitePermitPort permits,ReferralInviteProtectionPort protection,ReferralInviteSummaryPort summaries,Clock clock,
            ObjectMapper json,PlatformTransactionManager manager,@Value("${marketing.referral.subject-key-version:0}") long keyVersion,
            @Value("${marketing.referral.invite-replay-seconds:0}") long replaySeconds) {
        if(replaySeconds<0 || replaySeconds>86400) throw new IllegalArgumentException("invite replay window must not exceed 24 hours");
        this.tokens=tokens;this.participants=participants;this.subjects=subjects;this.permits=permits;this.protection=protection;this.summaries=summaries;
        this.clock=clock;this.json=json;this.keyVersion=keyVersion;this.replaySeconds=replaySeconds;
        transactions=new TransactionTemplate(manager);transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);transactions.setTimeout(3);
    }
    /** 只有受认证机器代本人发行；相同键只恢复原密文，不以当前规则重新生成成功结果。 */
    public Issued issue(TenantScope scope,Issue request) {
        outsideTransaction();Objects.requireNonNull(scope);Objects.requireNonNull(request);scope.requirePermission("referral:participate");
        ReferralInputs.key(scope.actorId(),128);
        if(keyVersion<=0 || replaySeconds<=0) throw unavailable();
        String tenant=scope.tenantId().value();
        Owned owner=owned(scope,request.participantId(),false);
        var p=owner.participant();
        String bodyHash=Digests.sha256Hex(json.writeValueAsString(Map.of("participantId",request.participantId())));
        var binding=new RequestBinding(tenant,p.campaignId(),p.organizationId(),p.shopId(),"ISSUE_TOKEN_V1",request.idempotencyKey(),bodyHash,"INTERNAL","referral.invite.issue");
        Subject subject;
        try { subject=subjects.resolve(scope,request.assertion(),binding); } catch(RuntimeException failure) { throw unavailable(); }
        if(subject==null || !tenant.equals(subject.tenantId()) || subject.keyVersion()!=keyVersion || !binding.equals(subject.binding())
                || !identityCurrent(subject,clock.instant()) || !owner.subjectKey().equals(subject.subjectKey()) || owner.keyVersion()!=subject.keyVersion()) throw unavailable();
        String requestHash=Digests.sha256Hex(json.writeValueAsString(List.of("ISSUE_TOKEN_V1",tenant,subject.subjectKey(),subject.keyVersion(),p.participantId(),p.organizationId(),p.shopId())));
        Replay found=tokens.findReplay(tenant,subject.subjectKey(),request.idempotencyKey());
        Permit permit=null; Replay candidate=null;
        if(found==null) {
            try { permit=permits.forParticipant(p); } catch(RuntimeException failure) { throw unavailable(); }
            Instant now=clock.instant().truncatedTo(ChronoUnit.MICROS); requirePermit(p,permit,now);
            byte[] entropy=new byte[32];random.nextBytes(entropy);String token=Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);
            var context=new Context(tenant,subject.subjectKey(),request.idempotencyKey(),requestHash,UUID.randomUUID().toString(),Digests.sha256Hex(token),
                    permit.tokenExpiresAt().truncatedTo(ChronoUnit.MICROS),now.plusSeconds(replaySeconds));
            Protected cipher;
            try { cipher=protection.encrypt(context,token); } catch(RuntimeException failure) { throw unavailable(); }
            if(cipher==null) throw unavailable();
            candidate=new Replay(tenant,subject.subjectKey(),request.idempotencyKey(),requestHash,context.tokenId(),context.tokenHash(),context.tokenExpiresAt(),context.replayUntil(),cipher.bytes(),cipher.keyId());
        }
        Permit observed=permit;Replay prepared=candidate;
        Replay selected=Objects.requireNonNull(transactions.execute(tx->{
            Long anchor=participants.subjectIndexVersion(tenant);
            if(anchor==null || anchor!=subject.keyVersion()) throw unavailable();
            Replay locked=tokens.lockReplay(tenant,subject.subjectKey(),request.idempotencyKey(),requestHash,clock.instant());
            if(!Digests.constantTimeEquals(locked.requestHash(),requestHash)) throw rejected("IDEMPOTENCY_PAYLOAD_CONFLICT");
            Owned current=owned(scope,request.participantId(),true);
            if(!current.subjectKey().equals(subject.subjectKey()) || current.keyVersion()!=subject.keyVersion()) throw unavailable();
            if(locked.tokenId()!=null) { requireReplayWindow(locked);return locked; }
            Instant now=clock.instant();
            if(prepared==null || !identityCurrent(subject,now)) throw unavailable();
            requirePermit(current.participant(),observed,now);
            if(!now.isBefore(prepared.tokenExpiresAt()) || !now.isBefore(prepared.replayUntil())) throw unavailable();
            tokens.save(prepared,p.participantId(),scope.actorId(),request.traceId(),now);return prepared;
        }));
        // KMS故障不撤销已成功发行；同键再请求可恢复已保存的密文，不能生成第二个token。
        requireReplayWindow(selected);String token;
        try { token=protection.decrypt(selected.context(),new Protected(selected.encryptionKeyId(),selected.responseCipher())); }
        catch(RuntimeException failure) { throw unavailable(); }
        requireReplayWindow(selected);
        if(!canonicalToken(token) || !Digests.constantTimeEquals(Digests.sha256Hex(token),selected.tokenHash())) throw unavailable();
        return new Issued(selected.tokenId(),token,selected.tokenExpiresAt());
    }
    /** 只返回可信公开摘要；展示不等于有效绑定/新客/首单资格，后续绑定必须重新校验。 */
    public View resolve(TenantScope scope,String token) {
        outsideTransaction();Objects.requireNonNull(scope);scope.requirePermission("referral:resolve");
        if(!canonicalToken(token)) throw rejected("REFERRAL_TOKEN_INVALID");
        String tenant=scope.tenantId().value(),hash=Digests.sha256Hex(token);
        Token identity=tokens.token(tenant,hash);requireLive(identity);
        Owned owner=owned(scope,identity.participantId(),false);
        ReferralInviteSummaryPort.Summary summary;
        try { summary=summaries.forParticipant(owner.participant()); } catch(RuntimeException failure) { throw unavailable(); }
        Instant now=clock.instant();
        if(summary==null || !owner.participant().equals(summary.participant()) || now.isBefore(summary.asOf()) || !now.isBefore(summary.validUntil())) throw unavailable();
        // 摘要读取可能很慢；展示前重新读取token，不能沿用调用外部来源前的有效性。
        requireLive(tokens.token(tenant,hash));
        // 最后一次主库读取也可能等待，展示水位不能停留在该读取之前。
        if(!clock.instant().isBefore(summary.validUntil())) throw unavailable();
        return new View(owner.participant().campaignId(),summary.title(),summary.publicTerms(),summary.termsVersion(),summary.maskedInviter());
    }
    private Owned owned(TenantScope scope,String id,boolean lock) {
        Owned owner=tokens.participant(scope.tenantId().value(),id,lock);
        if(owner==null || owner.participant()==null) throw unavailable();
        scope.requireOrganization(owner.participant().organizationId());scope.requireShop(owner.participant().shopId());return owner;
    }
    private void requireLive(Token token) { if(token==null || token.revokedAt()!=null || !clock.instant().isBefore(token.expiresAt())) throw rejected("REFERRAL_TOKEN_INVALID"); }
    private void requireReplayWindow(Replay replay) { if(replay.replayUntil()==null || !clock.instant().isBefore(replay.replayUntil())) throw rejected("REFERRAL_TOKEN_REPLAY_EXPIRED"); }
    private static void requirePermit(ReferralParticipant p,Permit permit,Instant now) {
        if(!"ACTIVE".equals(p.state()) || permit==null || !p.equals(permit.participant()) || !permit.issuingAllowed() || now.isBefore(permit.issuedAt())
                || !now.isBefore(permit.expiresAt()) || !now.isBefore(permit.tokenExpiresAt())) throw unavailable();
    }
    private static boolean identityCurrent(Subject subject,Instant now) { return !now.isBefore(subject.issuedAt()) && now.isBefore(subject.expiresAt()); }
    private static void outsideTransaction() { if(TransactionSynchronizationManager.isActualTransactionActive()) throw rejected("REFERRAL_OUTER_TRANSACTION_FORBIDDEN"); }
    private static boolean canonicalToken(String value) {
        if(value==null || !value.matches("[A-Za-z0-9_-]{43}")) return false;
        try { byte[] raw=Base64.getUrlDecoder().decode(value);return raw.length==32 && Base64.getUrlEncoder().withoutPadding().encodeToString(raw).equals(value); }
        catch(IllegalArgumentException invalid) { return false; }
    }
    private static ConflictException unavailable() { return rejected("REFERRAL_INVITE_UNAVAILABLE"); }
    private static ConflictException rejected(String code) { return new ConflictException(code,"invite token is unavailable or requires an explicit new request"); }
    /** 业务输入没有token或主体原值字段，用户断言只经过受信Port。 */
    public record Issue(String participantId,String assertion,String idempotencyKey,String traceId) {
        /** 业务机器键沿用参与服务限制。 */
        public Issue { ReferralInputs.key(participantId,64);ReferralInputs.key(idempotencyKey,128);ReferralInputs.key(traceId,64);if(idempotencyKey.length()<8 || assertion==null || assertion.isBlank() || assertion.length()>16384) throw new IllegalArgumentException("invalid invite issue request"); }
        /** 不把用户断言写进诊断输出。 */
        @Override public String toString() { return "InviteIssue[assertion=<redacted>]"; }
    }
    /** 仅受认证本人获得原明文token；调用方禁止把该响应写日志/埋点/Referer。 */
    public record Issued(String tokenId,String inviteToken,Instant expiresAt) {
        /** 默认诊断不会泄漏token。 */
        @Override public String toString() { return "IssuedInviteToken[token=<redacted>]"; }
    }
    /** 仅展示最小视图，不返回邀请人主体、HMAC或participantId。 */
    public record View(String campaignId,String title,String publicTerms,String termsVersion,String maskedInviter) {}
}
