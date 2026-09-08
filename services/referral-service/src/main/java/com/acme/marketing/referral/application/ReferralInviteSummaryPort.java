package com.acme.marketing.referral.application;
import com.acme.marketing.referral.domain.ReferralParticipant;
import java.time.Instant;
import java.util.Objects;
/** 可信公开活动摘要及已脱敏邀请人来源，不得用硬编码或主体原值构造展示。 */
public interface ReferralInviteSummaryPort {
    /** 只在事务外读取，缺历史活动摘要或无法确认暂停状态时返回null。 */
    Summary forParticipant(ReferralParticipant participant);
    /** 使用原版本的公开条款，摘要不证明邀请资格。 */
    record Summary(ReferralParticipant participant,String title,String publicTerms,String termsVersion,String maskedInviter,Instant asOf,Instant validUntil) {
        /** 限制展示体积与水位，真实脱敏正确性由来源合同另行验收。 */
        public Summary {
            Objects.requireNonNull(participant); Objects.requireNonNull(asOf); Objects.requireNonNull(validUntil);
            if(title==null || title.isBlank() || title.length()>200 || publicTerms==null || publicTerms.isBlank() || publicTerms.length()>8000
                || maskedInviter==null || maskedInviter.isBlank() || maskedInviter.length()>128 || !asOf.isBefore(validUntil) || validUntil.isAfter(asOf.plusSeconds(10))) throw new IllegalArgumentException("invalid invite summary");
            ReferralInputs.key(termsVersion,128);
        }
    }
}
