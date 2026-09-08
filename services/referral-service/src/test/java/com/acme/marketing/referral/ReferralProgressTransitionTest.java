package com.acme.marketing.referral;
import static org.junit.jupiter.api.Assertions.*;
import com.acme.marketing.referral.domain.qualification.ReferralProgressTransition;
import com.acme.marketing.referral.domain.qualification.ReferralProgressTransition.*;
import org.junit.jupiter.api.Test;
/** 纯计数状态机，未连接权威资格输入或数据库，不能作为真实有效人数验收。 */
class ReferralProgressTransitionTest {
    final Key key=new Key("tenant","participant","relation");
    Progress empty(){return new Progress("tenant","participant",0,0,1);}
    @Test void firstQualificationAddsCurrentAndHistoricalOnce(){var result=ReferralProgressTransition.apply(key,null,empty(),true);assertEquals(1,result.progress().validCount());assertEquals(1,result.progress().everQualifiedCount());assertEquals(2,result.progress().revision());assertTrue(result.next().everQualified());}
    @Test void pendingRelationDoesNotInventProgressChange(){var result=ReferralProgressTransition.apply(key,null,empty(),false);assertEquals(empty(),result.progress());assertFalse(result.next().everQualified());assertFalse(result.changesProgress());}
    @Test void refundAndRecoveryOnlyRestoreCurrentCount(){
        var first=ReferralProgressTransition.apply(key,null,empty(),true);var removed=ReferralProgressTransition.apply(key,first.next(),first.progress(),false);
        assertEquals(0,removed.progress().validCount());assertEquals(1,removed.progress().everQualifiedCount());assertTrue(removed.next().everQualified());
        var restored=ReferralProgressTransition.apply(key,removed.next(),removed.progress(),true);assertEquals(1,restored.progress().validCount());assertEquals(1,restored.progress().everQualifiedCount());assertEquals(0,restored.everDelta());assertEquals(4,restored.progress().revision());
    }
    @Test void repeatedSameStateNeverIncrementsCountOrRevision(){var first=ReferralProgressTransition.apply(key,null,empty(),true);var repeated=ReferralProgressTransition.apply(key,first.next(),first.progress(),true);assertEquals(first.progress(),repeated.progress());assertFalse(repeated.changesProgress());}
    @Test void tenantParticipantAndRelationCannotBeReassigned(){
        assertThrows(IllegalArgumentException.class,()->ReferralProgressTransition.apply(key,null,new Progress("other","participant",0,0,1),true));
        assertThrows(IllegalArgumentException.class,()->ReferralProgressTransition.apply(key,null,new Progress("tenant","other",0,0,1),true));
        assertThrows(IllegalArgumentException.class,()->ReferralProgressTransition.apply(key,new Flags(new Key("tenant","participant","other"),true,true),new Progress("tenant","participant",1,1,1),true));
    }
    @Test void corruptFlagsOrMissingCountsFailInsteadOfGoingNegative(){
        assertThrows(IllegalArgumentException.class,()->new Flags(key,true,false));assertThrows(IllegalArgumentException.class,()->new Progress("tenant","participant",2,1,1));
        assertThrows(IllegalArgumentException.class,()->ReferralProgressTransition.apply(key,new Flags(key,true,true),empty(),false));
        assertThrows(IllegalArgumentException.class,()->ReferralProgressTransition.apply(key,new Flags(key,false,true),empty(),true));
    }
    @Test void countAndRevisionOverflowNeverWrap(){
        assertThrows(ArithmeticException.class,()->ReferralProgressTransition.apply(key,null,new Progress("tenant","participant",Long.MAX_VALUE,Long.MAX_VALUE,1),true));
        assertThrows(ArithmeticException.class,()->ReferralProgressTransition.apply(key,null,new Progress("tenant","participant",0,0,Long.MAX_VALUE),true));
        assertEquals(Long.MAX_VALUE,ReferralProgressTransition.apply(key,null,new Progress("tenant","participant",0,0,Long.MAX_VALUE),false).progress().revision());
    }
    @Test void independentRelationsCountIndividuallyUnderSameParticipantProgress(){var first=ReferralProgressTransition.apply(key,null,empty(),true);var other=new Key("tenant","participant","second-relation");var second=ReferralProgressTransition.apply(other,null,first.progress(),true);assertEquals(2,second.progress().validCount());var removed=ReferralProgressTransition.apply(key,first.next(),second.progress(),false);assertEquals(1,removed.progress().validCount());assertEquals(2,removed.progress().everQualifiedCount());}
}
