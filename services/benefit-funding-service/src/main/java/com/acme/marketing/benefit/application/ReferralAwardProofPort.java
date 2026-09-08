package com.acme.marketing.benefit.application;

import com.acme.marketing.contracts.referral.ReferralAwardAuthorizationCodec.Verified;
import com.acme.marketing.referral.ReferralPlan;
import java.time.Instant;

/** 历史发布、冻结制品及目录证明边界；普通请求不能提供本端口的结果。 */
public interface ReferralAwardProofPort {
    /**
     * 未来适配器须在固定来源验证release签名、artifact attestation/ABI/原hash和目录引用映射。
     * 同时核对永久奖励身份、角色受益主体与稳定声明摘要；不得只echo传入claims。
     * 此证明不是在线qualification确认；缺少任一权威材料返回null，禁止查询最新版本替代。
     */
    Proof resolve(Verified authorization);

    /** 完整冻结计划参与同源验证，避免仅提供单条摘录掩盖重复ruleId。 */
    record Release(String tenantId,String organizationId,String shopId,String campaignId,
            String definitionId,long definitionVersion,long generation,String artifactId,String artifactHash,
            String abi,ReferralPlan plan) { }

    /**
     * 引用是未签收格式的opaque完整引用；适配器证明原引用与typed目录实体之间的精确关系。
     * 不允许把单独数字、当前最新SKU或调用者提供的SKU当作成功解析结果。
     */
    record Catalog(String tenantId,String benefitReference,String skuReference,String benefitId,
            long benefitVersion,String skuId,long skuVersion,String benefitType) { }

    /** 水位与摘要绑定本次已验证声明，不能将其他主体/规则的历史证明拼接使用。 */
    record Proof(Release release,Catalog catalog,String stableClaimsDigest,Instant issuedAt,Instant expiresAt) {
        @Override public String toString(){return "ReferralAwardProof[redacted]";}
    }
}
