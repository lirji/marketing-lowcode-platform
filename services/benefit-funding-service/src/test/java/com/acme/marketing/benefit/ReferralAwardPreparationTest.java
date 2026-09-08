package com.acme.marketing.benefit;

import com.acme.marketing.benefit.domain.ReferralAwardPreparation;
import com.acme.marketing.benefit.domain.ReferralAwardPreparation.*;
import com.acme.marketing.contracts.referral.ReferralAwardIdentity;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 纯状态迁移验证，不能替代准备/receipt/Outbox数据库原子与远端并发确认测试。 */
class ReferralAwardPreparationTest {
    static final Instant NOW=Instant.parse("2026-09-08T00:00:00Z");
    static final Identity ID=new Identity("tenant","marketing-referral",ReferralAwardIdentity.sourceRequestId("tenant","reward"),"reward","sha256:"+"a".repeat(64),"sha256:"+"b".repeat(64),1);
    static final Owner OWNER=new Owner("worker",1);
    static final Receipt RECEIPT=new Receipt(ID,"confirmation",NOW.plusSeconds(1));
    static Admission admission(Instant now){return new Admission(ID,true,"risk-allow",now,now.plusSeconds(20),NOW,NOW.plusSeconds(5));}
    static ReferralAwardPreparation prepared(){return ReferralAwardPreparation.prepare(ID,"worker",NOW,NOW.plusSeconds(30));}
    static ReferralAwardPreparation confirming(){return prepared().beginConfirmation(ID,OWNER,admission(NOW),NOW);}

    @Test void successfulPathPreservesPermanentIdentityAndReplaysOriginalLocalReference() {
        var confirming=confirming();assertEquals(Recovery.RECOVER_SAME_CONFIRMATION,confirming.recovery());
        var confirmed=confirming.recordConfirmation(ID,OWNER,RECEIPT,NOW.plusSeconds(1));
        assertEquals(Recovery.COMMIT_LOCAL_WITH_RECEIPT,confirmed.recovery());
        var accepted=confirmed.acceptLocally(ID,OWNER,"intent-original",admission(NOW),NOW.plusSeconds(2));
        assertEquals("intent-original",accepted.replay(ID));assertEquals(ID,accepted.identity());
        assertEquals(4,accepted.stateVersion());assertEquals(Recovery.REPLAY_LOCAL_ACCEPTANCE,accepted.recovery());
        assertThrows(IllegalStateException.class,()->accepted.takeOver(ID,"later",NOW.plusSeconds(1000),NOW.plusSeconds(2000)));
    }

    @Test void crashAndUnknownBothRecoverSameConfirmationAfterLeaseTakeover() {
        for(boolean timeout:new boolean[]{false,true}) {
            var prior=timeout?confirming().confirmationUnknown(ID,OWNER,NOW.plusSeconds(2)):confirming();
            var takeover=prior.takeOver(ID,"recovery",NOW.plusSeconds(30),NOW.plusSeconds(60));
            assertEquals(prior.phase(),takeover.phase());assertEquals(ID,takeover.identity());assertEquals(2,takeover.lease().fence());
            assertEquals(Recovery.RECOVER_SAME_CONFIRMATION,takeover.recovery());
            assertThrows(IllegalStateException.class,()->takeover.beginConfirmation(ID,new Owner("recovery",2),admission(NOW.plusSeconds(30)),NOW.plusSeconds(30)));
            var recovered=takeover.recordConfirmation(ID,new Owner("recovery",2),RECEIPT,NOW.plusSeconds(31));
            assertEquals(Phase.CONFIRMED,recovered.phase());assertEquals(RECEIPT,recovered.receipt());
        }
    }

    @Test void remoteConfirmedLocalRollbackRetainsPreparationForReceiptRecoveryPastTokenExpiry() {
        var persisted=confirming();
        // 模拟远端已确认、本地提交失败：丢弃未持久化的新状态，保留原CONFIRMING。
        var lostLocal= persisted.recordConfirmation(ID,OWNER,RECEIPT,NOW.plusSeconds(2));
        assertEquals(Phase.CONFIRMED,lostLocal.phase());assertEquals(Phase.CONFIRMING,persisted.phase());
        var takeover=persisted.takeOver(ID,"recovery",NOW.plusSeconds(30),NOW.plusSeconds(60));
        var confirmed=takeover.recordConfirmation(ID,new Owner("recovery",2),RECEIPT,NOW.plusSeconds(31));
        var accepted=confirmed.acceptLocally(ID,new Owner("recovery",2),"same-intent",admission(NOW.plusSeconds(31)),NOW.plusSeconds(32));
        assertEquals("same-intent",accepted.replay(ID));assertEquals(ID.sourceRequestId(),accepted.identity().sourceRequestId());
    }

    @Test void oldFenceExpiredLeaseAndNanosecondDeadlineCannotMutate() {
        var f=confirming();
        for(Owner owner:new Owner[]{new Owner("other",1),new Owner("worker",2)})
            assertThrows(IllegalStateException.class,()->f.confirmationUnknown(ID,owner,NOW.plusSeconds(1)));
        assertThrows(IllegalStateException.class,()->f.confirmationUnknown(ID,OWNER,NOW.plusSeconds(30)));
        var narrow=ReferralAwardPreparation.prepare(ID,"worker",NOW,NOW.plusNanos(500));
        assertThrows(IllegalStateException.class,()->narrow.beginConfirmation(ID,OWNER,admission(NOW),NOW.plusNanos(600)));
        assertThrows(IllegalStateException.class,()->f.takeOver(ID,"other",NOW.plusSeconds(29),NOW.plusSeconds(50)));
    }

