package com.acme.marketing.referral.application.fulfillment;

import com.acme.marketing.platform.identity.TenantScope;
import com.acme.marketing.referral.application.authorization.ReferralAuthorizationProofPort.Reward;
import java.time.Instant;
import java.util.Objects;

/** 真实渠道终态归一化和验签边界；必须核租户、固定受益人、SKU版本及稳定来源号，默认不可用。 */
public interface ReferralFulfillmentProofPort {
    /** 事务外认证完整报文，不能把HTTP202或查询404映射成成功/可靠未发放。 */
    Proof verify(TenantScope scope,Envelope envelope,Reward reward);
    /** 内部信封无可信布尔值；来源和终态由适配器验证，原载荷不落普通日志。 */
    record Envelope(String rewardId,String eventId,String assertion,String payload){
        /** 限制报文内存和键范围，防止无界解析。 */
        public Envelope{if(rewardId==null || !rewardId.matches("[a-f0-9]{64}") || eventId==null || !eventId.matches("[A-Za-z0-9._:-]{1,128}") || assertion==null || assertion.isBlank() || assertion.length()>32768 || payload==null || payload.isBlank() || payload.length()>65536)throw new IllegalArgumentException("invalid fulfillment envelope");}
        @Override public String toString(){return "ReferralFulfillmentEnvelope[redacted]";}
    }
    /** 交付与补偿是独立事实；REVERSED要求快照同时提供此前成功的永久摘要。 */
    record Snapshot(String tenantId,String rewardId,String sourceRequestId,String providerId,long providerRevision,String deliveryState,String compensationState,
            String businessDigest,String successDigest,String terminalDigest){
        /** 稳定摘要由可信适配器规范化完整业务事实生成，不混接收/重试时间。 */
        public Snapshot{
            if(tenantId==null || tenantId.isBlank() || rewardId==null || sourceRequestId==null || providerId==null || !providerId.matches("[A-Za-z0-9._:-]{1,64}") || providerRevision<=0
                    || !java.util.Set.of("UNKNOWN","ACCEPTED","SUCCEEDED","FAILED_FINAL").contains(deliveryState)
                    || !java.util.Set.of("NONE","PENDING","CANCELLED","REVERSED","MANUAL_REVIEW").contains(compensationState)
                    || businessDigest==null || !businessDigest.matches("sha256:[a-f0-9]{64}"))throw new IllegalArgumentException("invalid fulfillment snapshot");
            if(deliveryState.equals("SUCCEEDED")?(successDigest==null || !successDigest.matches("sha256:[a-f0-9]{64}")):successDigest!=null)throw new IllegalArgumentException("success evidence required");
            if((deliveryState.equals("FAILED_FINAL") || compensationState.equals("REVERSED")) && (terminalDigest==null || !terminalDigest.matches("sha256:[a-f0-9]{64}")))throw new IllegalArgumentException("stable terminal evidence required");
            if(compensationState.equals("REVERSED") && !deliveryState.equals("SUCCEEDED"))throw new IllegalArgumentException("reversal requires original success");
            if(compensationState.equals("CANCELLED") && !deliveryState.equals("FAILED_FINAL"))throw new IllegalArgumentException("cancellation requires definitive non-issuance");
        }
        @Override public String toString(){return "ReferralFulfillmentSnapshot[redacted]";}
    }
    /** 短期可信许可绑定整个请求与固定奖励；最终提交前重查原始有效期。 */
    record Proof(Envelope envelope,Reward reward,Snapshot snapshot,Instant issuedAt,Instant expiresAt){
        /** 不凭record构造器证明来源可信，生产只允许受信适配器实例。 */
        public Proof{Objects.requireNonNull(envelope);Objects.requireNonNull(reward);Objects.requireNonNull(snapshot);Objects.requireNonNull(issuedAt);Objects.requireNonNull(expiresAt);}
        @Override public String toString(){return "ReferralFulfillmentProof[redacted]";}
    }
}
