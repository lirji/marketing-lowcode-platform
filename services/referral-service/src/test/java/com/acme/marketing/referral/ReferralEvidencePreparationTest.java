package com.acme.marketing.referral;

import static org.junit.jupiter.api.Assertions.*;
import com.acme.marketing.referral.application.evidence.ReferralEvidencePreparation;
import com.acme.marketing.referral.application.evidence.ReferralEvidencePreparation.*;
import com.acme.marketing.referral.ReferralOrderEvidence.*;
import com.acme.marketing.referral.ReferralOrderEvidenceMerger.*;
import java.time.*;
import org.junit.jupiter.api.Test;

/** 无Spring/数据库/KMS的准备边界反例；不能代替事实持久原子性或真实来源验收。 */
class ReferralEvidencePreparationTest {
    static final Instant NOW=Instant.parse("2026-09-08T00:00:00Z");
    static final String HMAC="a".repeat(64), OTHER="b".repeat(64), DIGEST="c".repeat(64);
    static final Scope SCOPE=new Scope("tenant","source","sensitive-canonical-subject","order","org","shop");
    Snapshot snapshot(Scope scope,long revision){return new Snapshot(scope,revision,"first-order-v1",ReferralPolicyEvaluator.Fact.YES,true,OrderState.SETTLED,NOW.minusSeconds(20),100,0,0,100,"CNY");}
    Observation observation(Snapshot snapshot){return new Observation(snapshot,"evidence",NOW.minusSeconds(1),NOW,true);}
    VerifiedInput input(Snapshot snapshot){return new VerifiedInput(observation(snapshot),HMAC,1,NOW,NOW.plusSeconds(10));}
    State state(Snapshot snapshot){return new State(snapshot,NOW.minusSeconds(1),snapshot.settledAt(),NOW.minusSeconds(1),false,0);}
    CurrentRead current(){return new CurrentRead(7,HMAC,1,state(snapshot(SCOPE,1)));}
    HistoryRead unseen(long revision){return new HistoryRead(revision,new NotSeen(),null);}

