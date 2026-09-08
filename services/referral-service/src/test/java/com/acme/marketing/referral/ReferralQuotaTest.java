package com.acme.marketing.referral;

import com.acme.marketing.referral.domain.quota.ReferralQuota;
import com.acme.marketing.referral.domain.quota.ReferralQuota.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ReferralQuotaTest {
    private final Key key=new Key("tenant","campaign","rule",0);
    private Bucket bucket(long count){return new Bucket(key,count,count,0,0,1,1);}
    private Change reserve(Bucket b,String reward,Reservation r){return ReferralQuota.reserve(b,b.stamp(),reward,r,ReversalPolicy.RETAIN_CONSUMED);}
    private FinalProof proof(Key k,String r,FinalFact f){return new FinalProof(k,r,f,"sha256:"+"a".repeat(64));}
    @Test void repeatedUnknownAndSameRewardNeverReserveTwice(){
        Change c=reserve(bucket(1),"reward",null);
        for(int i=0;i<100;i++){
            c=reserve(c.bucket(),"reward",c.reservation());
            c=ReferralQuota.retainUnknown(c.bucket(),c.bucket().stamp(),c.reservation());
            assertEquals(1,c.bucket().reserved());assertEquals(0,c.bucket().available());
        }
        assertEquals(Decision.WAIT_QUOTA,reserve(c.bucket(),"other",null).decision());
    }
    @Test void successReplayAndReversalRetainConsumedByFrozenDefault(){
        Change c=reserve(bucket(1),"r",null);
        c=ReferralQuota.applyFinal(c.bucket(),c.bucket().stamp(),c.reservation(),proof(key,"r",FinalFact.SUCCEEDED));
        Change same=ReferralQuota.applyFinal(c.bucket(),c.bucket().stamp(),c.reservation(),proof(key,"r",FinalFact.SUCCEEDED));
        assertEquals(c.bucket(),same.bucket());
        c=ReferralQuota.applyFinal(c.bucket(),c.bucket().stamp(),c.reservation(),proof(key,"r",FinalFact.REVERSED_AFTER_ISSUE));
        assertEquals(1,c.bucket().consumed());assertEquals(0,c.bucket().available());
        assertEquals(Decision.RETAIN_TERMINAL,reserve(c.bucket(),"r",c.reservation()).decision());
    }
    @Test void explicitFrozenReversalPolicyCanReleaseOnceAndCannotBeChanged(){
        Bucket b=bucket(1);
        Change c=ReferralQuota.reserve(b,b.stamp(),"r",null,ReversalPolicy.RELEASE_AFTER_CONFIRMED_REVERSAL);
        final Change reserved=c;
        assertThrows(IllegalStateException.class,()->reserve(reserved.bucket(),"r",reserved.reservation()));
        c=ReferralQuota.applyFinal(c.bucket(),c.bucket().stamp(),c.reservation(),proof(key,"r",FinalFact.SUCCEEDED));
        c=ReferralQuota.applyFinal(c.bucket(),c.bucket().stamp(),c.reservation(),proof(key,"r",FinalFact.REVERSED_AFTER_ISSUE));
        assertEquals(1,c.bucket().available());assertEquals(0,c.bucket().consumed());
        assertEquals(c.bucket(),ReferralQuota.applyFinal(c.bucket(),c.bucket().stamp(),c.reservation(),proof(key,"r",FinalFact.REVERSED_AFTER_ISSUE)).bucket());
    }
    @Test void confirmedNotIssuedReleasesButPermanentIdentityCannotReserveAgain(){
        Change c=reserve(bucket(1),"r",null);
        c=ReferralQuota.applyFinal(c.bucket(),c.bucket().stamp(),c.reservation(),proof(key,"r",FinalFact.CONFIRMED_NOT_ISSUED));
        assertEquals(1,c.bucket().available());
        assertEquals(Decision.RETAIN_TERMINAL,reserve(c.bucket(),"r",c.reservation()).decision());
        final Change done=c;
        assertThrows(IllegalStateException.class,()->ReferralQuota.applyFinal(done.bucket(),done.bucket().stamp(),done.reservation(),proof(key,"r",FinalFact.SUCCEEDED)));
    }
    @Test void sameOutcomeDifferentDigestAndCrossScopeAreRejected(){
        Change c=reserve(bucket(1),"r",null);
        Change done=ReferralQuota.applyFinal(c.bucket(),c.bucket().stamp(),c.reservation(),proof(key,"r",FinalFact.SUCCEEDED));
        assertThrows(IllegalStateException.class,()->ReferralQuota.applyFinal(done.bucket(),done.bucket().stamp(),done.reservation(),new FinalProof(key,"r",FinalFact.SUCCEEDED,"sha256:"+"b".repeat(64))));
        assertThrows(IllegalStateException.class,()->ReferralQuota.applyFinal(c.bucket(),c.bucket().stamp(),c.reservation(),proof(new Key("other","campaign","rule",0),"r",FinalFact.SUCCEEDED)));
    }
    @Test void transferOnlyAvailablePreservesBothTotalsAndFencesOldWorkers(){
        Change c=reserve(bucket(5),"r",null);
        Bucket destination=new Bucket(new Key("tenant","campaign","rule",1),2,2,0,0,1,1);
        Transfer t=ReferralQuota.transfer(c.bucket(),c.bucket().stamp(),destination,destination.stamp(),4);
        assertEquals(7,t.source().allocated()+t.destination().allocated());assertEquals(1,t.source().reserved());
        assertThrows(IllegalStateException.class,()->ReferralQuota.reserve(t.source(),c.bucket().stamp(),"late",null,ReversalPolicy.RETAIN_CONSUMED));
        assertThrows(IllegalStateException.class,()->ReferralQuota.transfer(t.source(),t.source().stamp(),t.destination(),t.destination().stamp(),1));
        Change done=ReferralQuota.applyFinal(t.source(),t.source().stamp(),c.reservation(),proof(key,"r",FinalFact.SUCCEEDED));
        assertEquals(1,done.bucket().consumed());
    }
    @Test void crossTenantTransferAndArithmeticOverflowCannotCreateQuota(){
        Bucket b=bucket(1),other=new Bucket(new Key("other","campaign","rule",1),0,0,0,0,1,1);
        assertThrows(IllegalStateException.class,()->ReferralQuota.transfer(b,b.stamp(),other,other.stamp(),1));
        assertThrows(ArithmeticException.class,()->new Bucket(key,Long.MAX_VALUE,Long.MAX_VALUE,1,0,1,1));
        Bucket max=new Bucket(new Key("tenant","campaign","rule",1),Long.MAX_VALUE,Long.MAX_VALUE,0,0,1,1);
        assertThrows(ArithmeticException.class,()->ReferralQuota.transfer(b,b.stamp(),max,max.stamp(),1));
    }
    @Test void successHistorySurvivesReversalUnderBothPolicies(){
        for(ReversalPolicy policy:ReversalPolicy.values()){
            Bucket b=bucket(1);Change c=ReferralQuota.reserve(b,b.stamp(),"r",null,policy);
            c=ReferralQuota.applyFinal(c.bucket(),c.bucket().stamp(),c.reservation(),proof(key,"r",FinalFact.SUCCEEDED));
            c=ReferralQuota.applyFinal(c.bucket(),c.bucket().stamp(),c.reservation(),new FinalProof(key,"r",FinalFact.REVERSED_AFTER_ISSUE,"sha256:"+"b".repeat(64)));
            Change old=ReferralQuota.applyFinal(c.bucket(),c.bucket().stamp(),c.reservation(),proof(key,"r",FinalFact.SUCCEEDED));
            assertEquals(c.bucket(),old.bucket());assertEquals(c.reservation(),old.reservation());assertEquals(Decision.REPLAY,old.decision());
            final Change finalState=c;
            assertThrows(IllegalStateException.class,()->ReferralQuota.applyFinal(finalState.bucket(),finalState.bucket().stamp(),finalState.reservation(),new FinalProof(key,"r",FinalFact.SUCCEEDED,"sha256:"+"c".repeat(64))));
        }
    }
    @Test void longMixedTerminalSequenceConservesCapacityWithoutReusingHistory(){
        Bucket b=bucket(1000);
        for(int i=0;i<1000;i++){
            String id="r"+i;Change c=reserve(b,id,null);
            c=ReferralQuota.applyFinal(c.bucket(),c.bucket().stamp(),c.reservation(),proof(key,id,i%2==0?FinalFact.SUCCEEDED:FinalFact.CANCELLED_BEFORE_ISSUE));
            b=c.bucket();assertEquals(1000,b.available()+b.reserved()+b.consumed());
            assertEquals(Decision.RETAIN_TERMINAL,reserve(b,id,c.reservation()).decision());
        }
        assertEquals(500,b.consumed());assertEquals(500,b.available());assertEquals(0,b.reserved());
    }
}
