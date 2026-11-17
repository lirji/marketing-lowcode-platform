package com.acme.marketing.contracts.release;

import com.acme.marketing.platform.crypto.CanonicalMapCodec;
import com.acme.marketing.platform.crypto.Digests;
import com.acme.marketing.platform.identity.TenantId;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;

/** 一次参与固定完整组合的持久事实；不代表当前资格或再次执行业务副作用的授权。 */
public record ReleaseBundleParticipation(String bindingId, TenantId tenantId, String campaignId,
        String environment, String cell, String participationId, String subjectHash, String directiveId,
        long activationSequence, String activationSignature, String bundleId, long bundleVersion,
        String contentHash, Instant admittedAt, String signatureKeyId, String signature) {
    private static final int MAX_IDENTIFIER_LENGTH = 64;
    private static final int MAX_ENVIRONMENT_LENGTH = 32;
    private static final int MAX_PARTICIPATION_LENGTH = 128;
    private static final int MAX_SUBJECT_LENGTH = 512;
    private static final int ED25519_SIGNATURE_BYTES = 64;
    private static final int ED25519_SIGNATURE_TEXT_LENGTH = 86;
    private static final String SUBJECT_PURPOSE = "marketing.activity-bundle-subject/v1";

    public ReleaseBundleParticipation {
        if (!identifier(bindingId) || tenantId == null || !identifier(campaignId)
                || !bounded(environment, MAX_ENVIRONMENT_LENGTH) || !identifier(cell)
                || !bounded(participationId, MAX_PARTICIPATION_LENGTH) || !digest(subjectHash)
                || !extendedIdentifier(directiveId) || activationSequence < 1 || !signature(activationSignature)
                || !identifier(bundleId) || bundleVersion < 1 || !digest(contentHash)
                || admittedAt == null || !extendedIdentifier(signatureKeyId)) throw invalid();
        signature = signature == null ? "" : signature;
        if (!signature.isEmpty() && !signature(signature)) throw invalid();
    }

    /** 规范长度编码避免拼接碰撞；摘要只减少跨边界明文传播，不是匿名化或身份映射。 */
    public static String subjectHash(TenantId tenant, String subjectToken) {
        if (tenant == null || !bounded(subjectToken, MAX_SUBJECT_LENGTH)) throw invalid();
        return Digests.sha256Hex(CanonicalMapCodec.encode(Map.of("purpose", SUBJECT_PURPOSE,
                "tenant", tenant.value(), "subjectToken", subjectToken)));
    }

    private static boolean digest(String value) { return value != null && value.matches("[a-f0-9]{64}"); }
    private static boolean identifier(String value) {
        return value != null && value.length() <= MAX_IDENTIFIER_LENGTH && value.matches("[A-Za-z0-9_.:-]+");
    }
    private static boolean extendedIdentifier(String value) {
        return value != null && value.length() <= MAX_IDENTIFIER_LENGTH && value.matches("[A-Za-z0-9_.:@-]+");
    }
    private static boolean bounded(String value, int maximum) {
        return value != null && !value.isBlank() && value.length() <= maximum
                && value.chars().noneMatch(Character::isISOControl)
                // 非配对代理字符在UTF-8替换后会碰撞，不能作为签名主体或参与键。
                && java.nio.charset.StandardCharsets.UTF_8.newEncoder().canEncode(value);
    }
    private static boolean signature(String value) {
        if (value == null || value.length() != ED25519_SIGNATURE_TEXT_LENGTH) return false;
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(value);
            return bytes.length == ED25519_SIGNATURE_BYTES
                    && Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(value);
        } catch (IllegalArgumentException malformed) { return false; }
    }
    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException(ReleaseBundleProtocolError.PARTICIPATION_INVALID.message());
    }
}
