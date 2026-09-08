package com.acme.marketing.referral.infrastructure;
import com.acme.marketing.referral.ReferralOrderEvidence.*;
import com.acme.marketing.referral.application.evidence.*;
import com.acme.marketing.referral.application.evidence.ProtectedReferralEvidencePort.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.*;
/** 默认来源/保护均关闭；不存在明文持久或开发密钥回退，不启动真实消息处理。 */
@Configuration
public class ReferralEvidenceConfiguration {
    /** 未签收issuer/JWKS/权威映射时拒绝全部输入。 */
    @Bean @ConditionalOnMissingBean(TrustedReferralEvidencePort.class)
    public TrustedReferralEvidencePort evidenceSources(){return (scope,envelope,binding)->null;}
    /** 未接真实保护来源时不能读取或写入canonicalSubject；异常由应用入口脱敏。 */
    @Bean @ConditionalOnMissingBean(ProtectedReferralEvidencePort.class)
    public ProtectedReferralEvidencePort evidenceProtection(){return new ProtectedReferralEvidencePort(){
        @Override public Sealed sealSnapshot(Header header,Snapshot snapshot){throw unavailable();}
        @Override public Sealed sealState(Header header,State state){throw unavailable();}
        @Override public Snapshot openSnapshot(Sealed sealed){throw unavailable();}
        @Override public State openState(Sealed sealed){throw unavailable();}
        private IllegalStateException unavailable(){return new IllegalStateException("trusted evidence protection unavailable");}
    };}
}
