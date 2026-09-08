package com.acme.marketing.referral.application;
import com.acme.marketing.referral.domain.ReferralParticipant;
import java.time.Instant;
import java.util.Objects;
/** 事务外读取原冻结政策、已发布条款与新绑定运行许可；任何真实来源缺失默认拒绝。 */
public interface ReferralBindingPermitPort {
    /** bindUntil必须由可信条款确定；本服务不猜maxBindAge起点或用当前最新发布替换。 */
    Permit forParticipant(ReferralParticipant participant);
    /** 活动窗口与绝对绑定截止明确分开，达标窗口仅从冻结政策取得。 */
    record Permit(ReferralParticipant participant,String consentVersion,String consentHash,Instant startsAt,Instant endsAt,
            Instant bindUntil,Instant settlementEndsAt,long qualificationWindowSeconds,Instant issuedAt,Instant expiresAt,boolean bindingAllowed) {
        /** 不允许缺绝对截止、无效窗口或超过10秒的运行水位。 */
        public Permit {
            Objects.requireNonNull(participant);ReferralInputs.key(consentVersion,128);ReferralInputs.digest(consentHash);
            Objects.requireNonNull(startsAt);Objects.requireNonNull(endsAt);Objects.requireNonNull(bindUntil);Objects.requireNonNull(settlementEndsAt);Objects.requireNonNull(issuedAt);Objects.requireNonNull(expiresAt);
            if(!startsAt.isBefore(endsAt) || qualificationWindowSeconds<=0 || !issuedAt.isBefore(expiresAt) || expiresAt.isAfter(issuedAt.plusSeconds(10))) throw new IllegalArgumentException("invalid binding permit");
        }
    }
}
