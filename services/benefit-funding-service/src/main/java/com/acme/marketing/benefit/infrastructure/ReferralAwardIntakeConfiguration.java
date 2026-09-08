package com.acme.marketing.benefit.infrastructure;
import com.acme.marketing.benefit.application.*;
import java.time.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.*;
import org.springframework.transaction.PlatformTransactionManager;
/** 专用HELD编排默认关闭；所有真实权威来源均需后续签收并显式替换拒绝端口。 */
@Configuration
public class ReferralAwardIntakeConfiguration {
    @Bean @ConditionalOnMissingBean(ReferralIntakeIdentityPort.class)
    public ReferralIntakeIdentityPort referralIntakeIdentityPort(){return (scope,request)->null;}
    @Bean @ConditionalOnMissingBean(ReferralIntakeRiskPort.class)
    public ReferralIntakeRiskPort referralIntakeRiskPort(){return (binding,payload)->null;}
    @Bean @ConditionalOnMissingBean(ReferralIntakeConfirmationPort.class)
    public ReferralIntakeConfirmationPort referralIntakeConfirmationPort(){return new ReferralIntakeConfirmationPort(){
        public Result confirm(com.acme.marketing.benefit.domain.ReferralAwardPreparation.Identity i){return null;}
        public Result recover(com.acme.marketing.benefit.domain.ReferralAwardPreparation.Identity i){return null;}
    };}
    /** 0是关闭哨兵，启用时必须明确填写租约与权威证明寿命，不提供生产运营默认值。 */
    @Bean public ReferralAwardIntakeService referralAwardIntakeService(ReferralAwardIntentAssembler assembler,ReferralPreparationRepository preparations,
            ReferralIntakeRepository intake,ReferralCandidateSnapshotService protection,ReferralIntakeIdentityPort identities,ReferralIntakeRiskPort risk,
            ReferralIntakeConfirmationPort confirmations,AwardDispatchModeRouter modes,Clock clock,PlatformTransactionManager manager,
            @Value("${marketing.referral-intake.enabled:false}")boolean enabled,@Value("${marketing.referral-intake.lease-seconds:0}")long lease,
            @Value("${marketing.referral-intake.proof-max-seconds:0}")long maximum) {
        return new ReferralAwardIntakeService(assembler,preparations,intake,protection,identities,risk,confirmations,modes,clock,manager,enabled,Duration.ofSeconds(lease),Duration.ofSeconds(maximum));
    }
}
