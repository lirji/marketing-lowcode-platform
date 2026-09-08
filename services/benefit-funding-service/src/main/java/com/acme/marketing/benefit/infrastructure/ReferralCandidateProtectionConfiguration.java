package com.acme.marketing.benefit.infrastructure;

import com.acme.marketing.benefit.application.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

/** 候选保护真实密钥来源未签收，默认失败关闭，不在配置中生成临时生产密钥。 */
@Configuration
public class ReferralCandidateProtectionConfiguration {
    @Bean @ConditionalOnMissingBean(ReferralCandidateProtectionPort.class)
    public ReferralCandidateProtectionPort referralCandidateProtectionPort(){return new ReferralCandidateProtectionPort(){
        public Encrypted encrypt(byte[] aad,byte[] plaintext){throw new IllegalStateException("referral candidate protection unavailable");}
        public byte[] decrypt(byte[] aad,Encrypted encrypted){throw new IllegalStateException("referral candidate protection unavailable");}
    };}
    @Bean public ReferralCandidateSnapshotService referralCandidateSnapshotService(ReferralCandidateProtectionPort protection,ObjectMapper json){return new ReferralCandidateSnapshotService(protection,json);}
}
