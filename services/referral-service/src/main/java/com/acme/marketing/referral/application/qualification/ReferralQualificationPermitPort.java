package com.acme.marketing.referral.application.qualification;
import com.acme.marketing.platform.identity.TenantScope;
import com.acme.marketing.referral.*;
import com.acme.marketing.referral.domain.*;
import com.acme.marketing.referral.application.evidence.ReferralEvidencePreparation.OrderKey;
import java.time.Instant;
import java.util.Objects;
/** 事务外权威历史许可：真实签名制品、绑定时新客、首单选取与风险/kill-switch须全部来源可信；默认不可用。 */
public interface ReferralQualificationPermitPort {
    /** 禁止在实现中把actorId当主体；密文只交受控身份适配器，未确认来源返回null。 */
    Permit prepare(TenantScope scope,Binding binding,byte[] subjectCipher,String encryptionKeyId);
    /** 完整固定版本及主体HMAC绑定，响应不能把最新发布替换首次参与计划。 */
    record Binding(ReferralRelation relation,ReferralParticipant participant,String subjectKey,long keyVersion) {
        public Binding { Objects.requireNonNull(relation);Objects.requireNonNull(participant);if(subjectKey==null || !subjectKey.matches("[0-9a-f]{64}") || keyVersion<=0)throw new IllegalArgumentException("invalid qualification binding"); }
        @Override public String toString(){return "QualificationBinding[redacted]";}
    }
    /** 风控未知不能默认为放行；此内部合同尚不代表任何真实渠道已经接通。 */
    enum Risk { ALLOW,REJECT,REVIEW,UNKNOWN }
    /**
     * memberRevision为C02正int64，evaluatedAsOf严格等于boundAt。firstOrder由可信C03选定，不能本地排序猜首单。
     * memberEvidenceDigest为完整C02稳定业务事实的独立HMAC，禁止普通低熵摘要；不含重试/运输接收时间。
     * 注册目标另以registrationAnchorDigest对registeredAt+首次可信registrationReceivedAt及完整绑定作独立HMAC；重试不得更换锚。
     * 新修订不得降低，固定口径不可切换，同修订异内容永久隔离。proofId只存脱敏引用；Permit整体须由真实适配器校验签名、租户与冻结制品。生产TTL无默认。
     */
    record Permit(Binding binding,ReferralPlan plan,ReferralPolicyEvaluator.Fact newCustomer,long memberRevision,
            String memberPolicy,Instant evaluatedAsOf,Instant registeredAt,Instant registrationReceivedAt,
            ReferralPolicyEvaluator.Fact firstOrder,OrderKey order,String firstOrderPolicy,Risk risk,String proofId,
            Instant issuedAt,Instant expiresAt,String memberEvidenceDigest,String registrationAnchorDigest) {
        public Permit { Objects.requireNonNull(binding);Objects.requireNonNull(plan);Objects.requireNonNull(newCustomer);Objects.requireNonNull(firstOrder);Objects.requireNonNull(risk);Objects.requireNonNull(issuedAt);Objects.requireNonNull(expiresAt);
            ReferralPolicyValidator.validate(plan);
            if((registrationAnchorDigest!=null && !registrationAnchorDigest.matches("[0-9a-f]{64}")) || memberEvidenceDigest==null || !memberEvidenceDigest.matches("[0-9a-f]{64}") || memberRevision<=0 || memberPolicy==null || memberPolicy.isBlank() || memberPolicy.length()>128 || proofId==null || !proofId.matches("[A-Za-z0-9._:-]{1,128}") || !expiresAt.isAfter(issuedAt))throw new IllegalArgumentException("invalid qualification permit");
        }
        @Override public String toString(){return "QualificationPermit[redacted]";}
    }
}
