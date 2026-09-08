package com.acme.marketing.referral.application;
import java.time.Instant;
import java.util.Objects;
/** 独立KMS/AEAD保护边界，必须事务外调用；默认不可用，密钥不得与主体HMAC共用。 */
public interface ReferralInviteProtectionPort {
    /** 必须认证Context为AAD，返回密文不得包含token原文；失败不得记录入参。 */
    Protected encrypt(Context context,String token);
    /** 按保存keyId解密并验证全部AAD；失败不向调用方泄露密钥/密文或token。 */
    String decrypt(Context context,Protected cipher);
    /** 固定所有权、命令内容及两种期限，阻止跨租户/请求搬用密文。 */
    record Context(String tenantId,String subjectKey,String idempotencyKey,String requestHash,String tokenId,String tokenHash,Instant tokenExpiresAt,Instant replayUntil) {}
    /** 密文专用容器，不参与默认诊断显示。 */
    record Protected(String keyId,byte[] bytes) {
        /** 防御性复制防止加密完成后被调用方改写。 */
        public Protected { ReferralInputs.key(keyId,128); Objects.requireNonNull(bytes); if(bytes.length<16 || bytes.length>4096) throw new IllegalArgumentException("invalid protected token"); bytes=bytes.clone(); }
        /** 返回副本，维持密文不可变。 */
        @Override public byte[] bytes() { return bytes.clone(); }
        /** 不暴露密文或密钥引用。 */
        @Override public String toString() { return "ProtectedInviteToken[redacted]"; }
    }
}
