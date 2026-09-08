package com.acme.marketing.contracts.referral;

import java.time.Instant;

/** 单份裂变奖励的不可变授权声明；签名有效仍须在线消费资格，不能直接当作已发奖。 */
public record ReferralAwardAuthorizationClaims(
        String issuer, String audience, String tenantId, String organizationId, String shopId,
        String rewardId, String sourceRequestId, String campaignId, String definitionId,
        long definitionVersion, long generation, String artifactId, String artifactHash,
        String participantId, String relationId, Long milestone, String beneficiarySubject,
        Role role, String ruleId, int quantity, long qualificationRevision,
        Instant issuedAt, Instant expiresAt) {
    /** 精确保留主体字符；拒绝非法代理项，避免UTF-8替换造成不同身份摘要碰撞。 */
    public ReferralAwardAuthorizationClaims {
        text(issuer, "issuer", 256); text(audience, "audience", 256);
        text(tenantId, "tenantId", 64); text(organizationId, "organizationId", 128);
        text(shopId, "shopId", 128); text(rewardId, "rewardId", 128);
        text(sourceRequestId, "sourceRequestId", 128); text(campaignId, "campaignId", 64);
        text(definitionId, "definitionId", 128); text(artifactId, "artifactId", 128);
        text(participantId, "participantId", 128); text(beneficiarySubject, "beneficiarySubject", 256);
        text(ruleId, "ruleId", 128);
        if (!sourceRequestId.equals(ReferralAwardIdentity.sourceRequestId(tenantId, rewardId))) invalid("sourceRequestId");
        if (artifactHash == null || !artifactHash.matches("sha256:[a-f0-9]{64}")) invalid("artifactHash");
        if (definitionVersion < 1 || generation < 1 || qualificationRevision < 1) invalid("revision");
        if (quantity != 1 || role == null) invalid("quantity/role");
        if ((relationId == null) == (milestone == null)) invalid("relation/milestone");
        if (relationId != null) text(relationId, "relationId", 128);
        if (milestone != null && (milestone < 1 || role != Role.INVITER)) invalid("milestone");
        if (issuedAt == null || expiresAt == null || !expiresAt.isAfter(issuedAt)) invalid("time");
    }

    /** 首期只有邀请人与好友两个受益角色，不接受调用侧任意角色名称。 */
    public enum Role { INVITER, INVITEE }

    /** 日志只提供固定标识，防止record默认打印受益主体与完整授权内容。 */
    @Override public String toString() { return "ReferralAwardAuthorizationClaims[redacted]"; }

    static void text(String value, String field, int max) {
        if (value == null || value.isBlank() || value.codePointCount(0, value.length()) > max) invalid(field);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i))) invalid(field);
            } else if (Character.isLowSurrogate(c)) invalid(field);
        }
    }

    private static void invalid(String field) { throw new IllegalArgumentException("invalid referral claim: " + field); }
}
