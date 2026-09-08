package com.acme.marketing.benefit.application;

import com.acme.marketing.benefit.domain.ReferralAwardPreparation.Identity;
import com.acme.marketing.contracts.referral.ReferralAwardAuthorizationClaims;
import com.acme.marketing.platform.identity.TenantScope;

/** 固定机器来源与永久奖励身份核对；默认拒绝，不能从过期/未验签token或普通请求body拼接结果。 */
public interface ReferralIntakeIdentityPort {
    /** 实现必须验证actor专属relay权限和完整Scope；sourceRequest仅用于定位，不是授权凭证。 */
    Binding resolve(TenantScope scope,String sourceRequestId);
    /** 非主体固定上下文；受益主体/issuer等仍被完整stableClaimsDigest绑定。 */
    record Binding(Identity identity,String organizationId,String shopId,String campaignId,String definitionId,
            long definitionVersion,long generation,String artifactId,String artifactHash,String participantId,
            String relationId,Long milestone,String role,String ruleId,int quantity) {
        public Binding {
            java.util.Objects.requireNonNull(identity);
            for(String text:new String[]{organizationId,shopId,campaignId,definitionId,artifactId,artifactHash,participantId,role,ruleId})
                if(text==null || text.isBlank() || text.length()>256)throw new IllegalArgumentException("invalid referral intake binding");
            if(definitionVersion<1 || generation<1 || quantity!=1 || !java.util.Set.of("INVITER","INVITEE").contains(role)
                    || (relationId==null)==(milestone==null) || (milestone!=null && (milestone<1 || !role.equals("INVITER"))))
                throw new IllegalArgumentException("invalid referral intake binding");
        }
        /** 初次与验签claims逐字段匹配，固定摘要还绑定主体、签发者与全部资格声明。 */
        public boolean matches(ReferralAwardAuthorizationClaims c) {
            return identity.tenantId().equals(c.tenantId()) && identity.rewardId().equals(c.rewardId())
                    && identity.sourceRequestId().equals(c.sourceRequestId()) && identity.qualificationRevision()==c.qualificationRevision()
                    && identity.stableClaimsDigest().equals(com.acme.marketing.contracts.referral.ReferralAwardAuthorizationCodec.stableDigest(c))
                    && java.util.Objects.equals(organizationId,c.organizationId()) && java.util.Objects.equals(shopId,c.shopId())
                    && java.util.Objects.equals(campaignId,c.campaignId()) && java.util.Objects.equals(definitionId,c.definitionId())
                    && definitionVersion==c.definitionVersion() && generation==c.generation() && java.util.Objects.equals(artifactId,c.artifactId())
                    && java.util.Objects.equals(artifactHash,c.artifactHash()) && java.util.Objects.equals(participantId,c.participantId())
                    && java.util.Objects.equals(relationId,c.relationId()) && java.util.Objects.equals(milestone,c.milestone())
                    && java.util.Objects.equals(role,c.role().name()) && java.util.Objects.equals(ruleId,c.ruleId()) && quantity==c.quantity();
        }
        @Override public String toString(){return "TrustedReferralIntakeBinding[redacted]";}
    }
}