    @Test void conflictingReceiptQuarantinesWithoutOverwritingOriginalAndNeverResumes() {
        var original=confirming().recordConfirmation(ID,OWNER,RECEIPT,NOW.plusSeconds(1));
        for(Receipt conflicting:new Receipt[]{new Receipt(ID,"confirmation",NOW.plusSeconds(2)),new Receipt(ID,"different-confirmation",NOW.plusSeconds(1))}) {
            var isolated=original.recordConfirmation(ID,OWNER,conflicting,NOW.plusSeconds(3));
            assertTrue(isolated.quarantined());assertEquals(RECEIPT,isolated.receipt());assertEquals(Recovery.QUARANTINE,isolated.recovery());
            assertThrows(IllegalStateException.class,()->isolated.acceptLocally(ID,OWNER,"intent",admission(NOW),NOW.plusSeconds(4)));
            assertThrows(IllegalStateException.class,()->isolated.takeOver(ID,"next",NOW.plusSeconds(30),NOW.plusSeconds(60)));
        }
        assertSame(original,original.recordConfirmation(ID,OWNER,RECEIPT,NOW.plusSeconds(2)));
    }

    @Test void changedStableIdentityNeverReusesPreparationOrOriginalSuccess() {
        var f=confirming();
        for(Identity other:new Identity[]{
                new Identity("other","marketing-referral",ReferralAwardIdentity.sourceRequestId("other","reward"),"reward",ID.stableClaimsDigest(),ID.payloadHash(),1),
                new Identity("tenant","marketing-referral",ID.sourceRequestId(),"reward","sha256:"+"c".repeat(64),ID.payloadHash(),1),
                new Identity("tenant","marketing-referral",ID.sourceRequestId(),"reward",ID.stableClaimsDigest(),"sha256:"+"c".repeat(64),1),
                new Identity("tenant","marketing-referral",ID.sourceRequestId(),"reward",ID.stableClaimsDigest(),ID.payloadHash(),2)}) {
            assertThrows(IllegalStateException.class,()->f.confirmationUnknown(other,OWNER,NOW.plusSeconds(1)));
            var isolated=f.recordConfirmation(ID,OWNER,new Receipt(other,"confirmation",NOW),NOW.plusSeconds(1));
            assertTrue(isolated.quarantined());assertNull(isolated.receipt());
            var accepted=f.recordConfirmation(ID,OWNER,RECEIPT,NOW.plusSeconds(1)).acceptLocally(ID,OWNER,"intent",admission(NOW),NOW.plusSeconds(2));
            assertThrows(IllegalStateException.class,()->accepted.replay(other));
        }
        assertThrows(IllegalStateException.class,()->new Identity("tenant","drools-activity",ID.sourceRequestId(),"reward",ID.stableClaimsDigest(),ID.payloadHash(),1));
    }

    @Test void onlyExplicitUnconfirmedRejectionTerminatesAndConfirmedCancellationCannotEraseReceipt() {
        var f=confirming();var rejected=f.rejectConfirmation(ID,OWNER,new Rejection(ID,"not-qualified",NOW),NOW.plusSeconds(1));
        assertEquals(Phase.REJECTED,rejected.phase());assertEquals(Recovery.RETAIN_REJECTION,rejected.recovery());
        assertThrows(IllegalStateException.class,()->rejected.takeOver(ID,"new",NOW.plusSeconds(30),NOW.plusSeconds(60)));
        var confirmed=f.recordConfirmation(ID,OWNER,RECEIPT,NOW.plusSeconds(1));
        assertThrows(IllegalStateException.class,()->confirmed.rejectConfirmation(ID,OWNER,new Rejection(ID,"cancelled",NOW),NOW.plusSeconds(2)));
        assertThrows(IllegalStateException.class,()->confirmed.confirmationUnknown(ID,OWNER,NOW.plusSeconds(2)));
    }

    @Test void admissionRequiresCurrentCenterRiskAndFirstAuthorizationButDoesNotInventRetryIdentity() {
        for(Admission invalid:new Admission[]{
                new Admission(ID,false,"risk",NOW,NOW.plusSeconds(10),NOW,NOW.plusSeconds(10)),
                new Admission(ID,true,"risk",NOW,NOW.plusNanos(500),NOW,NOW.plusSeconds(10)),
                new Admission(ID,true,"risk",NOW,NOW.plusSeconds(10),NOW,NOW.plusNanos(500))}) {
            assertThrows(IllegalStateException.class,()->prepared().beginConfirmation(ID,OWNER,invalid,NOW.plusNanos(600)));
        }
        assertThrows(IllegalStateException.class,()->prepared().beginConfirmation(ID,OWNER,null,NOW));
        assertEquals(Recovery.EVALUATE_CURRENT_ADMISSION,prepared().recovery());
        var confirmed=confirming().recordConfirmation(ID,OWNER,RECEIPT,NOW.plusSeconds(1));
        assertThrows(IllegalStateException.class,()->confirmed.acceptLocally(ID,OWNER,"intent",admission(NOW),NOW.plusSeconds(21)));
    }
}
