package com.acme.marketing.referral;

import static org.junit.jupiter.api.Assertions.*;
import com.acme.marketing.platform.identity.*;
import com.acme.marketing.platform.crypto.Digests;
import com.acme.marketing.referral.application.*;
import com.acme.marketing.referral.application.ReferralParticipationService.Join;
import com.acme.marketing.referral.application.ReferralParticipationPermitPort.Permit;
import com.acme.marketing.referral.application.TrustedReferralSubjectPort.Subject;
import com.acme.marketing.referral.application.TrustedReferralSubjectPort.RequestBinding;
import com.acme.marketing.referral.domain.ReferralParticipant;
import com.acme.marketing.referral.infrastructure.ReferralConfiguration;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.ObjectMapper;

/** 仅使用新建MySQL容器；从不接受共享库URL，验证真实唯一键、行锁与原子提交。 */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "marketing.security.mode=DEV","marketing.security.dev-headers-enabled=false","marketing.referral.subject-key-version=1"})
@Import(ReferralParticipationIntegrationTest.Fixtures.class)
class ReferralParticipationIntegrationTest {
    static final Instant NOW=Instant.parse("2026-09-08T03:00:00Z");
    // Docker Desktop初始化曾超过120秒；只延长专用测试容器预算，不重试创建多个容器。
    static final MySQLContainer MYSQL=new MySQLContainer("mysql:8.4.11").withDatabaseName("referral_isolated")
        .withUsername("test").withPassword("test").withStartupTimeoutSeconds(300).withStartupAttempts(1).withReuse(false).withTmpFs(Map.of("/var/lib/mysql","rw"));
    static { MYSQL.start(); }
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",()->MYSQL.getJdbcUrl()+(MYSQL.getJdbcUrl().contains("?")?"&":"?")+"connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true");
        registry.add("spring.datasource.username",MYSQL::getUsername); registry.add("spring.datasource.password",MYSQL::getPassword);
        registry.add("spring.flyway.user",MYSQL::getUsername); registry.add("spring.flyway.password",MYSQL::getPassword);
    }
    @Autowired ReferralParticipationService service;
    @Autowired ReferralRepository repository;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    @Autowired DataSource datasource;
    @Autowired ObjectMapper json;
    @Autowired TestClock clock;
    @Autowired TestPermits permits;
    @Autowired TestSubjects subjects;
    String tenant;
    @BeforeEach void prepare() { tenant="tenant-"+UUID.randomUUID(); clock.now.set(NOW); permits.mode=1; subjects.version=1; anchor(); }
    void anchor() { jdbc.update("INSERT INTO mk_referral_subject_index_anchor(tenant_id,subject_key_version,created_at,updated_at) VALUES(?,1,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",tenant); }
    RequestBinding binding() { return new RequestBinding(tenant,"campaign","org","shop","JOIN_V1","join-key-1","a".repeat(64),"INTERNAL","referral.participation.join"); }
    TenantScope scope() { return new TenantScope(new TenantId(tenant),Set.of("org","other-org"),Set.of("shop","other-shop"),"bff-machine",Set.of("referral:participate")); }
    Join request(String key) { return new Join("campaign","org","shop","subject-A",key,"trace-test"); }
    int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE tenant_id=?",Integer.class,tenant); }
    ReferralParticipationService custom(ReferralRepository repo,TrustedReferralSubjectPort source,ReferralParticipationPermitPort releases) {
        return new ReferralParticipationService(repo,source,releases,clock,json,manager,1);
    }
    @Test void originalVersionReplaysWithSameOrNewKeyAfterUpgradeAndKillSwitch() {
        var first=service.join(scope(),request("join-key-1"));
        assertEquals(1,first.definitionVersion()); assertEquals(NOW,first.createdAt());
        permits.mode=2;
        assertEquals(first,service.join(scope(),request("join-key-1")));
        assertEquals(first,service.join(scope(),request("join-key-2")));
        permits.mode=0; clock.now.set(NOW.plus(Duration.ofDays(30)));
        assertEquals(first,service.join(scope(),request("join-key-1")));
        assertEquals(first,service.join(scope(),request("join-key-3")));
        assertEquals(1,count("mk_referral_participant")); assertEquals(1,count("mk_referral_subject_role"));
        assertEquals(1,count("mk_referral_audit")); assertEquals(1,count("mk_referral_outbox"));
        assertEquals(3,count("mk_referral_join_command"));
    }
    @Test void sameKeyChangedContentConflictsAndCrossScopeDoesNotReturnOriginal() {
        service.join(scope(),request("join-key-1"));
        var changed=new Join("other-campaign","org","shop","subject-A","join-key-1","trace-2");
        assertThrows(RuntimeException.class,()->service.join(scope(),changed));
        var crossShop=new Join("campaign","org","other-shop","subject-A","join-key-2","trace-2");
        assertThrows(RuntimeException.class,()->service.join(scope(),crossShop));
        assertEquals(1,count("mk_referral_join_command"));
        var restricted=new TenantScope(new TenantId(tenant),Set.of("elsewhere"),Set.of("shop"),"bff",Set.of("referral:participate"));
        assertThrows(IllegalArgumentException.class,()->service.join(restricted,request("join-key-1")));
        assertThrows(RuntimeException.class,()->service.join(new TenantScope(new TenantId(tenant),Set.of("org"),Set.of("shop"),"bff"),request("join-key-1")));
    }
    @Test void tenantAndExactSubjectsRemainIndependentAndSubjectNeverAppearsInEvents() {
        var a=service.join(scope(),request("join-key-1"));
        var b=service.join(scope(),new Join("campaign","org","shop","subject-a","join-key-1","trace-test"));
        assertNotEquals(a.participantId(),b.participantId());
        tenant="tenant-"+UUID.randomUUID(); anchor(); var c=service.join(scope(),request("join-key-1"));
        assertNotEquals(a.participantId(),c.participantId());
        var subject=subjects.resolve(scope(),"subject-A",binding());
        assertEquals(subject.subjectKey(),jdbc.queryForObject("SELECT subject_key FROM mk_referral_participant WHERE tenant_id=?",String.class,tenant));
        assertNotEquals(Digests.sha256Hex("subject-A"),subject.subjectKey());
        assertFalse(new String(subject.cipher(),StandardCharsets.UTF_8).contains("subject-A"));
        String payload=jdbc.queryForObject("SELECT CAST(payload AS CHAR) FROM mk_referral_outbox WHERE tenant_id=?",String.class,tenant);
        assertFalse(payload.contains("subject-A")); assertFalse(payload.contains(subject.subjectKey()));
        assertFalse(request("join-key-1").toString().contains("subject-A"));
        assertFalse(subject.toString().contains(subject.subjectKey()));
    }
    @Test void unavailableSourcesAndMismatchedReleaseOrIndexVersionFailClosed() {
        permits.mode=0;
        assertThrows(RuntimeException.class,()->service.join(scope(),request("join-key-1")));
        permits.mode=1; subjects.version=2;
        assertThrows(RuntimeException.class,()->service.join(scope(),request("join-key-1")));
        subjects.version=1;
        var wrong=custom(repository,subjects,(t,c,o,s)->TestPermits.permit("other-tenant",c,o,s,1,NOW));
        assertThrows(RuntimeException.class,()->wrong.join(scope(),request("join-key-1")));
        var unsafe=custom(repository,(s,a,b)->{ throw new IllegalStateException("subject-A internal.invalid"); },permits);
        var error=assertThrows(RuntimeException.class,()->unsafe.join(scope(),request("join-key-1")));
        assertFalse(error.toString().contains("subject-A")); assertNull(error.getCause());
        var defaults=new ReferralConfiguration();
        assertNull(defaults.referralParticipationPermits().current(tenant,"c","o","s"));
        assertThrows(RuntimeException.class,()->defaults.referralSubjects().resolve(scope(),"assertion",binding()));
        assertEquals(0,count("mk_referral_participant")); assertEquals(0,count("mk_referral_join_command")); assertEquals(0,count("mk_referral_subject_role"));
    }
    @Test void concurrentKeysAndSameKeyHaveOneParticipantAuditAndOutbox() throws Exception {
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var start=new CountDownLatch(1); var futures=new ArrayList<Future<ReferralParticipant>>();
            for(int i=0;i<12;i++) { int n=i%4; futures.add(pool.submit(()->{ start.await(); return service.join(scope(),request("join-key-"+n)); })); }
            start.countDown(); var first=futures.getFirst().get(10,TimeUnit.SECONDS);
            for(var future:futures) assertEquals(first,future.get(10,TimeUnit.SECONDS));
        }
        assertEquals(1,count("mk_referral_participant")); assertEquals(1,count("mk_referral_audit"));
        assertEquals(1,count("mk_referral_outbox")); assertEquals(4,count("mk_referral_join_command"));
    }
    @Test void auditOutboxFailureRollsBackParticipantRoleAndCommand() {
        var failing=new DelegatingRepository(repository) {
            @Override public void appendJoined(ReferralParticipant p,String actor,String trace,String payload,String hash) {
                super.appendJoined(p,actor,trace,payload,hash); throw new IllegalStateException("fixture rollback after outbox");
            }
        };
        assertThrows(RuntimeException.class,()->custom(failing,subjects,permits).join(scope(),request("join-key-1")));
        for(String table:List.of("mk_referral_participant","mk_referral_subject_role","mk_referral_join_command","mk_referral_audit","mk_referral_outbox")) assertEquals(0,count(table));
        assertNotNull(service.join(scope(),request("join-key-1")));
    }
    @Test void roleConflictDoesNotConvertInviteeIntoInviter() {
        var subject=subjects.resolve(scope(),"subject-A",binding());
        jdbc.update("INSERT INTO mk_referral_subject_role(tenant_id,campaign_id,subject_key,subject_key_version,role,created_at,updated_at) VALUES(?,?,?,1,'INVITEE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",tenant,"campaign",subject.subjectKey());
        assertThrows(RuntimeException.class,()->service.join(scope(),request("join-key-1")));
        assertEquals(0,count("mk_referral_participant")); assertEquals(0,count("mk_referral_join_command"));
    }
    @Test void permitExpiresWhileWaitingForActualRoleLock() throws Exception {
        var subject=subjects.resolve(scope(),"subject-A",binding());
        jdbc.update("INSERT INTO mk_referral_subject_role(tenant_id,campaign_id,subject_key,subject_key_version,role,created_at,updated_at) VALUES(?,?,?,1,'INVITER',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",tenant,"campaign",subject.subjectKey());
        var arrived=new CountDownLatch(1);
        var waiting=new DelegatingRepository(repository) {
            @Override public Role lockRole(String t,String c,Subject s,Instant now) { arrived.countDown(); return super.lockRole(t,c,s,now); }
        };
        try(Connection connection=datasource.getConnection(); var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            connection.setAutoCommit(false);
            try(var lock=connection.prepareStatement("SELECT role FROM mk_referral_subject_role WHERE tenant_id=? AND campaign_id='campaign' AND subject_key=? FOR UPDATE")) {
                lock.setString(1,tenant); lock.setString(2,subject.subjectKey()); try(var rows=lock.executeQuery()) { assertTrue(rows.next()); }
            }
            Future<ReferralParticipant> future=pool.submit(()->custom(waiting,subjects,permits).join(scope(),request("join-key-1")));
            try {
                assertTrue(arrived.await(3,TimeUnit.SECONDS));
                assertThrows(TimeoutException.class,()->future.get(150,TimeUnit.MILLISECONDS));
                clock.now.set(NOW.plusSeconds(11));
            } finally { connection.commit(); }
            assertThrows(ExecutionException.class,()->future.get(5,TimeUnit.SECONDS));
        }
        assertEquals(0,count("mk_referral_participant")); assertEquals(0,count("mk_referral_join_command"));
    }
    @Test void subjectBindingMismatchIsRejectedBeforePersistence() {
        TrustedReferralSubjectPort mismatched=(scope,assertion,binding)->{
            var subject=subjects.resolve(scope,assertion,binding);
            var wrong=new RequestBinding(binding.tenantId(),"wrong-campaign",binding.organizationId(),binding.shopId(),binding.operation(),binding.idempotencyKey(),binding.bodyDigest(),binding.method(),binding.path());
            return new Subject(subject.tenantId(),subject.subjectKey(),subject.keyVersion(),subject.cipher(),subject.encryptionKeyId(),wrong,subject.issuedAt(),subject.expiresAt());
        };
        assertThrows(RuntimeException.class,()->custom(repository,mismatched,permits).join(scope(),request("join-key-1")));
        assertEquals(0,count("mk_referral_join_command"));
    }
    @Test void identityLifetimeIsRecheckedAfterRoleLockEvenWhenReleaseStillValid() {
        TrustedReferralSubjectPort shortLived=(scope,assertion,binding)->{
            var subject=subjects.resolve(scope,assertion,binding);
            return new Subject(subject.tenantId(),subject.subjectKey(),subject.keyVersion(),subject.cipher(),subject.encryptionKeyId(),binding,NOW,NOW.plusSeconds(1));
        };
        var delayed=new DelegatingRepository(repository) {
            @Override public Role lockRole(String t,String c,Subject s,Instant now) {
                var role=super.lockRole(t,c,s,now); clock.now.set(NOW.plusSeconds(2)); return role;
            }
        };
        assertThrows(RuntimeException.class,()->custom(delayed,shortLived,permits).join(scope(),request("join-key-1")));
        assertEquals(0,count("mk_referral_participant")); assertEquals(0,count("mk_referral_join_command")); assertEquals(0,count("mk_referral_subject_role"));
    }
    @Test void persistentAnchorRejectsBothDeploymentAndSourceIndexRotation() {
        var first=service.join(scope(),request("join-key-1"));
        subjects.version=2;
        TrustedReferralSubjectPort rotated=(scope,assertion,binding)->{
            var subject=subjects.resolve(scope,assertion,binding);
            return new Subject(subject.tenantId(),"b".repeat(64),2,subject.cipher(),subject.encryptionKeyId(),binding,subject.issuedAt(),subject.expiresAt());
        };
        var versionTwo=new ReferralParticipationService(repository,rotated,permits,clock,json,manager,2);
        var error=assertThrows(com.acme.marketing.platform.error.ConflictException.class,()->versionTwo.join(scope(),request("join-key-2")));
        assertEquals("REFERRAL_SUBJECT_INDEX_UNAVAILABLE",error.code());
        assertEquals(1,count("mk_referral_participant")); assertEquals(1,count("mk_referral_join_command"));
        subjects.version=1; assertEquals(first,service.join(scope(),request("join-key-1")));
        tenant="tenant-"+UUID.randomUUID();
        var missing=assertThrows(com.acme.marketing.platform.error.ConflictException.class,()->service.join(scope(),request("join-key-1")));
        assertEquals("REFERRAL_SUBJECT_INDEX_UNAVAILABLE",missing.code()); assertEquals(0,count("mk_referral_join_command"));
    }
    @Test void existingParticipantNewKeyCannotCommitAfterIdentityExpires() throws Exception {
        var first=service.join(scope(),request("join-key-1"));
        var subject=subjects.resolve(scope(),"subject-A",binding());
        TrustedReferralSubjectPort shortLived=(scope,assertion,binding)->{
            var protectedSubject=subjects.resolve(scope,assertion,binding);
            return new Subject(protectedSubject.tenantId(),protectedSubject.subjectKey(),protectedSubject.keyVersion(),protectedSubject.cipher(),protectedSubject.encryptionKeyId(),binding,NOW,NOW.plusSeconds(1));
        };
        var arrived=new CountDownLatch(1);
        var waiting=new DelegatingRepository(repository) {
            @Override public Role lockRole(String t,String c,Subject protectedSubject,Instant now) { arrived.countDown(); return super.lockRole(t,c,protectedSubject,now); }
        };
        try(Connection connection=datasource.getConnection(); var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            connection.setAutoCommit(false);
            try(var lock=connection.prepareStatement("SELECT role FROM mk_referral_subject_role WHERE tenant_id=? AND campaign_id='campaign' AND subject_key=? FOR UPDATE")) {
                lock.setString(1,tenant); lock.setString(2,subject.subjectKey()); try(var rows=lock.executeQuery()) { assertTrue(rows.next()); }
            }
            Future<ReferralParticipant> future=pool.submit(()->custom(waiting,shortLived,permits).join(scope(),request("join-key-2")));
            try {
                assertTrue(arrived.await(3,TimeUnit.SECONDS)); assertThrows(TimeoutException.class,()->future.get(150,TimeUnit.MILLISECONDS));
                clock.now.set(NOW.plusSeconds(2));
            } finally { connection.commit(); }
            var error=assertThrows(ExecutionException.class,()->future.get(5,TimeUnit.SECONDS));
            assertEquals("REFERRAL_IDENTITY_UNAVAILABLE",((com.acme.marketing.platform.error.ConflictException)error.getCause()).code());
        }
        assertEquals(1,count("mk_referral_join_command")); assertEquals(first,service.join(scope(),request("join-key-1")));
    }
    @Test void completedSameKeyMayReplayWhenIdentityExpiresOnlyDuringCommandLock() {
        var first=service.join(scope(),request("join-key-1"));
        TrustedReferralSubjectPort shortLived=(scope,assertion,binding)->{
            var subject=subjects.resolve(scope,assertion,binding);
            return new Subject(subject.tenantId(),subject.subjectKey(),subject.keyVersion(),subject.cipher(),subject.encryptionKeyId(),binding,NOW,NOW.plusSeconds(1));
        };
        var delayed=new DelegatingRepository(repository) {
            @Override public Command lockCommand(String t,String s,String k,String h,Instant now) {
                var command=super.lockCommand(t,s,k,h,now); clock.now.set(NOW.plusSeconds(2)); return command;
            }
        };
        permits.mode=0;
        assertEquals(first,custom(delayed,shortLived,permits).join(scope(),request("join-key-1")));
        assertEquals(1,count("mk_referral_join_command"));
    }
    @Test void externalVerificationNeverRunsInsideTransaction() {
        var called=new java.util.concurrent.atomic.AtomicInteger();
        var instance=custom(repository,(s,a,b)->{ called.incrementAndGet(); return subjects.resolve(s,a,b); },permits);
        assertThrows(RuntimeException.class,()->new TransactionTemplate(manager).execute(tx->instance.join(scope(),request("join-key-1"))));
        assertEquals(0,called.get());
    }
    @Test void everyTableAndColumnHasChineseCommentAndNoNativeSubjectColumn() {
        assertEquals(6,jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name IN ('mk_referral_participant','mk_referral_subject_role','mk_referral_join_command','mk_referral_audit','mk_referral_outbox','mk_referral_subject_index_anchor')",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name LIKE 'mk_referral_%' AND table_comment=''",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name LIKE 'mk_referral_%' AND column_comment=''",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND column_name IN ('canonical_subject','subject','subject_token')",Integer.class));
    }
    /** 测试专用来源替身，不能被生产组件扫描。 */
    @TestConfiguration static class Fixtures {
        @Bean @Primary TestClock testClock() { return new TestClock(); }
        @Bean @Primary TestSubjects testSubjects(TestClock clock) { return new TestSubjects(clock); }
        @Bean @Primary TestPermits testPermits(TestClock clock) { return new TestPermits(clock); }
    }
    static class TestClock extends Clock {
        final AtomicReference<Instant> now=new AtomicReference<>(NOW);
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    }
    static class TestSubjects implements TrustedReferralSubjectPort {
        volatile long version=1; final Clock clock;
        TestSubjects(Clock clock) { this.clock=clock; }
        @Override public Subject resolve(TenantScope scope,String assertion,RequestBinding binding) {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            try {
                // 真实HMAC与AES-GCM使用不同测试key，证明不是普通SHA256或伪密文路径。
                var mac=Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec("fixture-hmac-key-for-isolated-tests-only".getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
                byte[] data=(scope.tenantId().value().length()+":"+scope.tenantId().value()+assertion).getBytes(StandardCharsets.UTF_8);
                String key=HexFormat.of().formatHex(mac.doFinal(data));
                byte[] iv=new byte[12]; new java.security.SecureRandom().nextBytes(iv);
                var cipher=Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec("fixture-aes-key!".getBytes(StandardCharsets.UTF_8),"AES"),new GCMParameterSpec(128,iv));
                byte[] body=cipher.doFinal(assertion.getBytes(StandardCharsets.UTF_8));
                byte[] encrypted=new byte[iv.length+body.length]; System.arraycopy(iv,0,encrypted,0,iv.length); System.arraycopy(body,0,encrypted,iv.length,body.length);
                return new Subject(scope.tenantId().value(),key,version,encrypted,"fixture-encryption-key-1",binding,clock.instant(),clock.instant().plusSeconds(60));
            } catch(Exception failed) { throw new IllegalStateException(failed); }
        }
    }
    static class TestPermits implements ReferralParticipationPermitPort {
        volatile int mode=1; final Clock clock;
        TestPermits(Clock clock) { this.clock=clock; }
        @Override public Permit current(String tenant,String campaign,String org,String shop) {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            return mode==0?null:permit(tenant,campaign,org,shop,mode,clock.instant());
        }
        static Permit permit(String tenant,String campaign,String org,String shop,long version,Instant at) {
            return new Permit(tenant,campaign,org,shop,"definition",version,version,"artifact-"+version,"a".repeat(64),1,at.minusSeconds(100),at.plusSeconds(100),at,at.plusSeconds(10),true);
        }
    }
    static class DelegatingRepository implements ReferralRepository {
        final ReferralRepository delegate;
        DelegatingRepository(ReferralRepository delegate) { this.delegate=delegate; }
        @Override public Long subjectIndexVersion(String tenant) { return delegate.subjectIndexVersion(tenant); }
        @Override public Command lockCommand(String t,String s,String k,String h,Instant n) { return delegate.lockCommand(t,s,k,h,n); }
        @Override public Role lockRole(String t,String c,Subject s,Instant n) { return delegate.lockRole(t,c,s,n); }
        @Override public ReferralParticipant participant(String t,String p) { return delegate.participant(t,p); }
        @Override public void insert(ReferralParticipant p,Subject s) { delegate.insert(p,s); }
        @Override public void complete(String t,String s,String k,String p,Instant n) { delegate.complete(t,s,k,p,n); }
        @Override public void appendJoined(ReferralParticipant p,String a,String t,String payload,String h) { delegate.appendJoined(p,a,t,payload,h); }
    }
}
