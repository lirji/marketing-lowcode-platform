package com.acme.marketing.referral.infrastructure;

import com.acme.marketing.referral.application.fulfillment.ReferralFulfillmentProofPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 未接通实际权益来源时拒绝所有履约事实，不允许业务请求自报成功。 */
@Configuration
public class ReferralFulfillmentConfiguration {
    /** 默认不验收任何外部终态，真实适配器需验证完整奖励/受益人/SKU/来源合同。 */
    @Bean @ConditionalOnMissingBean(ReferralFulfillmentProofPort.class)
    public ReferralFulfillmentProofPort referralFulfillmentProofs(){return (scope,envelope,reward)->null;}
}
