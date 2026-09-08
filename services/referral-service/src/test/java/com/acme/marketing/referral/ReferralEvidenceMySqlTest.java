package com.acme.marketing.referral;

import com.acme.marketing.platform.crypto.Digests;
import com.acme.marketing.platform.error.ConflictException;
import com.acme.marketing.platform.identity.*;
import com.acme.marketing.referral.ReferralOrderEvidence.*;
import com.acme.marketing.referral.application.ReferralRepository;
import com.acme.marketing.referral.application.evidence.*;
import com.acme.marketing.referral.application.evidence.ReferralEvidencePreparation.*;
import com.acme.marketing.referral.application.evidence.ReferralEvidenceRepository.*;
import com.acme.marketing.referral.application.evidence.ProtectedReferralEvidencePort.*;
import com.acme.marketing.referral.application.evidence.TrustedReferralEvidencePort.*;
import java.lang.reflect.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import javax.crypto.*;
import javax.crypto.spec.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

/** V4仅在临时MySQL验证，不接共享库/实际来源；真实AES-GCM/HMAC只作为独立测试适配器。 */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
        "marketing.security.mode=DEV","marketing.security.dev-headers-enabled=false","marketing.referral.subject-key-version=1"})
@Import(ReferralParticipationIntegrationTest.Fixtures.class)
class ReferralEvidenceMySqlTest {
    private static final Instant NOW=Instant.parse("2026-09-08T03:00:00.123456789Z");
    private static final String SUBJECT="canonical-秘密-subject-A";
    private static final List<String> EFFECT_TABLES=List.of("mk_referral_order_evidence_current","mk_referral_order_evidence_history",
            "mk_referral_evidence_inbox","mk_referral_evidence_fanout","mk_referral_audit","mk_referral_outbox");
    @DynamicPropertySource static void database(DynamicPropertyRegistry r){ReferralParticipationIntegrationTest.database(r);}
    @Autowired ReferralEvidenceRepository repository;
    @Autowired ReferralRepository participants;
    @Autowired PlatformTransactionManager manager;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource datasource;
    @Autowired ObjectMapper json;
    @Autowired ReferralParticipationIntegrationTest.TestClock clock;
    String tenant;
    Crypto protection;
    Source sources;
    @BeforeEach void setup(){
        tenant="evidence-"+UUID.randomUUID();clock.now.set(NOW);protection=new Crypto(json);sources=new Source();
        jdbc.update("INSERT INTO mk_referral_subject_index_anchor(tenant_id,subject_key_version,created_at,updated_at) VALUES(?,1,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",tenant);
    }
    TenantScope scope(){return new TenantScope(new TenantId(tenant),Set.of("org"),Set.of("shop"),"evidence-machine",Set.of("referral:evidence"));}
    ReferralEvidenceIntakeService service(ReferralEvidenceRepository repo,ProtectedReferralEvidencePort crypto){return new ReferralEvidenceIntakeService(repo,participants,sources,crypto,clock,manager,1,10);}
    ReferralEvidenceIntakeService service(){return service(repository,protection);}
    Snapshot snapshot(String source,String order,long revision,long refund){return new Snapshot(new Scope(tenant,source,SUBJECT,order,"org","shop"),revision,"policy-v1",ReferralPolicyEvaluator.Fact.YES,true,OrderState.SETTLED,NOW.minusSeconds(100),1000,refund,0,1000-refund,"CNY");}
    Envelope event(String id,Snapshot snapshot){return sources.register(id,snapshot,NOW.minusNanos(1));}
    OrderKey order(Snapshot s){return OrderKey.of(s.scope());}
    State current(Snapshot s){return protection.openState(repository.readOrder(order(s)).state());}
    int count(String table){return jdbc.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE tenant_id=?",Integer.class,tenant);}

    @Test void historyAndStateRemainEncryptedWithExactAnchorsAndAtomicFanout(){
        Snapshot s=snapshot("channel","order",1,0);Result result=service().accept(scope(),event("first",s));
        assertEquals(1,result.stateVersion());assertEquals(NOW.minusNanos(1),current(s).firstReceivedAt());
        assertEquals(NOW.minusNanos(1),current(s).firstSettlementReceivedAt());
        assertEquals(123456788,jdbc.queryForObject("SELECT first_received_nanos FROM mk_referral_order_evidence_history WHERE tenant_id=?",Integer.class,tenant));
        assertEquals(1,count("mk_referral_evidence_fanout"));assertEquals(1,count("mk_referral_outbox"));assertEquals(1,count("mk_referral_audit"));
        byte[] cipher=jdbc.queryForObject("SELECT state_cipher FROM mk_referral_order_evidence_current WHERE tenant_id=?",byte[].class,tenant);
        assertFalse(new String(cipher,StandardCharsets.UTF_8).contains(SUBJECT));
        String subjectKey=jdbc.queryForObject("SELECT subject_key FROM mk_referral_order_evidence_current WHERE tenant_id=?",String.class,tenant);
        assertNotEquals(Digests.sha256Hex(SUBJECT),subjectKey);
        String payload=jdbc.queryForObject("SELECT CAST(payload AS CHAR) FROM mk_referral_outbox WHERE tenant_id=?",String.class,tenant);
        assertFalse(payload.contains(SUBJECT));assertFalse(payload.contains(subjectKey));
        // 使用新的适配器实例及同测试密钥恢复，不能依赖内存Map冒充持久密文。
        assertEquals(current(s),new Crypto(json).openState(repository.readOrder(order(s)).state()));
    }
    @Test void originalInboxResultReplaysAfterLaterRefundWithoutDecryptingCurrent(){
        Snapshot first=snapshot("channel","order",1,0);Envelope original=event("same",first);
        Result receipt=service().accept(scope(),original);service().accept(scope(),event("later",snapshot("channel","order",2,200)));
        ProtectedReferralEvidencePort noCurrent=new Crypto(json){@Override public State openState(Sealed s){throw new AssertionError("original inbox replay must not decrypt current");}};
        assertEquals(receipt,service(repository,noCurrent).accept(scope(),original));
        assertEquals(2,current(first).latest().revision());assertEquals(2,count("mk_referral_evidence_inbox"));
    }
    @Test void olderRevisionSameContentIsSafeButDifferentHistoryPermanentlyQuarantines(){
        Snapshot first=snapshot("channel","order",1,0);service().accept(scope(),event("r1",first));
        service().accept(scope(),event("r2",snapshot("channel","order",2,200)));
        service().accept(scope(),event("old-identical",first));assertFalse(current(first).quarantined());assertEquals(2,current(first).latest().revision());
        service().accept(scope(),event("old-conflict",snapshot("channel","order",1,50)));
        assertTrue(current(first).quarantined());assertEquals(first,protection.openSnapshot(repository.readHistory(order(first),1)));
        service().accept(scope(),event("newer-after-isolation",snapshot("channel","order",3,250)));
        assertTrue(current(first).quarantined());assertEquals(3,count("mk_referral_order_evidence_history"));
    }
    @Test void sameEventDifferentOrderCommitsOriginalQuarantineBeforeReturningConflict(){
        Snapshot first=snapshot("channel","original",1,0);Result receipt=service().accept(scope(),event("permanent",first));
        Snapshot other=snapshot("channel","other",1,0);
        assertThrows(ConflictException.class,()->service().accept(scope(),event("permanent",other)));
        assertTrue(current(first).quarantined());assertNull(repository.readOrder(order(other)));assertNull(repository.readHistory(order(other),1));
        assertEquals(receipt,repository.readInbox(new EventKey(tenant,"fixture-issuer","channel","permanent")).result());
        assertEquals(1,count("mk_referral_evidence_inbox"));assertEquals(1,count("mk_referral_order_evidence_history"));
        assertEquals(3,count("mk_referral_audit"));
    }
    @Test void scopeDriftPreservesOriginalResourceAndCannotCreateNewAuthorizedSubject(){
        Snapshot first=snapshot("channel","order",1,0);service().accept(scope(),event("original",first));
        Snapshot drift=new Snapshot(new Scope(tenant,"channel","different-secret-subject","order","foreign-org","foreign-shop"),2,"policy-v1",ReferralPolicyEvaluator.Fact.YES,true,OrderState.SETTLED,NOW.minusSeconds(100),1000,0,0,1000,"CNY");
        service().accept(scope(),event("drift",drift));
        State state=current(first);assertTrue(state.quarantined());assertEquals(first.scope(),state.latest().scope());
        assertEquals("org",repository.readOrder(order(first)).state().header().organizationId());assertEquals(1,count("mk_referral_order_evidence_current"));
    }
    @Test void concurrentEventsForSameRevisionPersistOneBusinessHistoryAndOneStateChange() throws Exception {
        Snapshot first=snapshot("channel","order",1,0);var a=event("a",first);var b=event("b",first);
        try(var pool=Executors.newFixedThreadPool(2)){
            CountDownLatch start=new CountDownLatch(1);
            var x=pool.submit(()->{start.await();return service().accept(scope(),a);});var y=pool.submit(()->{start.await();return service().accept(scope(),b);});start.countDown();
            assertEquals(x.get(10,TimeUnit.SECONDS).resourceId(),y.get(10,TimeUnit.SECONDS).resourceId());
        }
        assertEquals(1,count("mk_referral_order_evidence_current"));assertEquals(1,count("mk_referral_order_evidence_history"));assertEquals(2,count("mk_referral_evidence_inbox"));assertEquals(1,count("mk_referral_outbox"));assertEquals(1,count("mk_referral_evidence_fanout"));
    }
    @Test void failureAfterOutboxRollsBackEveryNewReceiptHistoryCurrentAndTask(){
        ReferralEvidenceRepository failing=proxy((m,args)->{Object result=invoke(m,args);if(m.getName().equals("changed"))throw new IllegalStateException("fixture post-outbox failure");return result;});
        Snapshot s=snapshot("channel","order",1,0);Envelope e=event("retry",s);
        assertThrows(ConflictException.class,()->service(failing,protection).accept(scope(),e));
        for(String table:EFFECT_TABLES)assertEquals(0,count(table),table);
        assertNotNull(service().accept(scope(),e));
    }
    @Test void actualAnchorLockWaitPastRawExpiryRollsBackPlaceholders() throws Exception {
        Snapshot s=snapshot("channel","order",1,0);Envelope e=event("wait",s);
        try(Connection holder=datasource.getConnection();var pool=Executors.newSingleThreadExecutor()){
            holder.setAutoCommit(false);
            try(var q=holder.prepareStatement("SELECT subject_key_version FROM mk_referral_subject_index_anchor WHERE tenant_id=? FOR UPDATE")){q.setString(1,tenant);try(var rows=q.executeQuery()){assertTrue(rows.next());}}
            var request=pool.submit(()->service().accept(scope(),e));
            try{awaitActualLockWait();assertFalse(request.isDone());clock.now.set(NOW.plusSeconds(10));holder.commit();
                ExecutionException error=assertThrows(ExecutionException.class,()->request.get(5,TimeUnit.SECONDS));assertInstanceOf(ConflictException.class,error.getCause());
            }finally{holder.rollback();}
        }
        for(String table:EFFECT_TABLES)assertEquals(0,count(table),table);
    }
    @Test void sourceNamespacesAndExactOrderCaseDoNotShareInboxOrCurrent(){
        for(String source:List.of("channel","Channel"))for(String order:List.of("order","order "))service().accept(scope(),event(source+order,snapshot(source,order,1,0)));
        assertEquals(4,count("mk_referral_order_evidence_current"));assertEquals(4,count("mk_referral_order_evidence_history"));assertEquals(4,count("mk_referral_evidence_inbox"));
    }
    @Test void authenticatedPayloadTamperAndOuterTransactionCannotWrite(){
        Snapshot s=snapshot("channel","order",1,0);Envelope e=event("event",s);
        assertThrows(ConflictException.class,()->service().accept(scope(),new Envelope(e.eventId(),"wrong",e.payload(),e.traceId())));
        assertThrows(ConflictException.class,()->new TransactionTemplate(manager).execute(tx->service().accept(scope(),e)));
        assertEquals(0,count("mk_referral_order_evidence_current"));
    }
    @Test void allV4TableAndColumnCommentsExistAndNoPlainSubjectColumn(){
        assertEquals(4,jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name IN ('mk_referral_order_evidence_current','mk_referral_order_evidence_history','mk_referral_evidence_inbox','mk_referral_evidence_fanout')",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name LIKE 'mk_referral_%' AND table_comment=''",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name LIKE 'mk_referral_%' AND column_comment=''",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND column_name IN ('canonical_subject','subject_token','raw_payload')",Integer.class));
    }
    private void awaitActualLockWait() throws Exception {
        var mysql=ReferralParticipationIntegrationTest.MYSQL;
        try(Connection c=DriverManager.getConnection(mysql.getJdbcUrl(),"root",mysql.getPassword());var q=c.prepareStatement("SELECT COUNT(*) FROM performance_schema.data_lock_waits w JOIN performance_schema.data_locks l ON l.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID WHERE l.OBJECT_SCHEMA=? AND l.OBJECT_NAME='mk_referral_subject_index_anchor'")){
            q.setString(1,mysql.getDatabaseName());long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
            do{try(var rows=q.executeQuery()){rows.next();if(rows.getLong(1)>0)return;}Thread.sleep(20);}while(System.nanoTime()<end);
            throw new AssertionError("expected actual MySQL anchor lock wait");
        }
    }
    interface Call {Object run(Method method,Object[] args)throws Throwable;}
    private ReferralEvidenceRepository proxy(Call call){return (ReferralEvidenceRepository)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{ReferralEvidenceRepository.class},(p,m,a)->call.run(m,a));}
    private Object invoke(Method m,Object[] args)throws Throwable{try{return m.invoke(repository,args);}catch(InvocationTargetException e){throw e.getCause();}}
    final class Source implements TrustedReferralEvidencePort {
        private final Map<String,Observation> trusted=new ConcurrentHashMap<>();
        Envelope register(String event,Snapshot snapshot,Instant received){String payload="fixture-envelope-"+UUID.randomUUID();trusted.put(payload,new Observation(snapshot,event,received.minusNanos(1),received,true));return new Envelope(event,"fixture-assertion",payload,"trace-evidence");}
        @Override public Accepted verify(TenantScope scope,Envelope envelope,RequestBinding binding){
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            if(!"fixture-assertion".equals(envelope.assertion()))throw new IllegalStateException("fixture invalid assertion");
            Observation observation=trusted.get(envelope.payload());if(observation==null || !observation.evidenceId().equals(envelope.eventId()))throw new IllegalStateException("fixture missing trusted evidence");
            return new Accepted("fixture-issuer",envelope.eventId(),binding,new VerifiedInput(observation,protection.subjectKey(observation.snapshot().scope()),1,clock.instant(),clock.instant().plusSeconds(10)));
        }
    }
    /** 测试密钥只在src/test，生产仍无默认KMS实现；AAD/HMAC均包含完整结构而非redacted toString。 */
    static class Crypto implements ProtectedReferralEvidencePort {
        private final ObjectMapper json;
        private final byte[] key="fixture-aes-key-for-tests-only-32".getBytes(StandardCharsets.UTF_8);
        Crypto(ObjectMapper json){this.json=json;}
        String subjectKey(Scope scope){return hmac(json.writeValueAsBytes(List.of(scope.tenantId(),scope.canonicalSubject())));}
        @Override public Sealed sealSnapshot(Header h,Snapshot value){return seal(h,value);}
        @Override public Sealed sealState(Header h,State value){return seal(h,value);}
        @Override public Snapshot openSnapshot(Sealed s){return open(s,Snapshot.class);}
        @Override public State openState(Sealed s){return open(s,State.class);}
        private Sealed seal(Header h,Object value){
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            try{byte[] aad=json.writeValueAsBytes(h),plain=json.writeValueAsBytes(value),nonce=new byte[12];new SecureRandom().nextBytes(nonce);
                Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(Arrays.copyOf(key,32),"AES"),new GCMParameterSpec(128,nonce));cipher.updateAAD(aad);
                byte[] encrypted=cipher.doFinal(plain);return new Sealed(h,ByteBuffer.allocate(12+encrypted.length).put(nonce).put(encrypted).array(),"fixture-key",hmac(join(aad,plain)));
            }catch(GeneralSecurityException e){throw new IllegalStateException("fixture protect failure",e);}
        }
        private <T>T open(Sealed s,Class<T> type){
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            try{byte[] encoded=s.cipher(),aad=json.writeValueAsBytes(s.header());Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(Arrays.copyOf(key,32),"AES"),new GCMParameterSpec(128,Arrays.copyOf(encoded,12)));cipher.updateAAD(aad);
                byte[] plain=cipher.doFinal(Arrays.copyOfRange(encoded,12,encoded.length));if(!MessageDigest.isEqual(hmac(join(aad,plain)).getBytes(StandardCharsets.US_ASCII),s.businessDigest().getBytes(StandardCharsets.US_ASCII)))throw new IllegalStateException("fixture digest mismatch");return json.readValue(plain,type);
            }catch(GeneralSecurityException e){throw new IllegalStateException("fixture open failure",e);}
        }
        private static byte[] join(byte[] a,byte[] b){return ByteBuffer.allocate(4+a.length+b.length).putInt(a.length).put(a).put(b).array();}
        private static String hmac(byte[] bytes){try{Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec("separate-fixture-hmac-key-only".getBytes(StandardCharsets.UTF_8),"HmacSHA256"));return HexFormat.of().formatHex(mac.doFinal(bytes));}catch(GeneralSecurityException e){throw new IllegalStateException(e);}}
    }
}
