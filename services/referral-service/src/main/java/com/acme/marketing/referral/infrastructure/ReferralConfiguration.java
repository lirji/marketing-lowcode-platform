package com.acme.marketing.referral.infrastructure;
import com.acme.marketing.referral.application.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
/** 未接通真实身份/release/kill-switch时只装配拒绝实现，不允许开发配置模拟生产成功。 */
@Configuration
public class ReferralConfiguration {
    /** 默认没有可生成受信HMAC身份的来源，禁止接受调用方自报键。 */
    @Bean @ConditionalOnMissingBean(TrustedReferralSubjectPort.class)
    public TrustedReferralSubjectPort referralSubjects() { return (scope,assertion,binding)->{ throw new IllegalStateException("referral identity unavailable"); }; }
    /** 默认没有活动运行许可，任何首次加入都应失败关闭。 */
    @Bean @ConditionalOnMissingBean(ReferralParticipationPermitPort.class)
    public ReferralParticipationPermitPort referralParticipationPermits() { return (tenant,campaign,organization,shop)->null; }
}
