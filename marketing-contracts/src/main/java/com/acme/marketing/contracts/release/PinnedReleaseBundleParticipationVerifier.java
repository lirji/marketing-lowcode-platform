package com.acme.marketing.contracts.release;

import com.acme.marketing.platform.error.ConflictException;
import com.acme.marketing.platform.identity.TenantId;
import java.security.PublicKey;
import java.time.Instant;
import java.util.Map;

/** 验证固定版本的历史事实；密钥信任采用当前配置，业务资格/停用/终态仍须实时检查。 */
public final class PinnedReleaseBundleParticipationVerifier {
    private static final long MAX_CLOCK_SKEW_SECONDS = 60;
    private final Map<String, PublicKey> participationKeys;
    private final PinnedReleaseBundleActivationVerifier activationVerifier;

    /** 两类信任显式分离，持有Control密钥不能因此签发参与事实，反之亦然。 */
    public PinnedReleaseBundleParticipationVerifier(Map<String, PublicKey> participationKeys,
            PinnedReleaseBundleActivationVerifier activationVerifier) {
        this.participationKeys = Map.copyOf(participationKeys);
        this.activationVerifier = java.util.Objects.requireNonNull(activationVerifier);
    }

    /** 预期范围必须来自可信入口/本地配置，禁止直接复制待验凭证的字段当作预期值。 */
    public void requireValid(ExpectedContext expected, ReleaseBundleParticipation value,
            ReleaseBundleActivationDirective directive, Instant now) {
        if (expected == null || value == null || directive == null || now == null
                || !ReleaseBundleParticipationSigner.verify(participationKeys.get(value.signatureKeyId()), value)) throw invalid();
        var bundle = directive.bundle();
        if (!value.tenantId().equals(expected.tenantId()) || !value.campaignId().equals(expected.campaignId())
                || !value.environment().equals(expected.environment()) || !value.cell().equals(expected.cell())
                || !value.participationId().equals(expected.participationId())
                || !value.subjectHash().equals(ReleaseBundleParticipation.subjectHash(expected.tenantId(), expected.subjectToken()))
                || !value.tenantId().equals(bundle.tenantId()) || !value.campaignId().equals(bundle.campaignId())
                || !value.environment().equals(bundle.environment()) || !value.cell().equals(bundle.cell())
                || !value.directiveId().equals(directive.directiveId()) || value.activationSequence() != directive.activationSequence()
                || !value.activationSignature().equals(directive.signature()) || !value.bundleId().equals(bundle.bundleId())
                || value.bundleVersion() != bundle.version() || !value.contentHash().equals(bundle.contentHash())
                || value.admittedAt().isBefore(directive.authorizedAt()) || !value.admittedAt().isBefore(bundle.expiresAt())
                || value.admittedAt().isAfter(now.plusSeconds(MAX_CLOCK_SKEW_SECONDS))) throw invalid();
        // 老参与使用准入时刻核对期限，防止规则到期使恢复作业失效；当前信任集合仍能撤销任一原始签名钥匙。
        try { activationVerifier.requireValid(expected.tenantId().value(), directive, value.admittedAt()); }
        catch (ConflictException untrustedSource) { throw invalid(); }
    }

    private static ConflictException invalid() { return ReleaseBundleProtocolError.PARTICIPATION_INVALID.conflict(); }

    /** 仅用于接收校验的可信上下文；subjectToken不写入可传播的参与凭证。 */
    public record ExpectedContext(TenantId tenantId, String campaignId, String environment, String cell,
            String participationId, String subjectToken) {
        public ExpectedContext {
            if (tenantId == null || campaignId == null || environment == null || cell == null || participationId == null)
                throw new IllegalArgumentException(ReleaseBundleProtocolError.PARTICIPATION_INVALID.message());
            ReleaseBundleParticipation.subjectHash(tenantId, subjectToken);
        }
    }
}
