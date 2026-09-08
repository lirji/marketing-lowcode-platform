package com.acme.marketing.benefit;
import com.acme.marketing.benefit.application.*;
import com.acme.marketing.benefit.application.ReferralIntakeConfirmationPort.*;
import com.acme.marketing.benefit.application.ReferralHeldReviewRepository.*;
import java.time.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** 纯端口专项，不触发数据库测试类的静态容器；真实提交原子性由V12 MySQL专项承担。 */
class ReferralHeldReviewServiceTest {
    @Test void reviewOnlyRecordsBoundedObservationAndNeverReconfirmsOrCreatesAnotherHeld() throws Exception {
        var f=new Fixture();var review=f.review();assertEquals(Status.CHECKED,review.observation().status());assertEquals(f.now.plusSeconds(3),review.observation().validUntil());
        assertEquals(1,f.base.held);assertEquals(1,f.base.confirmCalls);assertEquals(1,f.recovers);assertEquals(1,f.risks);
    }
    @Test void cancellationSyncWorksInLegacyWithoutDecryptionRiskAndPreservesOriginalReceipt() throws Exception {
        var f=new Fixture();var receipt=f.base.stored.state().receipt();f.state=State.CANCEL_REQUESTED;f.base.inputs.modes=new AwardDispatchModeRouter("LEGACY","");
        assertEquals(Status.CANCELLED,f.sync().observation().status());assertEquals(0,f.risks);assertEquals(receipt,f.base.stored.state().receipt());
        f.state=State.UNKNOWN;assertEquals(Status.CANCELLED,f.sync().observation().status());assertEquals(1,f.base.held);
    }
    @Test void unknownDoesNotReuseOldCheckedAsDeliveryPermission() throws Exception {
        var f=new Fixture();assertEquals(Status.CHECKED,f.review().observation().status());f.state=State.UNKNOWN;
        var unknown=f.review().observation();assertEquals(Status.UNKNOWN,unknown.status());assertNull(unknown.validUntil());assertEquals(1,f.risks);
    }
    @Test void cancellationBypassesUnavailableRiskAndProofLockWaitMustFailClosed() throws Exception {
        var f=new Fixture();f.state=State.CANCEL_REQUESTED;f.riskUnavailable=true;assertEquals(Status.CANCELLED,f.review().observation().status());assertEquals(0,f.risks);
        var g=new Fixture();doAnswer(a->{g.base.inputs.clock.now=g.now.plusSeconds(5);return g.base.context;}).when(g.base.repository).lock(any());
        assertThrows(RuntimeException.class,g::review);verifyNoInteractions(g.reviews);
    }
    @Test void finalObservationWriteExpiryNeverReturnsChecked() throws Exception {
        var f=new Fixture();f.expireAtRecord=true;assertThrows(RuntimeException.class,f::review);assertEquals(1,f.base.held);
    }
    @Test void disabledAndScopeBoundaryNeverQueryAuthority() throws Exception {
        var f=new Fixture();f.enabled=false;assertTrue(f.review().disabled());assertEquals(0,f.recovers);
        f.enabled=true;f.base.inputs.modes=new AwardDispatchModeRouter("LEGACY","");assertThrows(RuntimeException.class,f::review);assertEquals(0,f.recovers);
    }
    @Test void identityFailureIsSanitizedAndModeFlipCannotProduceChecked() throws Exception {
        var f=new Fixture();f.identityThrows=true;var error=assertThrows(RuntimeException.class,f::review);
        assertFalse(error.toString().contains("sensitive"));assertNull(error.getCause());assertEquals(0,f.recovers);
        var g=new Fixture();g.base.inputs.modes=mock(AwardDispatchModeRouter.class);when(g.base.inputs.modes.modeFor("tenant")).thenReturn(AwardDispatchModeRouter.DeliveryMode.CENTER,AwardDispatchModeRouter.DeliveryMode.LEGACY);assertThrows(RuntimeException.class,g::review);verifyNoInteractions(g.reviews);
    }
    static class Fixture {
        final ReferralAwardIntakeServiceTest.Fixture base=new ReferralAwardIntakeServiceTest.Fixture();
        final ReferralHeldReviewRepository reviews=mock(ReferralHeldReviewRepository.class);final Instant now=ReferralAwardIntentAssemblerTest.NOW;
        State state=State.CONFIRMED;int recovers,risks;long sequence;boolean enabled=true,riskUnavailable,expireAtRecord,identityThrows;
        Fixture() throws Exception {
            base.call(base.token());
            doAnswer(a->{base.active();Result r=a.getArgument(1);var old=base.context;
                if(!old.cancelled())base.context=new ReferralIntakeRepository.Context(old.binding(),old.authorizationIssuedAt(),old.authorizationExpiresAt(),old.riskAction(),old.riskDecisionId(),r,false,old.acceptedIntentId());return base.context;}).when(base.repository).observe(any(),any());
            when(reviews.record(any(),any(),anyLong(),anyLong(),any(),any())).thenAnswer(a->{base.active();if(expireAtRecord)base.inputs.clock.now=now.plusSeconds(4);return new Observation(++sequence,a.getArgument(1),a.getArgument(2),a.getArgument(3),a.getArgument(4),a.getArgument(5));});
        }
        ReferralHeldReviewService service(){return new ReferralHeldReviewService(base.prep,base.repository,reviews,(scope,request)->{base.outside();if(identityThrows)throw new IllegalStateException("sensitive-subject-and-kms");return base.binding;},new ReferralIntakeConfirmationPort(){
            public Result confirm(com.acme.marketing.benefit.domain.ReferralAwardPreparation.Identity id){throw new AssertionError("must recover original receipt");}
            public Result recover(com.acme.marketing.benefit.domain.ReferralAwardPreparation.Identity id){base.outside();recovers++;return state==State.UNKNOWN?null:new Result(id,state,state==State.CANCEL_REQUESTED?3:2,state==State.CANCEL_REQUESTED?3:0,"confirmation",1,now,null,now,now.plusSeconds(5));}
        },(b,p)->{base.outside();risks++;if(riskUnavailable)throw new IllegalStateException("unavailable");return new ReferralIntakeRiskPort.Decision(b.identity(),ReferralIntakeRiskPort.Action.ALLOW,"risk",now,now.plusSeconds(3));},base.protection,base.inputs.modes,base.inputs.clock,base.manager,enabled,Duration.ofSeconds(10));}
        ReferralHeldReviewService.View review(){return service().reviewBeforeDispatch(base.inputs.scope,base.binding.identity().sourceRequestId());}
        ReferralHeldReviewService.View sync(){return service().syncCancellation(base.inputs.scope,base.binding.identity().sourceRequestId());}
    }
}
