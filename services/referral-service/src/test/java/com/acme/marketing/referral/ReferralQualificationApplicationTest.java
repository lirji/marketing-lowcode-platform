package com.acme.marketing.referral;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.acme.marketing.platform.identity.*;
import com.acme.marketing.platform.error.ConflictException;
import com.acme.marketing.referral.application.*;
import com.acme.marketing.referral.application.evidence.*;
import com.acme.marketing.referral.application.evidence.ReferralEvidencePreparation.OrderKey;
import com.acme.marketing.referral.application.evidence.ReferralEvidenceRepository.StoredOrder;
import com.acme.marketing.referral.application.evidence.ProtectedReferralEvidencePort.*;
import com.acme.marketing.referral.application.qualification.*;
import com.acme.marketing.referral.application.qualification.ReferralQualificationRepository.*;
import com.acme.marketing.referral.application.qualification.ReferralQualificationPermitPort.*;
import com.acme.marketing.referral.domain.*;
import com.acme.marketing.referral.domain.qualification.ReferralProgressTransition.Progress;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
/** 纯应用时限/绑定/CAS反例；真实行锁、DDL与回滚由隔离MySQL专项承担。 */
class ReferralQualificationApplicationTest {
    static final Instant NOW=Instant.parse("2026-09-08T00:00:00Z"),BOUND=NOW.minusSeconds(30);
    static final String SUBJECT="a".repeat(64),HASH="b".repeat(64);
    final ReferralQualificationRepository repo=mock(ReferralQualificationRepository.class);final ReferralInviteRepository participants=mock(ReferralInviteRepository.class);
    final ReferralRepository anchors=mock(ReferralRepository.class);final ReferralEvidenceRepository evidence=mock(ReferralEvidenceRepository.class);
    final ProtectedReferralEvidencePort protection=mock(ProtectedReferralEvidencePort.class);final ReferralQualificationPermitPort permits=mock(ReferralQualificationPermitPort.class);
    final MutableClock clock=new MutableClock();final Manager manager=new Manager();
    final ReferralParticipant participant=new ReferralParticipant("tenant","participant","campaign","org","shop","definition",1,1,"artifact",HASH,1,"ACTIVE",BOUND.minusSeconds(1));
    final ReferralRelation relation=new ReferralRelation("tenant","relation","campaign","org","shop","participant","token","definition",1,1,"artifact",HASH,BOUND,BOUND.plusSeconds(1000),"consent",HASH,"BOUND");
    final Relation owned=new Relation(relation,SUBJECT,1,new byte[]{1},"fixture-key");final Binding binding=new Binding(relation,participant,SUBJECT,1);
    final Task pending=new Task("tenant","relation","participant","org","shop",1,0,"PENDING",NOW,null,null,0);
    final Task claimed=new Task("tenant","relation","participant","org","shop",1,0,"PROCESSING",NOW,"worker",NOW.plusSeconds(30),1);
    final OrderKey orderKey=new OrderKey("tenant","source","order");
    ReferralPlan plan;Permit permit;StoredOrder stored;ReferralOrderEvidence.State state;ReferralQualificationService service;
    TenantScope scope(){return new TenantScope(new TenantId("tenant"),Set.of("org"),Set.of("shop"),"machine",Set.of("referral:evaluate"));}
    ReferralPlan plan(long observation){return new ReferralPlan(BOUND.minusSeconds(60),NOW.plusSeconds(100),BOUND.plusSeconds(2000),1000,observation,60,ReferralPlan.GoalType.FIRST_ORDER_SETTLED,50,"CNY",List.of(new ReferralRewardRule("reward",ReferralRewardRule.Role.INVITER,ReferralRewardRule.Mode.PER_RELATION,1,"benefit-v1","sku-v1",1,1,100)));}
    Permit permit(Binding b,Instant end){return new Permit(b,plan,ReferralPolicyEvaluator.Fact.YES,2,"member-v1",BOUND,null,null,ReferralPolicyEvaluator.Fact.YES,orderKey,"first-v1",Risk.ALLOW,"proof",NOW.minusSeconds(1),end,"f".repeat(64),null);}
    @BeforeEach void setup(){
        plan=plan(0);permit=permit(binding,NOW.plusSeconds(10));
        var snapshot=new ReferralOrderEvidence.Snapshot(new ReferralOrderEvidence.Scope("tenant","source","fixture-sensitive","order","org","shop"),1,"first-v1",ReferralPolicyEvaluator.Fact.YES,true,ReferralOrderEvidence.OrderState.SETTLED,NOW.minusSeconds(10),100,0,0,100,"CNY");
        state=new ReferralOrderEvidence.State(snapshot,NOW.minusSeconds(9),snapshot.settledAt(),NOW.minusSeconds(9),false,0);
        stored=new StoredOrder(orderKey,"resource",1,new Sealed(new Header(orderKey,SUBJECT,1,"org","shop",1,Purpose.CURRENT,false),new byte[16],"fixture-key","c".repeat(64)));
        when(repo.lockTask("tenant","relation")).thenReturn(pending,claimed);when(repo.claim(eq(pending),eq("worker"),any(),any())).thenReturn(claimed);
        when(repo.readRelation("tenant","relation",false)).thenReturn(owned);when(repo.readRelation("tenant","relation",true)).thenReturn(owned);
        when(participants.participant(eq("tenant"),eq("participant"),anyBoolean())).thenReturn(new ReferralInviteRepository.Owned(participant,"d".repeat(64),1));
        when(anchors.subjectIndexVersion("tenant")).thenReturn(1L);when(repo.progress(eq("tenant"),eq("participant"),any())).thenReturn(new Progress("tenant","participant",0,0,1));
        when(permits.prepare(any(),any(),any(),any())).thenAnswer(call->{assertFalse(TransactionSynchronizationManager.isActualTransactionActive());return permit;});
        when(evidence.readOrder(orderKey)).thenAnswer(call->{assertFalse(TransactionSynchronizationManager.isActualTransactionActive());return stored;});
        when(protection.openState(any())).thenAnswer(call->{assertFalse(TransactionSynchronizationManager.isActualTransactionActive());return state;});
        when(repo.lockExistingOrder(orderKey)).thenAnswer(call->{assertTrue(TransactionSynchronizationManager.isActualTransactionActive());return stored;});
        service=new ReferralQualificationService(repo,participants,anchors,evidence,protection,permits,clock,manager,mock(com.acme.marketing.referral.application.reward.ReferralRewardProjectionPort.class),1,30,5,20);
    }
    @AfterEach void reset(){TransactionSynchronizationManager.setActualTransactionActive(false);}
    Qualification run(){return service.process(scope(),"relation","worker","trace");}
    @Test void trustedFactCountsOnceUnderFinalTaskFence(){var q=run();assertEquals("ELIGIBLE",q.state());assertTrue(q.counted());assertTrue(q.everQualified());
        verify(repo).save(eq(claimed),isNull(),eq(q),any(),eq(new Progress("tenant","participant",1,1,2)),eq("machine"),eq("trace"),eq(NOW),isNull());assertEquals(2,manager.commits);}
    @Test void observationDueRetainsNanosecondsAndDoesNotCountEarly(){plan=plan(20);permit=permit(binding,NOW.plusSeconds(10));var s=state.latest();s=new ReferralOrderEvidence.Snapshot(s.scope(),s.revision(),s.firstOrderPolicyVersion(),s.firstEligibleOrder(),true,s.orderState(),s.settledAt().plusNanos(700),100,0,0,100,"CNY");state=new ReferralOrderEvidence.State(s,state.firstReceivedAt(),s.settledAt(),state.firstSettlementReceivedAt(),false,0);
        var q=run();assertEquals("OBSERVATION_PENDING",q.reason());assertEquals(NOW.plusSeconds(10).plusNanos(700),q.dueAt());assertFalse(q.counted());}
    @Test void missingAuthorityRevokesCurrentButKeepsEverAndOrderDependency(){permit=null;var old=old();when(repo.qualification("tenant","relation",true)).thenReturn(old);when(repo.progress(anyString(),anyString(),any())).thenReturn(new Progress("tenant","participant",1,1,2));
        var q=run();assertEquals("PENDING",q.state());assertFalse(q.counted());assertTrue(q.everQualified());assertEquals("resource",q.evidenceResourceId());verify(repo).save(any(),eq(old),eq(q),any(),eq(new Progress("tenant","participant",0,1,3)),anyString(),anyString(),any(),eq(NOW.plusSeconds(5)));}
    @Test void permitExceptionLeaksNoSensitiveTextAndProducesPending(){when(permits.prepare(any(),any(),any(),any())).thenThrow(new IllegalStateException("canonicalSubject/private-kms-address"));assertEquals("AUTHORITY_UNAVAILABLE",run().reason());verify(protection,never()).openState(any());}
    @Test void nanosecondExpiryDuringFinalTaskLockCannotCount(){permit=permit(binding,NOW.plusNanos(500));when(repo.lockTask("tenant","relation")).thenReturn(pending).thenAnswer(call->{clock.now=NOW.plusNanos(600);return claimed;});assertFalse(run().counted());}
    @Test void laterRequestFenceCannotBeCompletedByOldWorker(){Task newer=new Task("tenant","relation","participant","org","shop",2,0,"PENDING",NOW,null,null,2);when(repo.lockTask("tenant","relation")).thenReturn(pending,newer);
        assertEquals("REFERRAL_QUALIFICATION_LEASE_LOST",assertThrows(ConflictException.class,this::run).code());verify(repo,never()).save(any(),any(),any(),any(),any(),anyString(),anyString(),any(),any());assertEquals(1,manager.rollbacks);}
    @Test void changedOrderWatermarkRequiresFreshOutsidePreparation(){var next=new StoredOrder(orderKey,"resource",2,stored.state());doReturn(next).when(repo).lockExistingOrder(orderKey);
        assertEquals("REFERRAL_QUALIFICATION_RETRY",assertThrows(ConflictException.class,this::run).code());verify(permits,times(3)).prepare(any(),any(),any(),any());assertEquals(3,manager.rollbacks);}
    @Test void wrongMemberAsOfIsNeverAcceptedAsBoundTimeNewCustomer(){permit=new Permit(binding,plan,ReferralPolicyEvaluator.Fact.YES,2,"member-v1",BOUND.plusSeconds(1),null,null,ReferralPolicyEvaluator.Fact.YES,orderKey,"first-v1",Risk.ALLOW,"proof",NOW,NOW.plusSeconds(10),"f".repeat(64),null);assertFalse(run().counted());}
    @Test void mismatchedFrozenArtifactCannotReplaceOriginalPlan(){var p=new ReferralParticipant("tenant","participant","campaign","org","shop","definition",2,2,"new-artifact",HASH,1,"ACTIVE",BOUND.minusSeconds(1));permit=permit(new Binding(relation,p,SUBJECT,1),NOW.plusSeconds(10));assertFalse(run().counted());}
    @Test void subjectOrScopeMismatchQuarantinesRatherThanCounting(){stored=new StoredOrder(orderKey,"resource",1,new Sealed(new Header(orderKey,"e".repeat(64),1,"org","shop",1,Purpose.CURRENT,false),new byte[16],"fixture-key","c".repeat(64)));assertEquals("REVIEW",run().state());verify(protection,never()).openState(any());}
    @Test void refundedNetRemovesOnlyCurrentCount(){var old=old();when(repo.qualification("tenant","relation",true)).thenReturn(old);when(repo.progress(anyString(),anyString(),any())).thenReturn(new Progress("tenant","participant",1,1,2));var s=state.latest();s=new ReferralOrderEvidence.Snapshot(s.scope(),1,"first-v1",ReferralPolicyEvaluator.Fact.YES,true,ReferralOrderEvidence.OrderState.REFUNDED,s.settledAt(),100,100,0,0,"CNY");state=new ReferralOrderEvidence.State(s,state.firstReceivedAt(),s.settledAt(),state.firstSettlementReceivedAt(),false,0);var q=run();assertFalse(q.counted());assertTrue(q.everQualified());}
    @Test void missingAnchorAndRealPermissionDenyWithoutProjectionWrites(){when(anchors.subjectIndexVersion("tenant")).thenReturn(null);assertThrows(ConflictException.class,this::run);verify(repo,never()).save(any(),any(),any(),any(),any(),anyString(),anyString(),any(),any());assertThrows(RuntimeException.class,()->service.process(new TenantScope(new TenantId("tenant"),Set.of("org"),Set.of("shop"),"machine",Set.of()),"relation","worker","trace"));}
    @Test void expiryDuringSaveRollsBackWholeProjection(){doAnswer(call->{clock.now=NOW.plusSeconds(10);return null;}).when(repo).save(any(),any(),any(),any(),any(),anyString(),anyString(),any(),any());
        assertEquals("REFERRAL_QUALIFICATION_EXPIRED_BEFORE_COMMIT",assertThrows(ConflictException.class,this::run).code());assertEquals(1,manager.commits);assertEquals(1,manager.rollbacks);}
    @Test void oldMemberRevisionCannotResurrectCorrectedNo(){Qualification old=memberOld(3,"f".repeat(64),false);when(repo.qualification("tenant","relation",true)).thenReturn(old);when(repo.progress(anyString(),anyString(),any())).thenReturn(new Progress("tenant","participant",0,1,3));var q=run();assertFalse(q.counted());assertEquals("MEMBER_REVISION_STALE",q.reason());assertEquals(3,q.memberRevision());}
    @Test void sameRevisionChangedMemberFactsPermanentlyQuarantine(){Qualification old=memberOld(2,"0".repeat(64),false);when(repo.qualification("tenant","relation",true)).thenReturn(old);when(repo.progress(anyString(),anyString(),any())).thenReturn(new Progress("tenant","participant",0,1,3));var q=run();assertEquals("REVIEW",q.state());assertTrue(q.memberQuarantined());assertEquals(old.memberEvidenceDigest(),q.memberEvidenceDigest());assertFalse(q.counted());}
    @Test void laterGoodPermitDoesNotAutomaticallyClearMemberQuarantine(){Qualification old=memberOld(1,"f".repeat(64),true);when(repo.qualification("tenant","relation",true)).thenReturn(old);when(repo.progress(anyString(),anyString(),any())).thenReturn(new Progress("tenant","participant",0,1,3));var q=run();assertTrue(q.memberQuarantined());assertFalse(q.counted());}
    @Test void registeredGoalNeedsTrustedFirstReceiptAnchor(){plan=new ReferralPlan(plan.startsAt(),plan.endsAt(),plan.settlementEndsAt(),1000,0,60,ReferralPlan.GoalType.REGISTERED_NEW_CUSTOMER,0,"CNY",plan.rewards());
        permit=new Permit(binding,plan,ReferralPolicyEvaluator.Fact.YES,2,"member-v1",BOUND,BOUND.minusSeconds(1),BOUND,ReferralPolicyEvaluator.Fact.UNKNOWN,null,null,Risk.ALLOW,"proof",NOW,NOW.plusSeconds(10),"f".repeat(64),"1".repeat(64));
        var q=run();assertTrue(q.counted());assertEquals("1".repeat(64),q.registrationAnchorDigest());verify(evidence,never()).readOrder(any());}
    @Test void changedRegistrationReceiptAnchorCannotMoveLateArrivalWindow(){plan=new ReferralPlan(plan.startsAt(),plan.endsAt(),plan.settlementEndsAt(),1000,0,60,ReferralPlan.GoalType.REGISTERED_NEW_CUSTOMER,0,"CNY",plan.rewards());
        permit=new Permit(binding,plan,ReferralPolicyEvaluator.Fact.YES,3,"member-v1",BOUND,BOUND.minusSeconds(1),BOUND,ReferralPolicyEvaluator.Fact.UNKNOWN,null,null,Risk.ALLOW,"proof",NOW,NOW.plusSeconds(10),"f".repeat(64),"2".repeat(64));
        var old=new Qualification("tenant","relation","participant",plan.goalType().name(),HASH,"PENDING","EVIDENCE_UNAVAILABLE",false,false,1,null,0,2,"member-v1",null,"proof",null,1,"f".repeat(64),false,"1".repeat(64));when(repo.qualification("tenant","relation",true)).thenReturn(old);
        var q=run();assertTrue(q.memberQuarantined());assertFalse(q.counted());assertEquals(old.registrationAnchorDigest(),q.registrationAnchorDigest());}
    Qualification memberOld(long revision,String digest,boolean quarantined){return new Qualification("tenant","relation","participant",plan.goalType().name(),HASH,quarantined?"REVIEW":"INELIGIBLE","NOT_NEW_CUSTOMER",false,true,2,"resource",1,revision,"member-v1","first-v1","proof",null,1,digest,quarantined,null);}
    Qualification old(){return new Qualification("tenant","relation","participant",plan.goalType().name(),HASH,"ELIGIBLE","QUALIFIED",true,true,1,"resource",1,1,"member-v1","first-v1","proof",null,1,"f".repeat(64),false,null);}
    static final class MutableClock extends Clock {Instant now=NOW;@Override public ZoneId getZone(){return ZoneOffset.UTC;}@Override public Clock withZone(ZoneId zone){return this;}@Override public Instant instant(){return now;}}
    static final class Manager implements PlatformTransactionManager {int commits,rollbacks;@Override public TransactionStatus getTransaction(TransactionDefinition d){TransactionSynchronizationManager.setActualTransactionActive(true);return new SimpleTransactionStatus();}@Override public void commit(TransactionStatus s){commits++;TransactionSynchronizationManager.setActualTransactionActive(false);}@Override public void rollback(TransactionStatus s){rollbacks++;TransactionSynchronizationManager.setActualTransactionActive(false);}}
}
