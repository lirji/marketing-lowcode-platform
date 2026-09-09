package com.acme.marketing.referral.application.authorization;

import com.acme.marketing.platform.identity.TenantScope;
import com.acme.marketing.referral.domain.authorization.ReferralRewardAuthorization.Window;
import java.util.Objects;

/** 事务外验签、固定制品、受益人HMAC及最新风控/运行许可，未接通真实来源必须返回null。 */
public interface ReferralAuthorizationProofPort {
    /** 必须验证候选和风险断言绑定全部固定奖励声明；字符串相等本身不证明来源可信。 */
    Proof verify(TenantScope scope,Request request,Reward reward);
    /** 来自可信benefit机器的确认请求，候选/风险原文禁止写入日志或数据库。 */
    record Request(String rewardId,String sourceRequestId,String stableClaimsDigest,long qualificationRevision,String candidateToken,String riskAssertion){
        /** 幂等身份和受控文本有界；密码学与权限检查仍由可信适配器承担。 */
        public Request {
            if(rewardId==null || !rewardId.matches("[a-f0-9]{64}") || sourceRequestId==null || sourceRequestId.length()>128
                    || stableClaimsDigest==null || !stableClaimsDigest.matches("sha256:[a-f0-9]{64}") || qualificationRevision<=0
                    || candidateToken==null || candidateToken.isBlank() || candidateToken.length()>32768 || riskAssertion==null || riskAssertion.isBlank() || riskAssertion.length()>32768)throw new IllegalArgumentException("invalid authorization request");
        }
        @Override public String toString(){return "ReferralAuthorizationRequest[redacted]";}
    }
    /** 固定奖励声明供适配器逐字段校验，包含原密文上下文；不可把客户端自报受益人写入此记录。 */
    record Reward(String tenantId,String rewardId,String sourceRequestId,String campaignId,String organizationId,String shopId,String participantId,String relationId,
            String beneficiaryKey,long keyVersion,String role,String mode,long threshold,String ruleId,String ruleJson,String definitionId,long definitionVersion,long generation,
            String artifactId,String policyHash,String entitlementState,String authorizationState,String quotaState,long qualificationRevision,long progressRevision,long revision,long cancelRevision){
        @Override public String toString(){return "AuthorizationReward[redacted]";}
    }
    /** 三项已验证许可仍须锁后按原始纳秒复检；缺少任一项不得首次确认。 */
    record Proof(Request request,Reward reward,Window candidate,Window runtime,Window risk){
        /** 完整绑定而非几个裸版本号，防止并发刷新后使用原授权。 */
        public Proof{Objects.requireNonNull(request);Objects.requireNonNull(reward);Objects.requireNonNull(candidate);Objects.requireNonNull(runtime);Objects.requireNonNull(risk);}
        @Override public String toString(){return "ReferralAuthorizationProof[redacted]";}
    }
}
