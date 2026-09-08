package com.acme.marketing.referral.application;
import java.time.Instant;
import java.util.Objects;
/** 事务外读取已验证签名release及新参与kill-switch的短期许可；缺少任一权威依据返回null。 */
public interface ReferralParticipationPermitPort {
    /** 必须由本地可信发布/熔断状态生成，不能由HTTP请求body指定固定版本。 */
    Permit current(String tenantId,String campaignId,String organizationId,String shopId);
    /** 包含冻结规则与范围的有界许可；最多10秒水位沿用生产设计，不表示真实部署已验收。 */
    record Permit(String tenantId,String campaignId,String organizationId,String shopId,String definitionId,
            long definitionVersion,long generation,String artifactId,String policyHash,long routeEpoch,
            Instant campaignStartAt,Instant campaignEndAt,Instant issuedAt,Instant expiresAt,boolean joinsAllowed) {
        /** 非法规则快照不能进入参与者持久化。 */
        public Permit {
            ReferralInputs.key(tenantId,64); ReferralInputs.key(campaignId,64); ReferralInputs.key(organizationId,64);
            ReferralInputs.key(shopId,64); ReferralInputs.key(definitionId,128); ReferralInputs.key(artifactId,160); ReferralInputs.digest(policyHash);
            Objects.requireNonNull(campaignStartAt); Objects.requireNonNull(campaignEndAt); Objects.requireNonNull(issuedAt); Objects.requireNonNull(expiresAt);
            if(definitionVersion<=0 || generation<=0 || routeEpoch<=0 || !campaignStartAt.isBefore(campaignEndAt)
                || !issuedAt.isBefore(expiresAt) || expiresAt.isAfter(issuedAt.plusSeconds(10))) throw new IllegalArgumentException("invalid referral permit");
        }
    }
}
