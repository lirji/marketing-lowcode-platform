package com.acme.marketing.referral;
import static org.junit.jupiter.api.Assertions.*;
import static com.acme.marketing.referral.ReferralParticipationIntegrationTest.NOW;
import com.acme.marketing.platform.crypto.Digests;
import com.acme.marketing.platform.error.ConflictException;
import com.acme.marketing.platform.identity.*;
import com.acme.marketing.referral.application.*;
import com.acme.marketing.referral.application.ReferralInviteRepository.*;
import com.acme.marketing.referral.application.ReferralInviteTokenService.*;
import com.acme.marketing.referral.application.ReferralHistoricalInvitePermitPort.Permit;
import com.acme.marketing.referral.domain.ReferralParticipant;
import com.acme.marketing.referral.ReferralParticipationIntegrationTest.*;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;
/** 复用专用隔离容器fixture，不接受外部库URL；只测试令牌切片，不继承参与者用例。 */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"marketing.security.mode=DEV","marketing.security.dev-headers-enabled=false","marketing.referral.subject-key-version=1","marketing.referral.invite-replay-seconds=3600"})
@Import({ReferralParticipationIntegrationTest.Fixtures.class,ReferralInviteTokenIntegrationTest.TokenFixtures.class})
class ReferralInviteTokenIntegrationTest {
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) { ReferralParticipationIntegrationTest.database(registry); }
    @Autowired ReferralParticipationService joins;
    @Autowired ReferralInviteTokenService service;
    @Autowired ReferralRepository participants;
    @Autowired ReferralInviteRepository tokens;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource datasource;
    @Autowired PlatformTransactionManager manager;
    @Autowired ObjectMapper json;
    @Autowired TestClock clock;
    @Autowired TestSubjects subjects;
    @Autowired TestPermits joinPermits;
    @Autowired HistoricalPermits permits;
    @Autowired Summaries summaries;
    @Autowired InviteProtectionFixture protection;
    String tenant;ReferralParticipant participant;
    @BeforeEach void prepare() {
        tenant="token-"+UUID.randomUUID();clock.now.set(NOW);subjects.version=1;joinPermits.mode=1;permits.enabled=true;permits.tokenAge=86400;summaries.enabled=true;summaries.hook=()->{};
        protection.encryptHook=()->{};protection.decryptHook=()->{};protection.failNextDecrypt.set(false);
        jdbc.update("INSERT INTO mk_referral_subject_index_anchor(tenant_id,subject_key_version,created_at,updated_at) VALUES(?,1,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",tenant);
        participant=joins.join(scope(),new ReferralParticipationService.Join("campaign","org","shop","subject-A","join-key-1","trace"));
    }
    TenantScope scope() { return new TenantScope(new TenantId(tenant),Set.of("org"),Set.of("shop"),"bff-machine",Set.of("referral:participate","referral:resolve")); }
    Issue request(String key) { return new Issue(participant.participantId(),"subject-A",key,"trace"); }
    int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE tenant_id=?",Integer.class,tenant); }
    ReferralInviteTokenService custom(ReferralInviteRepository repository,ReferralInviteProtectionPort cipher) { return new ReferralInviteTokenService(repository,participants,subjects,permits,cipher,summaries,clock,json,manager,1,3600); }
    @Test void opaqueTokensHave256RandomBitsHashOnlyAndFrozenHistoricalVersion() {
        joinPermits.mode=2;
        var issued=service.issue(scope(),request("issue-key-1"));assertEquals(32,Base64.getUrlDecoder().decode(issued.inviteToken()).length);
        assertEquals(1,permits.last.definitionVersion());assertEquals(participant,permits.last);
        assertEquals(Digests.sha256Hex(issued.inviteToken()),jdbc.queryForObject("SELECT token_hash FROM mk_referral_invite_token WHERE tenant_id=?",String.class,tenant));
        byte[] cipher=jdbc.queryForObject("SELECT response_cipher FROM mk_referral_invite_replay WHERE tenant_id=?",byte[].class,tenant);
        assertFalse(new String(cipher,StandardCharsets.ISO_8859_1).contains(issued.inviteToken()));assertFalse(issued.toString().contains(issued.inviteToken()));
        var view=service.resolve(scope(),issued.inviteToken());assertEquals("活动测试摘要",view.title());assertEquals("邀请人***",view.maskedInviter());
        assertFalse(view.toString().contains("subject-A"));
        var unique=new HashSet<String>();unique.add(issued.inviteToken());for(int i=2;i<9;i++) unique.add(service.issue(scope(),request("issue-key-"+i)).inviteToken());assertEquals(8,unique.size());
    }
    @Test void concurrentSameKeyReturnsIdenticalTokenAndOneAudit() throws Exception {
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var start=new CountDownLatch(1);var futures=new ArrayList<Future<Issued>>();
            for(int i=0;i<12;i++) futures.add(pool.submit(()->{start.await();return service.issue(scope(),request("issue-key-1"));}));
            start.countDown();var first=futures.getFirst().get(10,TimeUnit.SECONDS);for(var future:futures) assertEquals(first,future.get(10,TimeUnit.SECONDS));
        }
        assertEquals(1,count("mk_referral_invite_token"));assertEquals(1,count("mk_referral_invite_replay"));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM mk_referral_audit WHERE tenant_id=? AND action='INVITE_TOKEN_ISSUED'",Integer.class,tenant));
    }
    @Test void sameKeyDifferentParticipantConflictsAndOtherSubjectCannotIssue() {
        service.issue(scope(),request("issue-key-1"));
        var other=joins.join(scope(),new ReferralParticipationService.Join("other-campaign","org","shop","subject-A","join-key-2","trace"));
        var error=assertThrows(ConflictException.class,()->service.issue(scope(),new Issue(other.participantId(),"subject-A","issue-key-1","trace")));
        assertEquals("IDEMPOTENCY_PAYLOAD_CONFLICT",error.code());
        assertThrows(ConflictException.class,()->service.issue(scope(),new Issue(participant.participantId(),"subject-B","issue-key-2","trace")));
        var wrongScope=new TenantScope(new TenantId(tenant),Set.of("other-org"),Set.of("shop"),"bff",Set.of("referral:participate"));
        assertThrows(IllegalArgumentException.class,()->service.issue(wrongScope,request("issue-key-2")));
        var otherTenant=new TenantScope(new TenantId("other-tenant"),Set.of("org"),Set.of("shop"),"bff",Set.of("referral:participate"));
        assertThrows(ConflictException.class,()->service.issue(otherTenant,request("issue-key-2")));assertEquals(1,count("mk_referral_invite_token"));
    }
    @Test void replayWindowExpirationNeverDecryptsOrReplacesOriginalIdentity() {
        var original=service.issue(scope(),request("issue-key-1"));int decrypts=protection.decrypts.get();
        clock.now.set(NOW.plusSeconds(3600));
        var error=assertThrows(ConflictException.class,()->service.issue(scope(),request("issue-key-1")));assertEquals("REFERRAL_TOKEN_REPLAY_EXPIRED",error.code());
        assertEquals(decrypts,protection.decrypts.get());assertEquals(1,count("mk_referral_invite_token"));
        var explicitNew=service.issue(scope(),request("issue-key-2"));assertNotEquals(original.inviteToken(),explicitNew.inviteToken());assertEquals(2,count("mk_referral_invite_token"));
    }
    @Test void expiredOrRevokedTokensKeepHashIdentityButCannotResolve() {
        permits.tokenAge=30;var original=service.issue(scope(),request("issue-key-1"));clock.now.set(NOW.plusSeconds(30));
        assertEquals(original,service.issue(scope(),request("issue-key-1")));
        assertThrows(ConflictException.class,()->service.resolve(scope(),original.inviteToken()));
        var revoked=service.issue(scope(),request("issue-key-2"));jdbc.update("UPDATE mk_referral_invite_token SET revoked_at=UTC_TIMESTAMP(6) WHERE tenant_id=? AND token_id=?",tenant,revoked.tokenId());
        assertThrows(ConflictException.class,()->service.resolve(scope(),revoked.inviteToken()));assertEquals(2,count("mk_referral_invite_token"));
        assertEquals(2,count("mk_referral_invite_replay"));
    }
    @Test void decryptFailureAfterCommitIsRecoverableWithTheSameKey() {
        protection.failNextDecrypt.set(true);assertThrows(ConflictException.class,()->service.issue(scope(),request("issue-key-1")));
        assertEquals(1,count("mk_referral_invite_token"));int encrypts=protection.encrypts.get();
        var recovered=service.issue(scope(),request("issue-key-1"));assertEquals(encrypts,protection.encrypts.get());assertEquals(1,count("mk_referral_invite_token"));
        assertEquals(recovered,service.issue(scope(),request("issue-key-1")));
    }
    @Test void failureAfterTokenReplayAndAuditWritesRollsBackEverything() {
        var failing=new Delegate(tokens) {
            @Override public void save(Replay replay,String p,String actor,String trace,Instant now) { super.save(replay,p,actor,trace,now);throw new IllegalStateException("test rollback"); }
        };
        assertThrows(RuntimeException.class,()->custom(failing,protection).issue(scope(),request("issue-key-1")));
        assertEquals(0,count("mk_referral_invite_token"));assertEquals(0,count("mk_referral_invite_replay"));assertEquals(1,count("mk_referral_audit"));
        assertNotNull(service.issue(scope(),request("issue-key-1")));
    }
    @Test void slowEncryptAndDecryptCannotExtendPermitOrReplayWindow() {
        protection.encryptHook=()->clock.now.set(NOW.plusSeconds(11));assertThrows(ConflictException.class,()->service.issue(scope(),request("issue-key-1")));
        assertEquals(0,count("mk_referral_invite_token"));assertEquals(0,count("mk_referral_invite_replay"));
        clock.now.set(NOW);protection.encryptHook=()->{};protection.decryptHook=()->clock.now.set(NOW.plusSeconds(3600));
        var expired=assertThrows(ConflictException.class,()->service.issue(scope(),request("issue-key-1")));assertEquals("REFERRAL_TOKEN_REPLAY_EXPIRED",expired.code());
        assertEquals(1,count("mk_referral_invite_token"));
    }
    @Test void participantLockWaitCannotExtendHistoricalPermit() throws Exception {
        var encrypted=new CountDownLatch(1);protection.encryptHook=encrypted::countDown;
        try(Connection connection=datasource.getConnection();var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            connection.setAutoCommit(false);
            try(var lock=connection.prepareStatement("SELECT participant_id FROM mk_referral_participant WHERE tenant_id=? AND participant_id=? FOR UPDATE")) {
                lock.setString(1,tenant);lock.setString(2,participant.participantId());try(var row=lock.executeQuery()){assertTrue(row.next());}
            }
            Future<Issued> future=pool.submit(()->service.issue(scope(),request("issue-key-1")));
            try { assertTrue(encrypted.await(3,TimeUnit.SECONDS));assertThrows(TimeoutException.class,()->future.get(150,TimeUnit.MILLISECONDS));clock.now.set(NOW.plusSeconds(11)); }
            finally { connection.commit(); }
            assertThrows(ExecutionException.class,()->future.get(5,TimeUnit.SECONDS));
        }
        assertEquals(0,count("mk_referral_invite_token"));assertEquals(0,count("mk_referral_invite_replay"));
    }
    @Test void missingHistoricalSummaryOrProtectionSourcesStayUnavailable() {
        permits.enabled=false;assertThrows(ConflictException.class,()->service.issue(scope(),request("issue-key-1")));permits.enabled=true;
        var defaults=new com.acme.marketing.referral.infrastructure.ReferralInviteConfiguration();
        assertThrows(ConflictException.class,()->custom(tokens,defaults.inviteProtection()).issue(scope(),request("issue-key-1")));
        assertEquals(0,count("mk_referral_invite_token"));var issued=service.issue(scope(),request("issue-key-1"));summaries.enabled=false;
        assertThrows(ConflictException.class,()->service.resolve(scope(),issued.inviteToken()));assertNull(defaults.historicalInvitePermits().forParticipant(participant));assertNull(defaults.inviteSummaries().forParticipant(participant));
    }
    @Test void summaryLatencyCannotReturnExpiredTokenAndOtherTenantCannotResolve() {
        permits.tokenAge=5;var issued=service.issue(scope(),request("issue-key-1"));summaries.hook=()->clock.now.set(NOW.plusSeconds(6));
        assertThrows(ConflictException.class,()->service.resolve(scope(),issued.inviteToken()));
        clock.now.set(NOW);var other=new TenantScope(new TenantId("other-tenant"),Set.of("org"),Set.of("shop"),"bff",Set.of("referral:resolve"));
        assertThrows(ConflictException.class,()->service.resolve(other,issued.inviteToken()));
    }
    @Test void finalTokenReadCannotExtendTrustedSummaryLifetime() {
        var issued=service.issue(scope(),request("issue-key-1"));var reads=new java.util.concurrent.atomic.AtomicInteger();
        var slowRead=new Delegate(tokens) {
            @Override public Token token(String tenant,String hash) {
                var result=super.token(tenant,hash);if(reads.incrementAndGet()==2) clock.now.set(NOW.plusSeconds(11));return result;
            }
        };
        var error=assertThrows(ConflictException.class,()->custom(slowRead,protection).resolve(scope(),issued.inviteToken()));
        assertEquals("REFERRAL_INVITE_UNAVAILABLE",error.code());assertEquals(2,reads.get());
    }
    @Test void tokenTablesHaveCompleteCommentsAndNoPlaintextTokenColumns() {
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name IN ('mk_referral_invite_token','mk_referral_invite_replay')",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name IN ('mk_referral_invite_token','mk_referral_invite_replay') AND column_comment=''",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name IN ('mk_referral_invite_token','mk_referral_invite_replay') AND table_comment=''",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND column_name IN ('invite_token','canonical_subject','subject')",Integer.class));
    }
    @TestConfiguration static class TokenFixtures {
        @Bean @Primary InviteProtectionFixture tokenProtection() { return new InviteProtectionFixture(); }
        @Bean @Primary HistoricalPermits tokenPermits(TestClock clock) { return new HistoricalPermits(clock); }
        @Bean @Primary Summaries tokenSummaries(TestClock clock) { return new Summaries(clock); }
    }
    static class HistoricalPermits implements ReferralHistoricalInvitePermitPort {
        volatile boolean enabled=true;volatile long tokenAge=86400;volatile ReferralParticipant last;final Clock clock;
        HistoricalPermits(Clock clock){this.clock=clock;}
        @Override public Permit forParticipant(ReferralParticipant p) { assertFalse(TransactionSynchronizationManager.isActualTransactionActive());last=p;Instant now=clock.instant();return enabled?new Permit(p,now,now.plusSeconds(10),now.plusSeconds(tokenAge),true):null; }
    }
    static class Summaries implements ReferralInviteSummaryPort {
        volatile boolean enabled=true;volatile Runnable hook=()->{};final Clock clock;Summaries(Clock clock){this.clock=clock;}
        @Override public Summary forParticipant(ReferralParticipant p) { assertFalse(TransactionSynchronizationManager.isActualTransactionActive());Instant now=clock.instant();var summary=new Summary(p,"活动测试摘要","测试公开条款","terms-v1","邀请人***",now,now.plusSeconds(10));hook.run();return enabled?summary:null; }
    }
    static class Delegate implements ReferralInviteRepository {
        final ReferralInviteRepository delegate;Delegate(ReferralInviteRepository delegate){this.delegate=delegate;}
        @Override public Replay findReplay(String t,String s,String k){return delegate.findReplay(t,s,k);}
        @Override public Replay lockReplay(String t,String s,String k,String h,Instant n){return delegate.lockReplay(t,s,k,h,n);}
        @Override public Owned participant(String t,String p,boolean l){return delegate.participant(t,p,l);}
        @Override public void save(Replay r,String p,String a,String t,Instant n){delegate.save(r,p,a,t,n);}
        @Override public Token token(String t,String h){return delegate.token(t,h);}
    }
}