    Prepared prepare(CurrentRead current,HistoryRead history,VerifiedInput input,Instant now){return ReferralEvidencePreparation.prepare(current,history,input,now,Duration.ofSeconds(10));}
    @Test void lifetimeMustComeFromExplicitTrustedConfiguration(){
        var in=input(snapshot(SCOPE,1));
        assertThrows(IllegalArgumentException.class,()->ReferralEvidencePreparation.prepare(CurrentRead.absent(),unseen(1),in,NOW,null));
        assertThrows(IllegalArgumentException.class,()->ReferralEvidencePreparation.prepare(CurrentRead.absent(),unseen(1),in,NOW,Duration.ofSeconds(9)));
        assertThrows(IllegalArgumentException.class,()->ReferralEvidencePreparation.prepare(CurrentRead.absent(),unseen(1),in,NOW,Duration.ZERO));
    }
    @Test void firstPreparationRequiresAbsentHistoryAndExactLockedProof(){
        var p=prepare(CurrentRead.absent(),unseen(1),input(snapshot(SCOPE,1)),NOW);
        assertEquals(Outcome.APPLIED,p.result().outcome());assertEquals(NOW,p.result().state().firstReceivedAt());assertTrue(p.mayCommit(p.expected(),1,NOW));
        assertFalse(p.mayCommit(p.expected(),2,NOW));
        assertFalse(p.mayCommit(new LockedVersion(p.expected().order(),1,HMAC,1,p.expected().history()),1,NOW));
        assertFalse(p.mayCommit(new LockedVersion(p.expected().order(),0,null,0,new HistoryStamp(1,Presence.PRESENT,DIGEST)),1,NOW));
        assertFalse(p.mayCommit(new LockedVersion(p.expected().order(),0,null,0,new HistoryStamp(2,Presence.ABSENT,null)),1,NOW));
    }
    @Test void existingStateVersionIdentityAndHistoryChangesRequirePreparationOutsideLock(){
        var p=prepare(current(),unseen(2),input(snapshot(SCOPE,2)),NOW);
        assertTrue(p.mayCommit(p.expected(),1,NOW.plusSeconds(1)));
        assertFalse(p.mayCommit(new LockedVersion(p.expected().order(),8,HMAC,1,p.expected().history()),1,NOW));
        assertFalse(p.mayCommit(new LockedVersion(p.expected().order(),7,OTHER,1,p.expected().history()),1,NOW));
        assertFalse(p.mayCommit(new LockedVersion(new OrderKey("tenant","source","other-order"),7,HMAC,1,p.expected().history()),1,NOW));
        assertEquals(current().state().firstReceivedAt(),p.result().state().firstReceivedAt());
    }
    @Test void lockAfterNanosecondExpiryCannotUseMicrosecondRounding(){
        var permit=new VerifiedInput(observation(snapshot(SCOPE,1)),HMAC,1,NOW,NOW.plusNanos(500));
        var p=prepare(CurrentRead.absent(),unseen(1),permit,NOW);
        assertTrue(p.mayCommit(p.expected(),1,NOW.plusNanos(499)));
        assertFalse(p.mayCommit(p.expected(),1,NOW.plusNanos(500)));
        assertFalse(p.mayCommit(p.expected(),1,NOW.plusNanos(600)));
        assertFalse(p.mayCommit(p.expected(),1,NOW.minusNanos(1)));
    }
    @Test void sameOrderScopeOrHmacDriftQuarantinesOriginalWithoutReplacingSubject(){
        for(var scope:new Scope[]{new Scope("tenant","source","changed-subject","order","org","shop"),new Scope("tenant","source",SCOPE.canonicalSubject(),"order","other-org","shop"),new Scope("tenant","source",SCOPE.canonicalSubject(),"order","org","other-shop")}){
            var p=prepare(current(),unseen(2),input(snapshot(scope,2)),NOW);
            assertEquals(Outcome.CONFLICT,p.result().outcome());assertEquals(Reason.SCOPE_MISMATCH,p.result().reason());assertTrue(p.result().state().quarantined());
            assertEquals(current().state().latest(),p.result().state().latest());assertEquals(current().state().firstReceivedAt(),p.result().state().firstReceivedAt());
        }
        var changedKey=new VerifiedInput(observation(snapshot(SCOPE,2)),OTHER,1,NOW,NOW.plusSeconds(10));
        assertTrue(prepare(current(),unseen(2),changedKey,NOW).result().state().quarantined());
    }
    @Test void differentOrderOrKeyVersionCannotOverwriteOriginalRead(){
        var changed=new Scope("tenant","source",SCOPE.canonicalSubject(),"other-order","org","shop");
        assertThrows(IllegalArgumentException.class,()->prepare(current(),unseen(2),input(snapshot(changed,2)),NOW));
        var version=new VerifiedInput(observation(snapshot(SCOPE,2)),HMAC,2,NOW,NOW.plusSeconds(10));
        assertThrows(IllegalArgumentException.class,()->prepare(current(),unseen(2),version,NOW));
    }
    @Test void unknownHistoryRemainsPendingAndHistorySlotCannotBeFabricated(){
        var p=prepare(current(),new HistoryRead(2,new Unavailable(),null),input(snapshot(SCOPE,2)),NOW);
        assertEquals(Reason.HISTORY_UNAVAILABLE,p.result().reason());assertEquals(2,p.result().state().historyPendingRevision());
        assertFalse(ReferralOrderEvidenceMerger.toEvaluatorEvidence(p.result().state()).verified());
        assertThrows(IllegalArgumentException.class,()->new HistoryRead(2,new Seen(snapshot(SCOPE,1)),DIGEST));
        assertThrows(IllegalArgumentException.class,()->prepare(current(),unseen(3),input(snapshot(SCOPE,2)),NOW));
    }
    @Test void futureOrUnverifiedObservationCannotQuarantineCurrent(){
        var altered=new Scope("tenant","source","changed-subject","order","org","shop");
        var future=new Observation(snapshot(altered,2),"event",NOW.plusNanos(1),NOW.plusNanos(1),true);
        var p=prepare(current(),unseen(2),new VerifiedInput(future,OTHER,1,NOW,NOW.plusSeconds(10)),NOW);
        assertEquals(Reason.FUTURE_OR_INCONSISTENT_TIME,p.result().reason());assertFalse(p.result().state().quarantined());
        assertThrows(IllegalArgumentException.class,()->new VerifiedInput(new Observation(snapshot(SCOPE,2),"event",NOW,NOW,false),HMAC,1,NOW,NOW.plusSeconds(10)));
    }
    @Test void replayDoesNotReplaceAnchorsOrClearQuarantineAndDiagnosticsAreRedacted(){
        var old=current();var p=prepare(old,new HistoryRead(1,new Seen(old.state().latest()),DIGEST),input(snapshot(SCOPE,1)),NOW);
        assertEquals(Outcome.REPLAY,p.result().outcome());assertEquals(old.state(),p.result().state());
        var quarantined=new State(old.state().latest(),old.state().firstReceivedAt(),old.state().settledAtAnchor(),old.state().firstSettlementReceivedAt(),true,0);
        var blocked=prepare(new CurrentRead(8,HMAC,1,quarantined),unseen(2),input(snapshot(SCOPE,2)),NOW);
        assertTrue(blocked.result().state().quarantined());
        for(var object:new Object[]{p,p.expected(),old,input(snapshot(SCOPE,1)),new HistoryRead(1,new Seen(old.state().latest()),DIGEST)}){
            assertFalse(object.toString().contains(SCOPE.canonicalSubject()));assertFalse(object.toString().contains(HMAC));
        }
    }
}
