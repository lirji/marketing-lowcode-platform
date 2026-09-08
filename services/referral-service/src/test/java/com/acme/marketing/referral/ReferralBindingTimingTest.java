package com.acme.marketing.referral;
import com.acme.marketing.referral.application.*;
import com.acme.marketing.referral.application.ReferralBindingPermitPort.Permit;
import com.acme.marketing.referral.domain.ReferralParticipant;
import com.acme.marketing.platform.error.ConflictException;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
/** 纯冻结政策时点测试，不启动Spring或MySQL，不假设maxBindAge起点。 */
class ReferralBindingTimingTest {
    static final Instant NOW=Instant.parse("2026-09-08T04:00:00Z");
    static final ReferralParticipant PARTICIPANT=new ReferralParticipant("tenant","participant","campaign","org","shop","definition",1,1,"artifact","a".repeat(64),1,"ACTIVE",NOW.minusSeconds(500));
    static Permit permit(Instant starts,Instant ends,Instant absoluteBindUntil,Instant settlement,long qualificationSeconds) {
        return new Permit(PARTICIPANT,"terms-v1","b".repeat(64),starts,ends,absoluteBindUntil,settlement,qualificationSeconds,NOW,NOW.plusSeconds(10),true);
    }
    @Test void startIsInclusiveAndAllEndBoundariesAreExclusive() {
        var normal=permit(NOW,NOW.plusSeconds(100),NOW.plusSeconds(80),NOW.plusSeconds(200),30);
        assertEquals(NOW.plusSeconds(30),ReferralBindingTiming.deadline(PARTICIPANT,normal,"terms-v1","b".repeat(64),NOW));
        for(Permit denied:new Permit[]{permit(NOW.plusNanos(1),NOW.plusSeconds(100),NOW.plusSeconds(80),NOW.plusSeconds(200),30),
                permit(NOW.minusSeconds(10),NOW,NOW.plusSeconds(80),NOW.plusSeconds(200),30),
                permit(NOW.minusSeconds(10),NOW.plusSeconds(100),NOW,NOW.plusSeconds(200),30),
                permit(NOW.minusSeconds(10),NOW.plusSeconds(100),NOW.plusSeconds(80),NOW,30)})
            assertThrows(ConflictException.class,()->ReferralBindingTiming.deadline(PARTICIPANT,denied,"terms-v1","b".repeat(64),NOW));
        assertThrows(ConflictException.class,()->ReferralBindingTiming.deadline(PARTICIPANT,normal,"terms-v1","b".repeat(64),NOW.plusSeconds(10)));
    }
    @Test void qualificationDeadlineIsCappedAndOverflowFailsClosed() {
        var cap=permit(NOW,NOW.plusSeconds(100),NOW.plusSeconds(80),NOW.plusSeconds(20),30);
        assertEquals(NOW.plusSeconds(20),ReferralBindingTiming.deadline(PARTICIPANT,cap,"terms-v1","b".repeat(64),NOW));
        var overflow=permit(NOW,NOW.plusSeconds(100),NOW.plusSeconds(80),NOW.plusSeconds(20),Long.MAX_VALUE);
        assertThrows(ConflictException.class,()->ReferralBindingTiming.deadline(PARTICIPANT,overflow,"terms-v1","b".repeat(64),NOW));
    }
    @Test void missingAbsoluteDeadlineOrWrongTermsCannotBorrowCurrentPolicy() {
        assertThrows(NullPointerException.class,()->permit(NOW,NOW.plusSeconds(100),null,NOW.plusSeconds(200),30));
        var permit=permit(NOW,NOW.plusSeconds(100),NOW.plusSeconds(80),NOW.plusSeconds(200),30);
        assertThrows(ConflictException.class,()->ReferralBindingTiming.deadline(PARTICIPANT,null,"terms-v1","b".repeat(64),NOW));
        assertThrows(ConflictException.class,()->ReferralBindingTiming.deadline(PARTICIPANT,permit,"terms-v2","b".repeat(64),NOW));
        assertThrows(ConflictException.class,()->ReferralBindingTiming.deadline(PARTICIPANT,permit,"terms-v1","c".repeat(64),NOW));
        var changed=new ReferralParticipant("tenant","participant","campaign","org","shop","definition",2,2,"new-artifact","a".repeat(64),1,"ACTIVE",PARTICIPANT.createdAt());
        assertThrows(ConflictException.class,()->ReferralBindingTiming.deadline(changed,permit,"terms-v1","b".repeat(64),NOW));
    }
}
