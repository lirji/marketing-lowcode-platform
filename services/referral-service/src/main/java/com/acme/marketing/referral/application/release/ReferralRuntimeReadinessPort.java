package com.acme.marketing.referral.application.release;

import com.acme.marketing.contracts.release.ReleaseManifest;
import java.time.Instant;

/** 仅受信适配器读取完整预热/闭包证明，不能由 HTTP 请求构造；当前无生产实现。 */
public interface ReferralRuntimeReadinessPort {
    /** 必须在事务外读取；不可用返回 null，不能拿 VERIFIED 收据替代。 */
    Proof current(String tenantId, ReleaseManifest manifest);
    /** 默认装配必须拒绝，测试替身不是实际 READY/ACK。 */
    static ReferralRuntimeReadinessPort unavailable() { return (tenant, manifest) -> null; }
    /** 有界且精确绑定签名清单；本地再次校验时间，不接受客户端更新时间。 */
    record Proof(String tenantId, String manifestId, long generation, String manifestSignature, Instant issuedAt, Instant expiresAt) { }
}
