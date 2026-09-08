package com.acme.marketing.referral.application;
import com.acme.marketing.platform.identity.TenantScope;
import java.time.Instant;
import java.util.Objects;
/** 事务外验证专用BFF断言并通过权威映射/KMS生成HMAC与密文；超时或未知来源必须拒绝。 */
public interface TrustedReferralSubjectPort {
    /** 断言不得出现在数据库/日志；实现者验证租户、audience、actor、时效和主体映射，禁止从普通body构造结果。 */
    Subject resolve(TenantScope scope,String assertion,RequestBinding binding);
    /** 断言绑定完整业务上下文；当前transport为INTERNAL，未来HTTP必须传实际method/path并签收规范JSON摘要。 */
    record RequestBinding(String tenantId,String campaignId,String organizationId,String shopId,String operation,
            String idempotencyKey,String bodyDigest,String method,String path) {
        /** 内部绑定仍执行机器键校验，摘要不能取自调用方未经核验的声明。 */
        public RequestBinding {
            ReferralInputs.key(tenantId,64); ReferralInputs.key(campaignId,64); ReferralInputs.key(organizationId,64); ReferralInputs.key(shopId,64);
            ReferralInputs.key(operation,128); ReferralInputs.key(idempotencyKey,128); ReferralInputs.digest(bodyDigest);
            Objects.requireNonNull(method); Objects.requireNonNull(path);
        }
    }
    /** 主体仅以租户绑定的版本HMAC和独立密钥密文跨边界；索引key轮换须双索引回填后另行发布。 */
    record Subject(String tenantId,String subjectKey,long keyVersion,byte[] cipher,String encryptionKeyId,RequestBinding binding,Instant issuedAt,Instant expiresAt) {
        /** 防御性复制密文，拒绝原文主体和空凭证进入正式写模型。 */
        public Subject {
            ReferralInputs.key(tenantId,64); ReferralInputs.digest(subjectKey); ReferralInputs.key(encryptionKeyId,128);
            if(keyVersion<=0 || cipher==null || cipher.length<16 || cipher.length>2048) throw new IllegalArgumentException("invalid protected subject");
            Objects.requireNonNull(binding); Objects.requireNonNull(issuedAt); Objects.requireNonNull(expiresAt);
            if(!issuedAt.isBefore(expiresAt) || expiresAt.isAfter(issuedAt.plusSeconds(60))) throw new IllegalArgumentException("invalid subject validity");
            cipher=cipher.clone();
        }
        /** 调用方不能修改受信结果保存的密文。 */
        @Override public byte[] cipher() { return cipher.clone(); }
        /** 默认诊断不暴露主体HMAC、密文或密钥引用。 */
        @Override public String toString() { return "ProtectedReferralSubject[redacted]"; }
    }
}
