package com.acme.marketing.referral.infrastructure;
import com.acme.marketing.referral.application.qualification.ReferralQualificationPermitPort;
import org.springframework.context.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
/** 未确认C02/C03、历史签名制品和风险来源时默认无许可，不伪造READY。 */
@Configuration
public class ReferralQualificationConfiguration {
    /** 实际来源需单独适配器与生产验收；测试替身不能改变默认拒绝。 */
    @Bean @ConditionalOnMissingBean(ReferralQualificationPermitPort.class)
    ReferralQualificationPermitPort qualificationPermits(){return (scope,binding,cipher,keyId)->null;}
}
