package com.acme.marketing.referral.application.release;

import java.time.Instant;

/** 已验证制品的本地永久存储；VERIFIED 不能证明闭包就绪或发布已激活。 */
public interface ReferralReleaseRepository {
    /** 按租户及部署槽位保留代次；唯一键竞争后锁读同一事实，不覆盖原内容。 */
    Stored reserveAndLock(Stored proposed);

    /** 写入对象保留签名原文与字节，供恢复时重新验签；时间由应用可信时钟生成。 */
    record Stored(String tenantId, String environment, String cell, String namespace, long generation,
            String manifestId, String releaseKeyId, String manifestJson, byte[] payload, Instant verifiedAt) {
        public Stored { payload = payload.clone(); }
        @Override public byte[] payload() { return payload.clone(); }
    }
}
