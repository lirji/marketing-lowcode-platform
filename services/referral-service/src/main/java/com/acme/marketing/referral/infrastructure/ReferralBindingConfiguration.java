package com.acme.marketing.referral.infrastructure;
import com.acme.marketing.referral.application.ReferralBindingPermitPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.*;
/** 真实历史条款与maxBindAge截止未签收前不开放默认绑定。 */
@Configuration
public class ReferralBindingConfiguration {
    /** 缺权威来源保持拒绝，不能从参与时间或注册时间补造截止。 */
    @Bean @ConditionalOnMissingBean(ReferralBindingPermitPort.class)
    public ReferralBindingPermitPort bindingPermits() { return participant->null; }
}
