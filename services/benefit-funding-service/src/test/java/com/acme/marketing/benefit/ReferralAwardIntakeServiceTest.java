package com.acme.marketing.benefit;

import com.acme.marketing.benefit.application.*;
import com.acme.marketing.benefit.application.ReferralAwardIntakeService.*;
import com.acme.marketing.benefit.application.ReferralIntakeConfirmationPort.*;
import com.acme.marketing.benefit.application.ReferralIntakeIdentityPort.Binding;
import com.acme.marketing.benefit.application.ReferralPreparationRepository.*;
import com.acme.marketing.benefit.domain.ReferralAwardPreparation;
import com.acme.marketing.benefit.infrastructure.ReferralAwardIntakeConfiguration;
import com.acme.marketing.platform.error.ConflictException;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 真实签名与事务同步标志验证编排；内存仓储只用于端口交互，真实原子性另由MySQL专项。 */
class ReferralAwardIntakeServiceTest {
    @Test void preparationAndConfirmingCommitBeforeOutsideRemoteAndAcceptanceRemainsHeld() throws Exception {
        var f=new Fixture();var result=f.call(f.token());
        assertEquals(Status.HELD_ACCEPTED,result.status());assertEquals(1,f.held);assertEquals(1,f.confirmCalls);
        assertEquals(ReferralAwardPreparation.Phase.ACCEPTED,f.stored.state().phase());assertNotNull(f.context.acceptedIntentId());
        assertEquals(result.intentId(),f.context.acceptedIntentId());assertTrue(f.commits>=3);
    }
    @Test void originalSuccessReplaysAfterTokenExpiryWithoutReassemblyOrSecondConfirm() throws Exception {
        var f=new Fixture();var first=f.call(f.token());int assembled=f.inputs.proofCalls;
        f.inputs.clock.now=Fixture.NOW.plusSeconds(1000);var replay=f.call(null);
        assertEquals(first.intentId(),replay.intentId());assertTrue(replay.replay());assertEquals(assembled,f.inputs.proofCalls);assertEquals(1,f.confirmCalls);assertEquals(1,f.held);
    }
    @Test void unknownKeepsPermanentPreparationAndRecoversSameIdentityPastOriginalTokenExpiry() throws Exception {
        var f=new Fixture();f.unknown=true;assertEquals(Status.CONFIRMATION_UNKNOWN,f.call(f.token()).status());
        assertEquals(ReferralAwardPreparation.Phase.CONFIRM_UNKNOWN,f.stored.state().phase());var identity=f.stored.state().identity();
        f.inputs.clock.now=Fixture.NOW.plusSeconds(11);f.unknown=false;
        assertEquals(Status.HELD_ACCEPTED,f.call(null).status());assertEquals(identity,f.stored.state().identity());assertEquals(1,f.recoverCalls);assertEquals(1,f.confirmCalls);
    }
    @Test void cancelledRemoteReceiptCommitsBarrierWithoutLocalAcceptOrHeldRows() throws Exception {
        var f=new Fixture();f.cancelled=true;assertEquals(Status.CANCEL_PENDING,f.call(f.token()).status());
        assertTrue(f.context.cancelled());assertNotNull(f.context.confirmation().confirmationId());assertEquals(0,f.held);
        f.cancelled=false;f.inputs.clock.now=Fixture.NOW.plusSeconds(11);
        assertEquals(Status.CANCEL_PENDING,f.call(null).status());assertEquals(1,f.confirmCalls);
    }
    @Test void unavailableRiskRetainsPreparationAndExplicitRejectCannotBeRekeyedIntoAllow() throws Exception {
        var f=new Fixture();f.action=ReferralIntakeRiskPort.Action.UNAVAILABLE;
        assertEquals(Status.PENDING_RISK,f.call(f.token()).status());assertEquals(ReferralAwardPreparation.Phase.PREPARED,f.stored.state().phase());assertEquals(0,f.confirmCalls);
        f.inputs.clock.now=Fixture.NOW.plusSeconds(11);f.refreshToken();f.action=ReferralIntakeRiskPort.Action.REJECT;
        assertEquals(Status.RISK_REJECTED,f.call(f.token()).status());
        f.action=ReferralIntakeRiskPort.Action.ALLOW;assertEquals(Status.RISK_REJECTED,f.call(null).status());assertEquals(0,f.confirmCalls);
    }
    @Test void finalProofExpiryAndLocalWriteFailureRollBackBeforeAcceptanceReference() throws Exception {
        for(boolean expire:new boolean[]{false,true}) {
            var f=new Fixture();f.expireAtHold=expire;f.failHold=!expire;
            assertThrows(RuntimeException.class,()->f.call(f.token()));assertEquals(ReferralAwardPreparation.Phase.CONFIRMING,f.stored.state().phase());
            assertNull(f.context.acceptedIntentId());assertEquals(0,f.held);
            f.failHold=false;f.expireAtHold=false;f.inputs.clock.now=Fixture.NOW.plusSeconds(11);
            assertEquals(Status.HELD_ACCEPTED,f.call(null).status());assertEquals(1,f.recoverCalls);
        }
    }
    @Test void rejectedRiskExpiryAfterLockCannotBecomePermanentRejection() throws Exception {
        var f=new Fixture();f.action=ReferralIntakeRiskPort.Action.REJECT;f.expireRiskAfterLock=true;
        assertThrows(ConflictException.class,()->f.call(f.token()));
        assertNull(f.context.riskAction());assertEquals(0,f.confirmCalls);
    }
    @Test void finalWriteCannotOutliveLeaseEvenWhenRiskAndConfirmationRemainFresh() throws Exception {
        var f=new Fixture();f.leaseDuration=Duration.ofSeconds(2);f.expireAtHold=true;
        assertThrows(ConflictException.class,()->f.call(f.token()));
        assertNull(f.context.acceptedIntentId());assertEquals(0,f.held);
    }
    @Test void currentMachineScopeAndFixedBindingRemainRequiredForOriginalReplay() throws Exception {
        var f=new Fixture();f.call(f.token());var original=f.binding;
        f.binding=new Binding(original.identity(),"other-org",original.shopId(),original.campaignId(),original.definitionId(),original.definitionVersion(),original.generation(),original.artifactId(),original.artifactHash(),original.participantId(),original.relationId(),original.milestone(),original.role(),original.ruleId(),1);
        assertThrows(RuntimeException.class,()->f.call(null));assertEquals(1,f.held);
    }
    @Test void disabledDefaultNonCenterAndOuterTransactionNeverStartWork() throws Exception {
        var f=new Fixture();f.enabled=false;assertEquals(Status.DISABLED,f.call(null).status());assertNull(f.stored);
        var cfg=new ReferralAwardIntakeConfiguration();assertNull(cfg.referralIntakeIdentityPort().resolve(null,"request"));assertNull(cfg.referralIntakeRiskPort().evaluate(null,null));assertNull(cfg.referralIntakeConfirmationPort().recover(null));
        f.enabled=true;f.inputs.modes=new AwardDispatchModeRouter("LEGACY","");assertThrows(ConflictException.class,()->f.call(f.token()));assertNull(f.stored);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try{assertThrows(ConflictException.class,()->f.call(null));}finally{TransactionSynchronizationManager.setActualTransactionActive(false);}
    }

