package com.acme.marketing.referral;

import com.acme.marketing.contracts.referral.ReferralAwardIdentity;
import com.acme.marketing.referral.domain.authorization.ReferralRewardAuthorization;
import com.acme.marketing.referral.domain.authorization.ReferralRewardAuthorization.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

class ReferralRewardAuthorizationTest {
    private static final Instant NOW=Instant.parse("2026-09-08T00:00:00.123456789Z");
    private final Identity id=new Identity("tenant","reward",ReferralAwardIdentity.sourceRequestId("tenant","reward"),"sha256:"+"a".repeat(64),7);
    private Window window(){return new Window(NOW.minusSeconds(1),NOW.plusSeconds(1));}
    private EvidenceBasis basis(String hash){return new EvidenceBasis(id,"sha256:"+hash.repeat(64));}
    private NewConfirmation input(){return new NewConfirmation(id,7,basis("b"),basis("b"),window(),window(),window(),"confirmation",1);}
    @Test void permanentReceiptReplaysAfterCandidateAndLeaseExpiry(){
        var first=ReferralRewardAuthorization.eligible(id).confirm(id,1,input(),NOW);
        var replay=first.next().confirm(id,0,null,NOW.plusSeconds(3600));
        assertSame(first.receipt(),replay.receipt());assertTrue(replay.replay());assertEquals(2,replay.currentRevision());
    }
    @Test void cancellationBeforeConfirmationCannotBeRevivedByValidCandidate(){
        var cancelled=ReferralRewardAuthorization.eligible(id).invalidate(id,1,Entitlement.INVALIDATED);
        assertThrows(IllegalStateException.class,()->cancelled.confirm(id,cancelled.version(),input(),NOW));
        assertEquals(Authorization.NONE,cancelled.state());assertNull(cancelled.receipt());
    }
    @Test void cancellationAfterConfirmationKeepsReceiptAndReportsCurrentFence(){
        var first=ReferralRewardAuthorization.eligible(id).confirm(id,1,input(),NOW);
        var cancelled=first.next().invalidate(id,first.currentRevision(),Entitlement.INVALIDATED);
        var replay=cancelled.confirm(id,0,null,NOW.plusSeconds(3600));
        assertEquals(first.receipt(),replay.receipt());assertEquals(Authorization.CANCEL_REQUESTED,replay.currentState());
        assertEquals(cancelled.version(),replay.currentRevision());assertTrue(replay.cancelRevision()>0);
    }
    @Test void staleCancellationSnapshotMustReloadAfterConcurrentConfirmation(){
        var first=ReferralRewardAuthorization.eligible(id).confirm(id,1,input(),NOW);
        assertThrows(IllegalStateException.class,()->first.next().invalidate(id,1,Entitlement.INVALIDATED));
        var cancelled=first.next().invalidate(id,2,Entitlement.INVALIDATED);
        assertEquals(Authorization.CANCEL_REQUESTED,cancelled.state());
    }
    @Test void incomingEvidenceAheadOfProjectionDeniesFirstConfirmation(){
        NewConfirmation in=input();
        var stale=new NewConfirmation(id,7,basis("c"),basis("b"),in.candidateWindow(),in.permitWindow(),in.riskWindow(),"confirmation",1);
        assertThrows(IllegalStateException.class,()->ReferralRewardAuthorization.eligible(id).confirm(id,1,stale,NOW));
    }
    @Test void basisFromDifferentRewardCannotPassEqualRevision(){
        Identity other=new Identity("tenant","other",ReferralAwardIdentity.sourceRequestId("tenant","other"),"sha256:"+"a".repeat(64),7);
        EvidenceBasis wrong=new EvidenceBasis(other,"sha256:"+"b".repeat(64));
        var in=new NewConfirmation(id,7,wrong,wrong,window(),window(),window(),"confirmation",1);
        assertThrows(IllegalStateException.class,()->ReferralRewardAuthorization.eligible(id).confirm(id,1,in,NOW));
    }
    @Test void allThreeWindowsRejectRawNanosExpiryAfterLockWait(){
        Window expired=new Window(NOW.minusSeconds(1),NOW.minusNanos(1));
        for(int field=0;field<3;field++){
            var in=new NewConfirmation(id,7,basis("b"),basis("b"),field==0?expired:window(),field==1?expired:window(),field==2?expired:window(),"confirmation",1);
            assertThrows(IllegalStateException.class,()->ReferralRewardAuthorization.eligible(id).confirm(id,1,in,NOW));
        }
        Window exact=new Window(NOW.minusSeconds(1),NOW);
        var in=new NewConfirmation(id,7,basis("b"),basis("b"),exact,window(),window(),"confirmation",1);
        assertThrows(IllegalStateException.class,()->ReferralRewardAuthorization.eligible(id).confirm(id,1,in,NOW));
    }
    @Test void wrongIdentityOrRevisionCannotReplayOriginalReceipt(){
        var first=ReferralRewardAuthorization.eligible(id).confirm(id,1,input(),NOW);
        for(Identity wrong:new Identity[]{new Identity("tenant","reward",id.sourceRequestId(),"sha256:"+"c".repeat(64),7),new Identity("tenant","reward",id.sourceRequestId(),id.stableClaimsDigest(),8)})
            assertThrows(IllegalStateException.class,()->first.next().confirm(wrong,0,null,NOW));
        var in=new NewConfirmation(id,8,basis("b"),basis("b"),window(),window(),window(),"confirmation",1);
        assertThrows(IllegalStateException.class,()->ReferralRewardAuthorization.eligible(id).confirm(id,1,in,NOW));
    }
    @Test void permanentCancellationCannotBecomeEligibleAgain(){
        var cancelled=ReferralRewardAuthorization.eligible(id).invalidate(id,1,Entitlement.EXPIRED);
        assertSame(cancelled,cancelled.invalidate(id,0,Entitlement.INVALIDATED));
        assertThrows(IllegalStateException.class,()->cancelled.invalidate(id,cancelled.version(),Entitlement.ELIGIBLE));
        assertThrows(IllegalStateException.class,()->new ReferralRewardAuthorization(id,3,Entitlement.ELIGIBLE,Authorization.NONE,null,1));
    }
    @Test void invalidStableSourceAndMalformedUnicodeAreRejectedWithoutPayloadLeak(){
        assertThrows(IllegalStateException.class,()->new Identity("tenant","reward","referral:wrong",id.stableClaimsDigest(),7));
        var error=assertThrows(IllegalStateException.class,()->new Identity("tenant\uD800","reward",id.sourceRequestId(),id.stableClaimsDigest(),7));
        assertFalse(error.toString().contains("tenant"));assertFalse(id.toString().contains("reward"));
    }
}
