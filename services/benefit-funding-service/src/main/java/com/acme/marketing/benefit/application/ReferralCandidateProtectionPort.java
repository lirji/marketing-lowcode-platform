package com.acme.marketing.benefit.application;

/** 固定来源的候选快照AEAD边界；真实KMS/密钥未配置时拒绝，不能把body指定的key当信任来源。 */
public interface ReferralCandidateProtectionPort {
    /** 调用方保证在事务外；实现须把完整canonical AAD做认证加密附加数据。 */
    Encrypted encrypt(byte[] aad,byte[] plaintext);
    /** 解密必须验证原AAD，不能仅检查数据库中可复制的摘要字段。 */
    byte[] decrypt(byte[] aad,Encrypted encrypted);
    /** 密文独立复制，不提供默认含明文的日志输出。 */
    record Encrypted(String keyId,byte[] ciphertext) {
        public Encrypted {
            if(keyId==null || keyId.isBlank() || keyId.length()>128 || ciphertext==null || ciphertext.length<16 || ciphertext.length>131072)
                throw new IllegalArgumentException("invalid protected referral candidate");
            ciphertext=ciphertext.clone();
        }
        @Override public byte[] ciphertext(){return ciphertext.clone();}
        @Override public String toString(){return "EncryptedReferralCandidate[redacted]";}
    }
}
