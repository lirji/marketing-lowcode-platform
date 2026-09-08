package com.acme.marketing.benefit.application;

import java.security.PublicKey;
import java.time.Duration;
import java.util.Map;

/** 仅由服务配置提供固定签发者和公钥；令牌内容不能指定网络地址或信任来源。 */
public interface ReferralAwardTrustPort {
    /** 未签收租户来源必须返回null；不得把调用方body转为信任配置。 */
    Trust forTenant(String tenantId);

    /** 公钥集合和有效期上限来自受控配置，不设置任何生产默认值。 */
    record Trust(String issuer, Map<String, PublicKey> keys, Duration maxTokenLifetime, Duration maxProofLifetime) {
        public Trust {
            if(issuer==null || issuer.isBlank() || keys==null || keys.isEmpty()
                    || maxTokenLifetime==null || maxTokenLifetime.isZero() || maxTokenLifetime.isNegative()
                    || maxProofLifetime==null || maxProofLifetime.isZero() || maxProofLifetime.isNegative())
                throw new IllegalArgumentException("invalid referral trust configuration");
            keys=Map.copyOf(keys);
        }
        @Override public String toString(){return "ReferralAwardTrust[redacted]";}
    }
}
