package com.acme.marketing.referral;
import static org.junit.jupiter.api.Assertions.*;
import static com.acme.marketing.referral.ReferralParticipationIntegrationTest.NOW;
import com.acme.marketing.platform.crypto.Digests;
import com.acme.marketing.platform.error.ConflictException;
import com.acme.marketing.platform.identity.*;
import com.acme.marketing.referral.application.*;
import com.acme.marketing.referral.application.ReferralBindingService.Bind;
import com.acme.marketing.referral.application.ReferralBindingPermitPort.Permit;
import com.acme.marketing.referral.application.ReferralBindingRepository.*;
import com.acme.marketing.referral.application.ReferralInviteRepository.Token;
import com.acme.marketing.referral.application.TrustedReferralSubjectPort.Subject;
import com.acme.marketing.referral.domain.*;
import com.acme.marketing.referral.ReferralParticipationIntegrationTest.*;
import com.acme.marketing.referral.ReferralInviteTokenIntegrationTest.*;
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
/** 专用MySQL验证首绑唯一性、排序行锁与原子回滚；所有绝对截止明确为测试fixture。 */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"marketing.security.mode=DEV","marketing.security.dev-headers-enabled=false","marketing.referral.subject-key-version=1","marketing.referral.invite-replay-seconds=3600"})
@Import({ReferralParticipationIntegrationTest.Fixtures.class,ReferralInviteTokenIntegrationTest.TokenFixtures.class,ReferralBindingIntegrationTest.BindingFixtures.class})
class ReferralBindingIntegrationTest {
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry){ReferralParticipationIntegrationTest.database(registry);}
    @Autowired ReferralParticipationService joins;
    @Autowired ReferralInviteTokenService issues;
    @Autowired ReferralBindingService service;
    @Autowired ReferralBindingRepository repository;
    @Autowired ReferralRepository participants;
    @Autowired ReferralInviteRepository invites;
    @Autowired TokenBindingReadPort tokens;
    @Autowired TestClock clock;
    @Autowired TestSubjects subjects;
    @Autowired TestPermits joinPermits;
    @Autowired HistoricalPermits invitePermits;
    @Autowired InviteProtectionFixture protection;
    @Autowired BindingPermits permits;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource datasource;
    @Autowired PlatformTransactionManager manager;
    @Autowired ObjectMapper json;
    String tenant;ReferralParticipant inviter;ReferralInviteTokenService.Issued token;
    @BeforeEach void prepare(){
        tenant="bind-"+UUID.randomUUID();clock.now.set(NOW);subjects.version=1;joinPermits.mode=1;invitePermits.enabled=true;invitePermits.tokenAge=86400;
        protection.encryptHook=()->{};protection.decryptHook=()->{};protection.failNextDecrypt.set(false);permits.enabled=true;
        jdbc.update("INSERT INTO mk_referral_subject_index_anchor(tenant_id,subject_key_version,created_at,updated_at) VALUES(?,1,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",tenant);
        inviter=join("subject-A","join-key-A");token=issue(inviter,"subject-A","issue-key-A");
    }
    TenantScope scope(){return new TenantScope(new TenantId(tenant),Set.of("org"),Set.of("shop"),"bff-machine",Set.of("referral:participate","referral:resolve"));}
    ReferralParticipant join(String subject,String key){return joins.join(scope(),new ReferralParticipationService.Join("campaign","org","shop",subject,key,"trace"));}
    ReferralInviteTokenService.Issued issue(ReferralParticipant p,String subject,String key){return issues.issue(scope(),new ReferralInviteTokenService.Issue(p.participantId(),subject,key,"trace"));}
    Bind request(String subject,String key){return bind(token.inviteToken(),subject,key);}
    Bind bind(String rawToken,String subject,String key){return new Bind(rawToken,"terms-v1","d".repeat(64),subject,key,"trace");}
    int count(String table){return jdbc.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE tenant_id=?",Integer.class,tenant);}
    ReferralBindingService custom(ReferralBindingRepository repo,TokenBindingReadPort tokenPort){return new ReferralBindingService(repo,participants,invites,tokenPort,subjects,permits,clock,json,manager,1);}
    @Test void firstBindingFreezesOriginalVersionAndOnlyQueuesPendingEvidence(){
        joinPermits.mode=2;var relation=service.bind(scope(),request("subject-B","bind-key-B"));
        assertEquals(1,relation.definitionVersion());assertEquals(inviter.participantId(),relation.participantId());assertEquals("BOUND",relation.state());
        assertEquals(NOW,relation.boundAt());assertEquals(NOW.plusSeconds(600),relation.qualifyDeadline());assertEquals(inviter,permits.last);
        assertEquals("PENDING_EVIDENCE",jdbc.queryForObject("SELECT reason_code FROM mk_referral_relation WHERE tenant_id=?",String.class,tenant));
        String payload=jdbc.queryForObject("SELECT CAST(payload AS CHAR) FROM mk_referral_outbox WHERE tenant_id=? AND event_type='RELATION_BOUND'",String.class,tenant);
        assertTrue(payload.contains("PENDING"));assertFalse(payload.contains("subject-B"));assertFalse(payload.contains(token.inviteToken()));
        assertThrows(ConflictException.class,()->join("subject-B","join-key-B"));
    }
    @Test void concurrentSameAndNewKeysKeepOneRelationAuditAndEvent() throws Exception {
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
            var start=new CountDownLatch(1);var futures=new ArrayList<Future<ReferralRelation>>();
            for(int i=0;i<12;i++){int n=i%4;futures.add(pool.submit(()->{start.await();return service.bind(scope(),request("subject-B","bind-key-"+n));}));}
            start.countDown();var first=futures.getFirst().get(10,TimeUnit.SECONDS);for(var future:futures)assertEquals(first,future.get(10,TimeUnit.SECONDS));
        }
        assertEquals(1,count("mk_referral_relation"));assertEquals(4,count("mk_referral_bind_command"));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM mk_referral_audit WHERE tenant_id=? AND action='RELATION_BOUND'",Integer.class,tenant));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM mk_referral_outbox WHERE tenant_id=? AND event_type='RELATION_BOUND'",Integer.class,tenant));
    }
    @Test void twoInvitersCompeteForOnePermanentInviteeAttribution() throws Exception {
        var c=join("subject-C","join-key-C");var tokenC=issue(c,"subject-C","issue-key-C");
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
            var start=new CountDownLatch(1);var futures=new ArrayList<Future<Object>>();
            for(var entry:List.of(Map.entry(token.inviteToken(),"bind-key-A"),Map.entry(tokenC.inviteToken(),"bind-key-C")))
                futures.add(pool.submit(()->{start.await();try{return service.bind(scope(),bind(entry.getKey(),"subject-B",entry.getValue()));}catch(ConflictException conflict){return conflict.code();}}));
            start.countDown();var results=List.of(futures.get(0).get(10,TimeUnit.SECONDS),futures.get(1).get(10,TimeUnit.SECONDS));
            assertEquals(1,results.stream().filter(ReferralRelation.class::isInstance).count());assertTrue(results.contains("REFERRAL_ALREADY_BOUND"));
        }
        assertEquals(1,count("mk_referral_relation"));assertEquals(1,count("mk_referral_bind_command"));
    }
    @Test void oneSharingTokenBindsMultipleFriendsWithoutConsumption(){
        var b=service.bind(scope(),request("subject-B","bind-key-1"));var d=service.bind(scope(),request("subject-D","bind-key-1"));
        assertNotEquals(b.relationId(),d.relationId());assertEquals(b.tokenId(),d.tokenId());assertEquals(2,count("mk_referral_relation"));
        assertNotNull(tokens.locate(tenant,Digests.sha256Hex(token.inviteToken())));assertNotNull(issues.resolve(scope(),token.inviteToken()));
    }
    @Test void selfAndMutualInviterBindingsNeverConvertRoles() throws Exception {
        assertEquals("REFERRAL_SELF_INVITATION",assertThrows(ConflictException.class,()->service.bind(scope(),request("subject-A","bind-key-self"))).code());
        var b=join("subject-B","join-key-B");var tokenB=issue(b,"subject-B","issue-key-B");
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
            var start=new CountDownLatch(1);Future<String> a=pool.submit(()->{start.await();return assertThrows(ConflictException.class,()->service.bind(scope(),request("subject-B","bind-key-A"))).code();});
            Future<String> reverse=pool.submit(()->{start.await();return assertThrows(ConflictException.class,()->service.bind(scope(),bind(tokenB.inviteToken(),"subject-A","bind-key-B"))).code();});
            start.countDown();assertEquals("REFERRAL_SUBJECT_ROLE_CONFLICT",a.get(10,TimeUnit.SECONDS));assertEquals("REFERRAL_SUBJECT_ROLE_CONFLICT",reverse.get(10,TimeUnit.SECONDS));
        }
        assertEquals(0,count("mk_referral_relation"));assertEquals(0,count("mk_referral_bind_command"));assertEquals(2,count("mk_referral_subject_role"));
    }
    @Test void originalSuccessfulKeyReplaysAfterRevocationExpiryAndPermitShutdown(){
        var original=service.bind(scope(),request("subject-B","bind-key-B"));
        jdbc.update("UPDATE mk_referral_invite_token SET revoked_at=UTC_TIMESTAMP(6) WHERE tenant_id=? AND token_id=?",tenant,token.tokenId());
        clock.now.set(NOW.plusSeconds(86401));permits.enabled=false;
        assertEquals(original,service.bind(scope(),request("subject-B","bind-key-B")));
        assertThrows(ConflictException.class,()->service.bind(scope(),request("subject-B","bind-key-new")));assertEquals(1,count("mk_referral_bind_command"));
    }
    @Test void differentTokenTermsScopeOrTenantCannotChangeOriginalBinding(){
        service.bind(scope(),request("subject-B","bind-key-B"));var second=issue(inviter,"subject-A","issue-key-A2");
        assertEquals("IDEMPOTENCY_PAYLOAD_CONFLICT",assertThrows(ConflictException.class,()->service.bind(scope(),bind(second.inviteToken(),"subject-B","bind-key-B"))).code());
        assertEquals("REFERRAL_BINDING_CONTENT_CONFLICT",assertThrows(ConflictException.class,()->service.bind(scope(),bind(second.inviteToken(),"subject-B","bind-key-new"))).code());
        assertThrows(ConflictException.class,()->service.bind(scope(),new Bind(token.inviteToken(),"terms-v2","d".repeat(64),"subject-B","bind-key-terms","trace")));
        var wrongScope=new TenantScope(new TenantId(tenant),Set.of("other-org"),Set.of("shop"),"bff",Set.of("referral:participate"));
        assertThrows(IllegalArgumentException.class,()->service.bind(wrongScope,request("subject-B","bind-key-scope")));
        var wrongTenant=new TenantScope(new TenantId("other-tenant"),Set.of("org"),Set.of("shop"),"bff",Set.of("referral:participate"));
        assertThrows(ConflictException.class,()->service.bind(wrongTenant,request("subject-B","bind-key-tenant")));assertEquals(1,count("mk_referral_bind_command"));
    }
    @Test void auditOutboxFailureRollsBackRelationRoleAndCommand(){
        var failing=new Delegate(repository){@Override public void save(ReferralRelation r,Subject s,String a,String t,String p,String h){super.save(r,s,a,t,p,h);throw new IllegalStateException("fixture rollback after outbox");}};
        assertThrows(RuntimeException.class,()->custom(failing,tokens).bind(scope(),request("subject-B","bind-key-B")));
        assertEquals(0,count("mk_referral_relation"));assertEquals(0,count("mk_referral_bind_command"));assertEquals(1,count("mk_referral_subject_role"));
        assertEquals(2,count("mk_referral_audit"));assertEquals(1,count("mk_referral_outbox"));assertNotNull(service.bind(scope(),request("subject-B","bind-key-B")));
    }
    @Test void realTokenLockWaitRechecksTokenPermitAndIdentity() throws Exception {
        for(int seconds:new int[]{5,11,61}){
            clock.now.set(NOW);invitePermits.tokenAge=seconds==5?5:86400;
            var selected=issue(inviter,"subject-A","issue-wait-"+seconds);var arrived=new CountDownLatch(1);
            var waiting=new TokenBindingReadPort(){
                @Override public Token locate(String t,String h){return tokens.locate(t,h);}
                @Override public Token lock(String t,String h){arrived.countDown();return tokens.lock(t,h);}
            };
            try(Connection connection=datasource.getConnection();var pool=Executors.newVirtualThreadPerTaskExecutor()){
                connection.setAutoCommit(false);try(var lock=connection.prepareStatement("SELECT token_id FROM mk_referral_invite_token WHERE tenant_id=? AND token_id=? FOR UPDATE")){
                    lock.setString(1,tenant);lock.setString(2,selected.tokenId());try(var row=lock.executeQuery()){assertTrue(row.next());}
                }
                Future<ReferralRelation> future=pool.submit(()->custom(repository,waiting).bind(scope(),bind(selected.inviteToken(),"friend-"+seconds,"bind-wait-"+seconds)));
                try{assertTrue(arrived.await(3,TimeUnit.SECONDS));assertThrows(TimeoutException.class,()->future.get(150,TimeUnit.MILLISECONDS));clock.now.set(NOW.plusSeconds(seconds));}
                finally{connection.commit();}
                var error=assertThrows(ExecutionException.class,()->future.get(5,TimeUnit.SECONDS));assertInstanceOf(ConflictException.class,error.getCause());
            }
            assertEquals(0,count("mk_referral_relation"));assertEquals(0,count("mk_referral_bind_command"));assertEquals(1,count("mk_referral_subject_role"));
        }
    }
    @Test void newKeyForExistingRelationRollsBackIfIdentityExpiresAtTokenLock() throws Exception {
        var original=service.bind(scope(),request("subject-B","bind-key-B"));var arrived=new CountDownLatch(1);
        var waiting=new TokenBindingReadPort(){@Override public Token locate(String t,String h){return tokens.locate(t,h);}@Override public Token lock(String t,String h){arrived.countDown();return tokens.lock(t,h);}};
        try(Connection connection=datasource.getConnection();var pool=Executors.newVirtualThreadPerTaskExecutor()){
            connection.setAutoCommit(false);try(var lock=connection.prepareStatement("SELECT token_id FROM mk_referral_invite_token WHERE tenant_id=? AND token_id=? FOR UPDATE")){
                lock.setString(1,tenant);lock.setString(2,token.tokenId());try(var row=lock.executeQuery()){assertTrue(row.next());}
            }
            Future<ReferralRelation> future=pool.submit(()->custom(repository,waiting).bind(scope(),request("subject-B","bind-key-new")));
            try{assertTrue(arrived.await(3,TimeUnit.SECONDS));assertThrows(TimeoutException.class,()->future.get(150,TimeUnit.MILLISECONDS));clock.now.set(NOW.plusSeconds(61));}finally{connection.commit();}
            assertThrows(ExecutionException.class,()->future.get(5,TimeUnit.SECONDS));
        }
        assertEquals(1,count("mk_referral_bind_command"));assertEquals(original,service.bind(scope(),request("subject-B","bind-key-B")));
    }
    @Test void bindingSchemaCommentsAndDefaultRefusalAreExplicit(){
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name IN ('mk_referral_relation','mk_referral_bind_command')",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name IN ('mk_referral_relation','mk_referral_bind_command') AND column_comment=''",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name IN ('mk_referral_relation','mk_referral_bind_command') AND table_comment=''",Integer.class));
        assertThrows(IllegalStateException.class,()->tokens.lock(tenant,Digests.sha256Hex(token.inviteToken())));
        permits.enabled=false;assertThrows(ConflictException.class,()->service.bind(scope(),request("subject-B","bind-key-B")));assertEquals(0,count("mk_referral_relation"));assertEquals(1,count("mk_referral_subject_role"));
        assertNull(new com.acme.marketing.referral.infrastructure.ReferralBindingConfiguration().bindingPermits().forParticipant(inviter));
    }
    @TestConfiguration static class BindingFixtures{@Bean @Primary BindingPermits bindingFixture(TestClock clock){return new BindingPermits(clock);}}
    static class BindingPermits implements ReferralBindingPermitPort{
        volatile boolean enabled=true;volatile ReferralParticipant last;final Clock clock;BindingPermits(Clock clock){this.clock=clock;}
        @Override public Permit forParticipant(ReferralParticipant p){assertFalse(TransactionSynchronizationManager.isActualTransactionActive());last=p;Instant now=clock.instant();return enabled?new Permit(p,"terms-v1","d".repeat(64),NOW.minusSeconds(600),NOW.plusSeconds(600),NOW.plusSeconds(300),NOW.plusSeconds(3600),600,now,now.plusSeconds(10),true):null;}
    }
    static class Delegate implements ReferralBindingRepository{
        final ReferralBindingRepository delegate;Delegate(ReferralBindingRepository delegate){this.delegate=delegate;}
        @Override public Command lockCommand(String t,String i,String k,String h,Instant n){return delegate.lockCommand(t,i,k,h,n);}
        @Override public Role lockInviter(String t,String c,String s){return delegate.lockInviter(t,c,s);}
        @Override public Role lockInvitee(String t,String c,Subject s,Instant n){return delegate.lockInvitee(t,c,s,n);}
        @Override public ReferralRelation relation(String t,String c,String i){return delegate.relation(t,c,i);}
        @Override public ReferralRelation relationById(String t,String r){return delegate.relationById(t,r);}
        @Override public void save(ReferralRelation r,Subject s,String a,String t,String p,String h){delegate.save(r,s,a,t,p,h);}
        @Override public void complete(String t,String i,String k,String r,Instant n){delegate.complete(t,i,k,r,n);}
    }
}
