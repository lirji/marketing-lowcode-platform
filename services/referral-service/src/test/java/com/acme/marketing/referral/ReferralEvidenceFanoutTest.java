package com.acme.marketing.referral;

import com.acme.marketing.platform.error.ConflictException;
import com.acme.marketing.platform.identity.*;
import com.acme.marketing.referral.application.fanout.*;
import com.acme.marketing.referral.application.fanout.ReferralFanoutRepository.*;
import com.acme.marketing.referral.application.qualification.ReferralProjectionEnqueuePort;
import com.acme.marketing.referral.application.qualification.ReferralProjectionEnqueuePort.Signal;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 只验证分页编排/时限和拒绝边界，真实双行事务与UNION另由V5数据库专项证明。 */
class ReferralEvidenceFanoutTest {
    static final Instant NOW=Instant.parse("2026-09-08T00:00:00.123456789Z");
    ReferralFanoutRepository repo;
    ReferralProjectionEnqueuePort queue;
    MutableClock clock;
    Manager manager;
    ReferralEvidenceFanoutService service;
    Task claimed;
    Current current;
    @BeforeEach void setup(){
        repo=mock(ReferralFanoutRepository.class);queue=mock(ReferralProjectionEnqueuePort.class);clock=new MutableClock();manager=new Manager();
        service=new ReferralEvidenceFanoutService(repo,queue,clock,manager,10,2);
        claimed=new Task("tenant","resource",7,"ORDER_EVIDENCE_CHANGED",Status.PROCESSING,null,"worker",NOW.plusSeconds(10),3,4);
        current=new Current("tenant","resource","a".repeat(64),1,"org","shop",7);
        when(repo.lock("tenant","resource")).thenReturn(claimed);when(repo.readCurrent("tenant","resource")).thenReturn(current);
        when(repo.targets(any(),any(),anyInt())).thenReturn(List.of());
    }
    @AfterEach void reset(){TransactionSynchronizationManager.setActualTransactionActive(false);}
    TenantScope scope(){return new TenantScope(new TenantId("tenant"),Set.of("org"),Set.of("shop"),"worker-machine",Set.of("referral:project"));}
    Target target(String id){return new Target("tenant","participant",id,"org","shop");}
    ReferralEvidenceFanoutService.Claim claim(){return new ReferralEvidenceFanoutService.Claim(claimed);}
    @Test void pageEnqueuesOnlyLimitAndAdvancesCursorInSameTransaction(){
        when(repo.targets(any(),any(),eq(3))).thenReturn(List.of(target("a"),target("b"),target("c")));
        doAnswer(call->{assertTrue(TransactionSynchronizationManager.isActualTransactionActive());return null;}).when(queue).enqueue(any());
        var result=service.dispatchPage(scope(),claim());assertEquals(2,result.enqueued());assertTrue(result.more());
        verify(queue).enqueue(new Signal("tenant","participant","a","resource",7,"ORDER_EVIDENCE_CHANGED"));
        verify(queue).enqueue(new Signal("tenant","participant","b","resource",7,"ORDER_EVIDENCE_CHANGED"));verify(queue,times(2)).enqueue(any());
        verify(repo).finish(eq(claimed),eq("b"),eq(true),any());assertEquals(1,manager.commits);
    }
    @Test void newerTargetOwnerOrFenceCannotBeCompletedByOldClaim(){
        for(Task changed:List.of(new Task("tenant","resource",8,claimed.reason(),Status.PROCESSING,null,"worker",claimed.leaseUntil(),4,5),
                new Task("tenant","resource",7,claimed.reason(),Status.PROCESSING,null,"other-worker",claimed.leaseUntil(),4,5))){
            when(repo.lock("tenant","resource")).thenReturn(changed);assertThrows(ConflictException.class,()->service.dispatchPage(scope(),claim()));
        }
        verifyNoInteractions(queue);verify(repo,never()).finish(any(),any(),anyBoolean(),any());assertEquals(2,manager.rollbacks);
    }
    @Test void enqueueWaitPastExpiryRejectsBeforeCursorCommit(){
        when(repo.targets(any(),any(),anyInt())).thenReturn(List.of(target("a")));
        doAnswer(call->{clock.now.set(claimed.leaseUntil());return null;}).when(queue).enqueue(any());
        assertThrows(ConflictException.class,()->service.dispatchPage(scope(),claim()));verify(repo,never()).finish(any(),any(),anyBoolean(),any());assertEquals(1,manager.rollbacks);
    }
    @Test void expiryDuringFinishAlsoRollsBackOwningTransaction(){
        doAnswer(call->{clock.now.set(claimed.leaseUntil().plusNanos(1));return null;}).when(repo).finish(any(),any(),anyBoolean(),any());
        assertThrows(ConflictException.class,()->service.dispatchPage(scope(),claim()));assertEquals(1,manager.rollbacks);assertEquals(0,manager.commits);
    }
    @Test void exactExpiryAndCurrentEvidenceVersionMismatchFailClosed(){
        clock.now.set(claimed.leaseUntil());assertThrows(ConflictException.class,()->service.dispatchPage(scope(),claim()));
        clock.now.set(NOW);when(repo.readCurrent(anyString(),anyString())).thenReturn(new Current("tenant","resource","a".repeat(64),1,"org","shop",8));
        assertThrows(ConflictException.class,()->service.dispatchPage(scope(),claim()));verifyNoInteractions(queue);
    }
    @Test void crossTenantScopeAndUnsortedTargetsCannotAdvanceCursor(){
        when(repo.targets(any(),any(),anyInt())).thenReturn(List.of(new Target("other","participant","a","org","shop")));
        assertThrows(ConflictException.class,()->service.dispatchPage(scope(),claim()));verifyNoInteractions(queue);
        when(repo.targets(any(),any(),anyInt())).thenReturn(List.of(target("b"),target("a")));
        assertThrows(ConflictException.class,()->service.dispatchPage(scope(),claim()));verify(repo,never()).finish(any(),any(),anyBoolean(),any());
        when(repo.readCurrent(anyString(),anyString())).thenReturn(new Current("tenant","resource","a".repeat(64),1,"private-org","shop",7));
        assertThrows(IllegalArgumentException.class,()->service.dispatchPage(scope(),claim()));
    }
    @Test void leaseAcquisitionUsesExplicitDurationAndTruncatesOnlyExpiry(){
        Task pending=new Task("tenant","resource",7,claimed.reason(),Status.PENDING,null,null,null,2,3);
        when(repo.lockNext(eq("tenant"),any())).thenReturn(pending);
        when(repo.claim(eq(pending),eq("worker"),any(),any())).thenAnswer(call->new Task("tenant","resource",7,claimed.reason(),Status.PROCESSING,null,"worker",call.getArgument(2),3,4));
        var result=service.claim(scope(),"worker").orElseThrow();assertEquals(0,result.task().leaseUntil().getNano()%1000);
        assertTrue(result.task().leaseUntil().isBefore(NOW.plusSeconds(10)));assertEquals(1,manager.commits);
    }
    @Test void disabledConfigurationAndAmbientTransactionCannotClaim(){
        var disabled=new ReferralEvidenceFanoutService(repo,queue,clock,manager,0,0);
        assertThrows(ConflictException.class,()->disabled.claim(scope(),"worker"));
        TransactionSynchronizationManager.setActualTransactionActive(true);assertThrows(ConflictException.class,()->service.claim(scope(),"worker"));verify(repo,never()).lockNext(anyString(),any());
        assertThrows(IllegalArgumentException.class,()->new Signal("tenant","p","r",null,0,"ORDER_CHANGED"));
        assertThrows(IllegalArgumentException.class,()->new Signal("tenant","p","r","resource",1,"BOUND"));
    }
    static class MutableClock extends Clock {
        final AtomicReference<Instant> now=new AtomicReference<>(NOW);
        @Override public ZoneId getZone(){return ZoneOffset.UTC;}
        @Override public Clock withZone(ZoneId zone){return this;}
        @Override public Instant instant(){return now.get();}
    }
    static class Manager implements PlatformTransactionManager {
        int commits,rollbacks;
        @Override public TransactionStatus getTransaction(TransactionDefinition definition){TransactionSynchronizationManager.setActualTransactionActive(true);return new SimpleTransactionStatus();}
        @Override public void commit(TransactionStatus status){commits++;TransactionSynchronizationManager.setActualTransactionActive(false);}
        @Override public void rollback(TransactionStatus status){rollbacks++;TransactionSynchronizationManager.setActualTransactionActive(false);}
    }
}
