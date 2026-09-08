package com.acme.marketing.referral.application;
import com.acme.marketing.referral.domain.ReferralParticipant;
import java.time.Instant;
import java.util.Objects;
/** 按参与者冻结版本读取历史发布许可及当前熔断状态；禁止退回current最新规则。 */
public interface ReferralHistoricalInvitePermitPort {
    /** 缺历史制品、有效签名或熔断水位时返回null；只允许事务外调用。 */
    Permit forParticipant(ReferralParticipant participant);
    /** tokenExpiresAt来自冻结政策，不是应用自行猜测活动期限。 */
    record Permit(ReferralParticipant participant,Instant issuedAt,Instant expiresAt,Instant tokenExpiresAt,boolean issuingAllowed) {
        /** 运行许可最多10秒；固定参与者版本和值必须完整匹配查询对象。 */
        public Permit {
            Objects.requireNonNull(participant); Objects.requireNonNull(issuedAt); Objects.requireNonNull(expiresAt); Objects.requireNonNull(tokenExpiresAt);
            if(!issuedAt.isBefore(expiresAt) || expiresAt.isAfter(issuedAt.plusSeconds(10)) || !issuedAt.isBefore(tokenExpiresAt)) throw new IllegalArgumentException("invalid historical invite permit");
        }
    }
}