    @Test void newHeldMapperLoadsWithoutConnectingOrRegisteringOldRelay() throws Exception {
        var datasource=mock(javax.sql.DataSource.class);var factory=new org.mybatis.spring.SqlSessionFactoryBean();factory.setDataSource(datasource);
        factory.setMapperLocations(new org.springframework.core.io.ClassPathResource("mapper/ReferralIntakeMapper.xml"));
        assertTrue(factory.getObject().getConfiguration().hasStatement("com.acme.marketing.benefit.infrastructure.persistence.mapper.ReferralIntakeMapper.heldOutbox"));
        verifyNoInteractions(datasource);
    }

    static class Fixture {
        static final Instant NOW=ReferralAwardIntentAssemblerTest.NOW;
        final ReferralAwardIntentAssemblerTest.Fixture inputs=new ReferralAwardIntentAssemblerTest.Fixture();
        final ReferralPreparationRepository prep=mock(ReferralPreparationRepository.class);final ReferralIntakeRepository repository=mock(ReferralIntakeRepository.class);
        final ReferralCandidateSnapshotService protection=new ReferralCandidateSnapshotService(new ReferralCandidateSnapshotTest.ProtectionFixture(),new ObjectMapper());
        Stored stored;ReferralIntakeRepository.Context context;Binding binding;int held,commits,confirmCalls,recoverCalls;
        boolean enabled=true,unknown,cancelled,failHold,expireAtHold,expireRiskAfterLock,riskReturned;Duration leaseDuration=Duration.ofSeconds(10);ReferralIntakeRiskPort.Action action=ReferralIntakeRiskPort.Action.ALLOW;
        final Manager manager=new Manager();
        Fixture() throws Exception {
            var candidate=inputs.assemble();var id=ReferralCandidateSnapshotTest.identity(candidate);var c=inputs.claims;
            binding=new Binding(id,c.organizationId(),c.shopId(),c.campaignId(),c.definitionId(),c.definitionVersion(),c.generation(),c.artifactId(),c.artifactHash(),c.participantId(),c.relationId(),c.milestone(),c.role().name(),c.ruleId(),c.quantity());
            when(prep.find(anyString(),anyString())).thenAnswer(a->Optional.ofNullable(stored));when(prep.lock(any())).thenAnswer(a->{active();return stored;});
            when(prep.prepare(any(),any(),anyString(),any())).thenAnswer(a->{active();if(stored==null)stored=new Stored(ReferralAwardPreparation.prepare(a.getArgument(0),a.getArgument(2),inputs.clock.instant(),a.getArgument(3)),a.getArgument(1));return stored;});
            when(prep.apply(any(),any())).thenAnswer(a->{active();var idArg=stored.state().identity();var before=stored.state();var now=inputs.clock.instant();Command command=a.getArgument(1);
                var next=switch(command){case TakeOver c1->before.takeOver(idArg,c1.owner(),now,c1.until());case Begin c1->before.beginConfirmation(idArg,c1.owner(),c1.admission(),now);case Unknown c1->before.confirmationUnknown(idArg,c1.owner(),now);case Confirm c1->before.recordConfirmation(idArg,c1.owner(),c1.receipt(),now);case Reject c1->before.rejectConfirmation(idArg,c1.owner(),c1.rejection(),now);case Accept c1->before.acceptLocally(idArg,c1.owner(),c1.intentId(),c1.admission(),now);};stored=new Stored(next,stored.snapshot());return stored;});
            when(repository.find(anyString(),anyString())).thenAnswer(a->context);when(repository.lock(any())).thenAnswer(a->{active();if(riskReturned && expireRiskAfterLock)inputs.clock.now=NOW.plusSeconds(5);return context;});
            when(repository.reserve(any(),any(),any())).thenAnswer(a->{active();if(context==null)context=new ReferralIntakeRepository.Context(a.getArgument(0),a.getArgument(1),a.getArgument(2),null,null,null,false,null);return context;});
            doAnswer(a->{active();ReferralIntakeRiskPort.Decision d=a.getArgument(1);context=new ReferralIntakeRepository.Context(context.binding(),context.authorizationIssuedAt(),context.authorizationExpiresAt(),d==null?"UNAVAILABLE":d.action().name(),d==null?null:d.decisionId(),context.confirmation(),false,null);return null;}).when(repository).recordRisk(any(),any());
            when(repository.observe(any(),any())).thenAnswer(a->{active();context=new ReferralIntakeRepository.Context(context.binding(),context.authorizationIssuedAt(),context.authorizationExpiresAt(),context.riskAction(),context.riskDecisionId(),a.getArgument(1),false,null);return context;});
            doAnswer(a->{active();held++;context=new ReferralIntakeRepository.Context(context.binding(),context.authorizationIssuedAt(),context.authorizationExpiresAt(),context.riskAction(),context.riskDecisionId(),context.confirmation(),false,a.getArgument(1));if(failHold)throw new IllegalStateException("local injected write failure");if(expireAtHold)inputs.clock.now=NOW.plusSeconds(leaseDuration.getSeconds()==2?3:5);return null;}).when(repository).hold(any(),anyString(),any());
        }
        ReferralAwardIntakeService service(){return new ReferralAwardIntakeService(inputs.service(),prep,repository,protection,(scope,request)->{outside();return binding;},(b,p)->{outside();riskReturned=true;return new ReferralIntakeRiskPort.Decision(b.identity(),action,"risk-fixture",inputs.clock.instant(),inputs.clock.instant().plusSeconds(5));},new ReferralIntakeConfirmationPort(){
            public Result confirm(ReferralAwardPreparation.Identity i){outside();confirmCalls++;assertTrue(commits>=2);assertEquals(ReferralAwardPreparation.Phase.CONFIRMING,stored.state().phase());return result(i);}
            public Result recover(ReferralAwardPreparation.Identity i){outside();recoverCalls++;return result(i);}
            Result result(ReferralAwardPreparation.Identity i){return unknown?null:new Result(i,cancelled?State.CANCEL_REQUESTED:State.CONFIRMED,cancelled?3:2,cancelled?3:0,"confirmation",1,NOW,null,inputs.clock.instant(),inputs.clock.instant().plusSeconds(5));}
        },inputs.modes,inputs.clock,manager,enabled,leaseDuration,Duration.ofSeconds(10));}
        View call(String token){return service().intake(inputs.scope,binding.identity().sourceRequestId(),token);}
        String token(){return inputs.token();}
        void refreshToken(){var now=inputs.clock.now;inputs.claims=inputs.claims(inputs.claims.role(),inputs.claims.relationId(),inputs.claims.milestone(),now,now.plusSeconds(5));inputs.afterProof=now;inputs.proofIssued=now;inputs.proofExpiry=now.plusSeconds(5);}
        void active(){assertTrue(TransactionSynchronizationManager.isActualTransactionActive());}
        void outside(){assertFalse(TransactionSynchronizationManager.isActualTransactionActive());}
        class Manager extends AbstractPlatformTransactionManager {
            @java.io.Serial private static final long serialVersionUID=1L;
            protected Object doGetTransaction(){return new Object[]{stored,context,held};}
            protected void doBegin(Object tx,TransactionDefinition definition){}
            protected void doCommit(DefaultTransactionStatus status){commits++;}
            protected void doRollback(DefaultTransactionStatus status){Object[] old=(Object[])status.getTransaction();stored=(Stored)old[0];context=(ReferralIntakeRepository.Context)old[1];held=(Integer)old[2];}
        }
    }
}
