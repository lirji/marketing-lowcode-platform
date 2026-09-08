package com.acme.marketing.referral.application;
import com.acme.marketing.referral.application.ReferralBindingPermitPort.Permit;
import com.acme.marketing.referral.domain.ReferralParticipant;
import com.acme.marketing.platform.error.ConflictException;
import java.time.*;
import java.time.temporal.ChronoUnit;
/** 确定性历史条款/时点裁决，方便纯测试覆盖边界，不在此推导未签收maxBindAge起点。 */
public final class ReferralBindingTiming {
    private ReferralBindingTiming() {}
    /** 在最后锁后时间计算达标截止；绝对绑定截止必须由受信Port提供。 */
    public static Instant deadline(ReferralParticipant participant,Permit permit,String consentVersion,String consentHash,Instant now) {
        if(permit==null || !"ACTIVE".equals(participant.state()) || !participant.equals(permit.participant()) || !permit.bindingAllowed()
                || !consentVersion.equals(permit.consentVersion()) || !consentHash.equals(permit.consentHash())
                || now.isBefore(permit.issuedAt()) || !now.isBefore(permit.expiresAt()) || now.isBefore(permit.startsAt())
                || !now.isBefore(permit.endsAt()) || !now.isBefore(permit.bindUntil()) || !now.isBefore(permit.settlementEndsAt())) throw unavailable();
        try {
            Instant configured=now.plusSeconds(permit.qualificationWindowSeconds());
            Instant result=(configured.isBefore(permit.settlementEndsAt())?configured:permit.settlementEndsAt()).truncatedTo(ChronoUnit.MICROS);
            if(!now.isBefore(result)) throw unavailable();return result;
        } catch(DateTimeException|ArithmeticException overflow) { throw unavailable(); }
    }
    private static ConflictException unavailable() { return new ConflictException("REFERRAL_BINDING_UNAVAILABLE","frozen binding policy or deadline is unavailable"); }
}
