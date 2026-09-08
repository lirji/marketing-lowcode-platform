package com.acme.marketing.referral;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.acme.marketing.platform.error.ConflictException;
import com.acme.marketing.platform.identity.*;
import com.acme.marketing.referral.ReferralOrderEvidence.*;
import com.acme.marketing.referral.application.ReferralRepository;
import com.acme.marketing.referral.application.evidence.*;
import com.acme.marketing.referral.application.evidence.TrustedReferralEvidencePort.*;
import com.acme.marketing.referral.application.evidence.ReferralEvidencePreparation.*;
import com.acme.marketing.referral.application.evidence.ProtectedReferralEvidencePort.*;
import com.acme.marketing.referral.application.evidence.ReferralEvidenceRepository.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.*;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import tools.jackson.databind.ObjectMapper;

/** 纯应用反例只验证调用/时限/CAS；内存保护替身不是加密或真实KMS验收。 */
class ReferralEvidenceIntakeApplicationTest {
    static final Instant NOW=Instant.parse("2026-09-08T00:00:00Z");
    final ReferralEvidenceRepository repo=mock(ReferralEvidenceRepository.class);
    final ReferralRepository participants=mock(ReferralRepository.class);
    final TrustedReferralEvidencePort sources=mock(TrustedReferralEvidencePort.class);
    final ProtectedReferralEvidencePort protection=mock(ProtectedReferralEvidencePort.class);
    final MutableClock clock=new MutableClock();final Manager manager=new Manager();final ObjectMapper json=new ObjectMapper();
    final Map<String,Object> plaintext=new HashMap<>();final Map<String,Header> headers=new HashMap<>();
    Snapshot incoming;long lifetimeNanos=10_000_000_000L;Runnable sealHook=()->{};
    ReferralEvidenceIntakeService service;
    TenantScope scope(){return new TenantScope(new TenantId("tenant"),Set.of("org"),Set.of("shop"),"machine",Set.of("referral:evidence"));}
    Envelope envelope(){return new Envelope("event","signed-fixture","payload-fixture","trace");}
    Snapshot snapshot(String order,String subject,String organization,long revision){return new Snapshot(new Scope("tenant","source",subject,order,organization,"shop"),revision,"first-v1",ReferralPolicyEvaluator.Fact.YES,true,OrderState.SETTLED,NOW.minusSeconds(20),100,0,0,100,"CNY");}
    State state(Snapshot s){return new State(s,NOW.minusSeconds(1),s.settledAt(),NOW.minusSeconds(1),false,0);}
    String subject(String canonical){return mac("subject:"+canonical);}
    Header header(Snapshot s,Purpose purpose,boolean quarantined){return new Header(OrderKey.of(s.scope()),subject(s.scope().canonicalSubject()),1,s.scope().organizationId(),s.scope().shopId(),s.revision(),purpose,quarantined);}
    Sealed seal(Header header,Object value){assertFalse(TransactionSynchronizationManager.isActualTransactionActive());sealHook.run();String id=UUID.randomUUID().toString();plaintext.put(id,value);headers.put(id,header);return new Sealed(header,id.getBytes(StandardCharsets.US_ASCII),"fixture-only",mac(json.writeValueAsString(List.of(header,value))));}
    Object open(Sealed sealed){assertFalse(TransactionSynchronizationManager.isActualTransactionActive());String id=new String(sealed.cipher(),StandardCharsets.US_ASCII);assertEquals(headers.get(id),sealed.header());return plaintext.get(id);}
    StoredOrder stored(Snapshot snapshot,long version){return new StoredOrder(OrderKey.of(snapshot.scope()),"original-resource",version,seal(header(snapshot,Purpose.CURRENT,false),state(snapshot)));}
    EventKey event(){return new EventKey("tenant","issuer","source","event");}
    @BeforeEach void setup(){
        incoming=snapshot("order","canonical-sensitive","org",1);clock.value.set(NOW);when(participants.subjectIndexVersion("tenant")).thenReturn(1L);
        doAnswer(call->{assertFalse(TransactionSynchronizationManager.isActualTransactionActive());return new Accepted("issuer","event",call.getArgument(2),new VerifiedInput(new Observation(incoming,"source-event",NOW.minusSeconds(1),NOW,true),subject(incoming.scope().canonicalSubject()),1,NOW,NOW.plusNanos(lifetimeNanos)));}).when(sources).verify(any(),any(),any());
        doAnswer(call->seal(call.getArgument(0),call.getArgument(1))).when(protection).sealSnapshot(any(),any());
        doAnswer(call->seal(call.getArgument(0),call.getArgument(1))).when(protection).sealState(any(),any());
        doAnswer(call->open(call.getArgument(0))).when(protection).openSnapshot(any());doAnswer(call->open(call.getArgument(0))).when(protection).openState(any());
        doAnswer(call->new Inbox(call.getArgument(0),call.getArgument(1),call.getArgument(2),null)).when(repo).lockInbox(any(),anyString(),any(),any());
        doAnswer(call->new StoredOrder(call.getArgument(0),"new-resource",0,null)).when(repo).lockOrder(any(),anyString(),any());
        doAnswer(call->((StoredOrder)call.getArgument(0)).rowVersion()+1).when(repo).replaceOrder(any(),any(),any());
        service=new ReferralEvidenceIntakeService(repo,participants,sources,protection,clock,manager,1,10);
    }
    @AfterEach void reset(){TransactionSynchronizationManager.setActualTransactionActive(false);}
    @Test void firstAcceptedFactUsesLockedProofAndOnlyQueuesPendingProjection(){
        var result=service.accept(scope(),envelope());assertEquals("APPLIED",result.outcome());assertEquals(1,result.stateVersion());
        var order=inOrder(participants,repo);order.verify(participants).subjectIndexVersion("tenant");order.verify(repo).lockInbox(any(),anyString(),any(),any());order.verify(repo).lockOrder(any(),anyString(),any());order.verify(repo).lockHistory(any(),eq(1L));
        verify(repo).insertHistory(any(),eq(NOW),any());verify(repo).changed(any(),eq(1L),eq("READY_FOR_EVALUATOR"),eq("machine"),eq("trace"),any());assertEquals(1,manager.commits);
    }
    @Test void changedHistoryProofRequiresOutsideTransactionPreparationAgain(){
        var prior=seal(header(incoming,Purpose.HISTORY,false),incoming);when(repo.lockHistory(any(),eq(1L))).thenReturn(prior);
        assertEquals("REFERRAL_EVIDENCE_CONCURRENT_CHANGE",assertThrows(ConflictException.class,()->service.accept(scope(),envelope())).code());
        verify(repo,times(3)).lockHistory(any(),eq(1L));verify(repo,never()).replaceOrder(any(),any(),any());assertEquals(3,manager.rollbacks);
    }
    @Test void lastLockedReadPastNanosecondExpiryRejectsAllNewWrites(){
        lifetimeNanos=500;doAnswer(call->{clock.value.set(NOW.plusNanos(600));return null;}).when(repo).lockHistory(any(),anyLong());
        assertThrows(ConflictException.class,()->service.accept(scope(),envelope()));verify(repo,never()).insertHistory(any(),any(),any());verify(repo,never()).completeInbox(any(),any(),any());assertEquals(1,manager.rollbacks);
    }
    @Test void protectionDelayCannotAuthorizeNewFactAfterPermitExpiry(){
        doAnswer(call->{var sealed=seal(call.getArgument(0),call.getArgument(1));clock.value.set(NOW.plusSeconds(10));return sealed;}).when(protection).sealState(any(),any());
        assertThrows(ConflictException.class,()->service.accept(scope(),envelope()));verify(repo,never()).replaceOrder(any(),any(),any());assertEquals(1,manager.rollbacks);
    }
    @Test void successfulInboxReplayDoesNotDecryptOrDependOnCurrentAggregate(){
        var protectedInput=seal(header(incoming,Purpose.HISTORY,false),incoming);var original=new Result("original-resource","APPLIED","READY_FOR_EVALUATOR",1);var receipt=new Inbox(event(),protectedInput.businessDigest(),OrderKey.of(incoming.scope()),original);
        when(repo.readInbox(event())).thenReturn(receipt);doReturn(receipt).when(repo).lockInbox(any(),anyString(),any(),any());
        assertEquals(original,service.accept(scope(),envelope()));verify(repo,never()).readOrder(any());verify(protection,never()).openState(any());verify(repo,never()).changed(any(),anyLong(),anyString(),anyString(),anyString(),any());
    }
    @Test void sameEventDifferentOrderQuarantinesOnlyOriginalThenReturnsConflictAfterCommit(){
        var originalSnapshot=incoming;var original=stored(originalSnapshot,7);var receipt=new Inbox(event(),mac("different-original-content"),original.key(),new Result(original.resourceId(),"APPLIED","READY_FOR_EVALUATOR",7));incoming=snapshot("changed-order","changed-canonical","org",2);
        when(repo.readInbox(event())).thenReturn(receipt);when(repo.readOrder(original.key())).thenReturn(original);doReturn(receipt).when(repo).lockInbox(any(),anyString(),any(),any());doReturn(original).when(repo).lockOrder(any(),anyString(),any());
        assertEquals("REFERRAL_EVIDENCE_EVENT_CONFLICT",assertThrows(ConflictException.class,()->service.accept(scope(),envelope())).code());
        verify(repo).replaceOrder(eq(original),argThat(value->value.header().quarantined() && value.header().order().equals(original.key())),any());
        verify(repo,never()).completeInbox(any(),any(),any());verify(repo).conflict(eq(original),eq("machine"),eq("trace"),any());assertEquals(1,manager.commits);assertEquals(0,manager.rollbacks);
    }
    @Test void quarantineMustPersistEvenWhenProtectionKeepsBusinessDigestUnchanged(){
        var original=stored(incoming,7);var receipt=new Inbox(event(),mac("prior-business-content"),original.key(),new Result(original.resourceId(),"APPLIED","READY_FOR_EVALUATOR",7));
        incoming=snapshot("changed-order","changed-canonical","org",2);
        when(repo.readInbox(event())).thenReturn(receipt);when(repo.readOrder(original.key())).thenReturn(original);doReturn(receipt).when(repo).lockInbox(any(),anyString(),any(),any());doReturn(original).when(repo).lockOrder(any(),anyString(),any());
        doAnswer(call->{var result=seal(call.getArgument(0),call.getArgument(1));return new Sealed(result.header(),result.cipher(),result.keyId(),original.state().businessDigest());}).when(protection).sealState(any(),any());
        assertEquals("REFERRAL_EVIDENCE_EVENT_CONFLICT",assertThrows(ConflictException.class,()->service.accept(scope(),envelope())).code());
        verify(repo).replaceOrder(eq(original),argThat(value->value.header().quarantined() && value.businessDigest().equals(original.state().businessDigest())),any());
        verify(repo).changed(eq(original),eq(8L),eq("INBOX_CONTENT_CONFLICT"),eq("machine"),eq("trace"),any());assertEquals(1,manager.commits);
    }
    @Test void scopeDriftCanOnlyInvalidateAuthorizedOriginalScope(){
        var original=stored(incoming,7);when(repo.readOrder(original.key())).thenReturn(original);doReturn(original).when(repo).lockOrder(any(),anyString(),any());incoming=snapshot("order","canonical-sensitive","other-org",2);
        var result=service.accept(scope(),envelope());assertEquals("CONFLICT",result.outcome());assertEquals("SCOPE_MISMATCH",result.reason());
        verify(repo).replaceOrder(eq(original),argThat(value->value.header().quarantined() && value.header().organizationId().equals("org")),any());
    }
    @Test void originalScopeMustBeAuthorizedAndAuthorityExceptionsAreSanitized(){
        var privateOrder=stored(snapshot("order","canonical-sensitive","private-org",1),7);when(repo.readOrder(privateOrder.key())).thenReturn(privateOrder);
        assertThrows(ConflictException.class,()->service.accept(scope(),envelope()));verify(repo,never()).lockInbox(any(),anyString(),any(),any());
        doThrow(new IllegalStateException("canonical-sensitive http://private-kms.internal")).when(sources).verify(any(),any(),any());
        var failure=assertThrows(ConflictException.class,()->service.accept(scope(),envelope()));assertNull(failure.getCause());assertFalse(failure.getMessage().contains("canonical-sensitive"));assertFalse(failure.getMessage().contains("private-kms"));
    }
    @Test void missingLifetimeAndAmbientTransactionDefaultToRefusal(){
        var disabled=new ReferralEvidenceIntakeService(repo,participants,sources,protection,clock,manager,1,0);
        assertThrows(ConflictException.class,()->disabled.accept(scope(),envelope()));verify(repo,never()).lockInbox(any(),anyString(),any(),any());
        TransactionSynchronizationManager.setActualTransactionActive(true);assertEquals("REFERRAL_OUTER_TRANSACTION_FORBIDDEN",assertThrows(ConflictException.class,()->service.accept(scope(),envelope())).code());
        var defaults=new com.acme.marketing.referral.infrastructure.ReferralEvidenceConfiguration();assertNull(defaults.evidenceSources().verify(scope(),envelope(),null));
    }
    static String mac(String text){try{Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec("unit-fixture-key-not-production".getBytes(StandardCharsets.US_ASCII),"HmacSHA256"));return HexFormat.of().formatHex(mac.doFinal(text.getBytes(StandardCharsets.UTF_8)));}catch(Exception failure){throw new AssertionError(failure);}}
    static class MutableClock extends Clock {final AtomicReference<Instant> value=new AtomicReference<>(NOW);@Override public ZoneId getZone(){return ZoneOffset.UTC;}@Override public Clock withZone(ZoneId zone){return this;}@Override public Instant instant(){return value.get();}}
    static class Manager implements PlatformTransactionManager {int commits,rollbacks;@Override public TransactionStatus getTransaction(TransactionDefinition definition){TransactionSynchronizationManager.setActualTransactionActive(true);return new SimpleTransactionStatus();}@Override public void commit(TransactionStatus status){commits++;TransactionSynchronizationManager.setActualTransactionActive(false);}@Override public void rollback(TransactionStatus status){rollbacks++;TransactionSynchronizationManager.setActualTransactionActive(false);}}
}
