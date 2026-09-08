package com.acme.marketing.referral.infrastructure;
import com.acme.marketing.referral.application.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.*;
/** 邀请令牌默认无加密、历史许可或展示来源，不能用开发假数据启动生产发行。 */
@Configuration
public class ReferralInviteConfiguration {
    /** 默认拒绝任何token加解密，不生成临时本地生产密钥。 */
    @Bean @ConditionalOnMissingBean(ReferralInviteProtectionPort.class)
    public ReferralInviteProtectionPort inviteProtection() {
        return new ReferralInviteProtectionPort() {
            /** 未接可信保护来源时拒绝保存明文。 */
            @Override public Protected encrypt(Context context,String token) { throw new IllegalStateException("invite protection unavailable"); }
            /** 未接可信保护来源时拒绝回显敏感响应。 */
            @Override public String decrypt(Context context,Protected cipher) { throw new IllegalStateException("invite protection unavailable"); }
        };
    }
    /** 不从当前规则冒充参与者历史发布许可。 */
    @Bean @ConditionalOnMissingBean(ReferralHistoricalInvitePermitPort.class)
    public ReferralHistoricalInvitePermitPort historicalInvitePermits() { return participant->null; }
    /** 未接公开活动信息来源前不返回伪展示。 */
    @Bean @ConditionalOnMissingBean(ReferralInviteSummaryPort.class)
    public ReferralInviteSummaryPort inviteSummaries() { return participant->null; }
}
