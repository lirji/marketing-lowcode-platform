package com.acme.marketing.benefit.infrastructure;

import com.acme.marketing.benefit.application.*;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

/** 纯候选装配；没有真实来源时全部拒绝，不创建HTTP入口或投递worker。 */
@Configuration
public class ReferralAwardCandidateConfiguration {
    /** 没有受控签发者/公钥配置时不得信任任何裂变授权。 */
    @Bean @ConditionalOnMissingBean(ReferralAwardTrustPort.class)
    public ReferralAwardTrustPort referralAwardTrustPort(){return tenant->null;}
    /** 历史发布及冻结目录真实来源尚未签收，不以本地假证明替代。 */
    @Bean @ConditionalOnMissingBean(ReferralAwardProofPort.class)
    public ReferralAwardProofPort referralAwardProofPort(){return authorization->null;}
    /** 注入现有CENTER路由器但不复用旧来源受理编排。 */
    @Bean public ReferralAwardIntentAssembler referralAwardIntentAssembler(ReferralAwardTrustPort trust,
            ReferralAwardProofPort proofs,AwardDispatchModeRouter modes,Clock clock,ObjectMapper json) {
        return new ReferralAwardIntentAssembler(trust,proofs,modes,clock,json);
    }
}
