package com.acme.marketing.referral.infrastructure;

import com.acme.marketing.referral.application.authorization.ReferralAuthorizationProofPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 未接真实候选验签、目录、风控和当前运行许可时禁止首次确认，不内置ALLOW。 */
@Configuration
public class ReferralAuthorizationConfiguration {
    /** 真实来源装配必须实现整个可信证明合同，不能只校验调用机器身份。 */
    @Bean @ConditionalOnMissingBean(ReferralAuthorizationProofPort.class)
    public ReferralAuthorizationProofPort referralAuthorizationProofs(){return (scope,request,reward)->null;}
}
