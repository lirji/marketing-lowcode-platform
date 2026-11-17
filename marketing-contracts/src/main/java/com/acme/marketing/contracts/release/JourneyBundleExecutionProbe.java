package com.acme.marketing.contracts.release;

import com.acme.marketing.platform.identity.TenantId;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

/** Control签名的实际Flink执行探针；只引用精确授权，不创建任何业务参与或执行效果。 */
public record JourneyBundleExecutionProbe(String probeId, TenantId tenantId, String campaignId,
        String environment, String cell, String namespace, String memberManifestId,
        long activationSequence, String activationSignature, String targetJobId, int targetParallelism,
        String participationKeyId, Instant issuedAt, Instant expiresAt, String signatureKeyId, String signature) {
    public static final int MAX_PARALLELISM = 128;
    private static final int MAX_IDENTIFIER_LENGTH = 64;
    private static final int MAX_ENVIRONMENT_LENGTH = 32;
    private static final int MAX_NAMESPACE_LENGTH = 128;
    private static final Duration MAX_VALIDITY = Duration.ofMinutes(5);
    private static final int SIGNATURE_BYTES = 64;
    private static final int SIGNATURE_TEXT_LENGTH = 86;

    public JourneyBundleExecutionProbe {
        if (!identifier(probeId) || tenantId == null || !identifier(campaignId) || !bounded(environment, MAX_ENVIRONMENT_LENGTH)
                || !identifier(cell) || !bounded(namespace, MAX_NAMESPACE_LENGTH) || !identifier(memberManifestId)
                || activationSequence < 1 || !signed(activationSignature) || !jobId(targetJobId)
                || targetParallelism < 1 || targetParallelism > MAX_PARALLELISM || !extendedIdentifier(participationKeyId)
                || issuedAt == null || expiresAt == null || !expiresAt.isAfter(issuedAt)
                || Duration.between(issuedAt, expiresAt).compareTo(MAX_VALIDITY) > 0 || !extendedIdentifier(signatureKeyId)) throw invalid();
        signature = signature == null ? "" : signature;
        if (!signature.isEmpty() && !signed(signature)) throw invalid();
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException(ReleaseBundleProtocolError.EXECUTION_PROBE_INVALID.message()); }
    private static boolean identifier(String value) { return value != null && value.length() <= MAX_IDENTIFIER_LENGTH && value.matches("[A-Za-z0-9_.:-]+"); }
    private static boolean extendedIdentifier(String value) { return value != null && value.length() <= MAX_IDENTIFIER_LENGTH && value.matches("[A-Za-z0-9_.:@-]+"); }
    private static boolean bounded(String value, int maximum) {
        return value != null && !value.isBlank() && value.length() <= maximum && value.chars().noneMatch(Character::isISOControl)
                && StandardCharsets.UTF_8.newEncoder().canEncode(value);
    }
    static boolean jobId(String value) { return value != null && value.matches("[a-f0-9]{32}"); }
    static boolean buildDigest(String value) { return value != null && value.matches("sha256:[a-f0-9]{64}"); }
    static boolean keyId(String value) { return extendedIdentifier(value); }
    static boolean signed(String value) {
        if (value == null || value.length() != SIGNATURE_TEXT_LENGTH) return false;
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(value);
            return bytes.length == SIGNATURE_BYTES && Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(value);
        } catch (IllegalArgumentException malformed) { return false; }
    }
}
