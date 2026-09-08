package com.acme.marketing.contracts.referral;

import com.acme.marketing.platform.crypto.CanonicalMapCodec;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

/** 永久reward身份到权益中心幂等键的唯一映射；不能用签名、资格revision或重试时间生成新键。 */
public final class ReferralAwardIdentity {
    private ReferralAwardIdentity() { }

    /** rewardId必须先由持久唯一账本确定；租户与reward精确编码，避免分隔符和旧来源碰撞。 */
    public static String sourceRequestId(String tenantId, String rewardId) {
        ReferralAwardAuthorizationClaims.text(tenantId,"tenantId",64);
        ReferralAwardAuthorizationClaims.text(rewardId,"rewardId",128);
        try {
            byte[] value=CanonicalMapCodec.encode(Map.of("format","marketing-referral-source/1",
                    "tenantId",tenantId,"rewardId",rewardId));
            return "referral:"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
