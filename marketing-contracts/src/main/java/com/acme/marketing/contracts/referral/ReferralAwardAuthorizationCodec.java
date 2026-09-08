package com.acme.marketing.contracts.referral;

import com.acme.marketing.platform.crypto.CanonicalMapCodec;
import com.acme.marketing.platform.crypto.SignedTokenCodec;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/** 内部Ed25519授权格式；固定受众及规范声明，独立于浏览器BFF断言和订单RS256令牌。 */
public final class ReferralAwardAuthorizationCodec {
    public static final String AUDIENCE = "marketing-benefit-referral-award";
    private static final String FORMAT = "marketing-referral-award/1";
    private static final int MAX_TOKEN_LENGTH = 16_384;
    private ReferralAwardAuthorizationCodec() { }

    /** 签发前校验专用受众；私钥与可信签发策略由外层KMS适配器提供。 */
    public static String encode(String keyId, PrivateKey key, ReferralAwardAuthorizationClaims claims) {
        if (!AUDIENCE.equals(claims.audience())) throw invalid();
        return SignedTokenCodec.encode(keyId, key, fields(claims, true));
    }

    /** keyResolver只解析可信固定来源；调用者必须在业务事务外执行本方法。 */
    public static Verified verify(String token, Function<String, PublicKey> keyResolver,
            Expected expected, Instant now) {
        try {
            if (token == null || token.length() > MAX_TOKEN_LENGTH || !token.matches("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+")) throw invalid();
            var verified = SignedTokenCodec.decodeAndVerify(token, keyResolver);
            var c = fromFields(verified.claims());
            // 严格重编码同时拒绝未知字段、非规范数值和非法UTF-8替换，不依赖宽松Map解码。
            byte[] canonical = CanonicalMapCodec.encode(fields(c, true));
            if (!Arrays.equals(canonical, Base64.getUrlDecoder().decode(token.split("\\.")[1]))) throw invalid();
            if (!c.issuer().equals(expected.issuer()) || !c.audience().equals(AUDIENCE)
                    || !c.tenantId().equals(expected.tenantId())
                    || !c.organizationId().equals(expected.organizationId()) || !c.shopId().equals(expected.shopId())) throw invalid();
            verifyTime(c, expected.maxLifetime(), now);
            return new Verified(verified.keyId(), c, stableDigest(c));
        } catch (RuntimeException failure) {
            // 不把签名、主体、keyResolver内部地址或解码内容放入错误文本/异常链。
            throw invalid();
        }
    }

    /** 事务拿锁之后再次执行纯时间判断；窗口半开，过期签名不能获得新的受理。 */
    public static void verifyTime(ReferralAwardAuthorizationClaims c, Duration maxLifetime, Instant now) {
        if (now == null || maxLifetime == null || maxLifetime.isNegative() || maxLifetime.isZero()
                || c.issuedAt().isAfter(now) || !c.expiresAt().isAfter(now)
                || Duration.between(c.issuedAt(), c.expiresAt()).compareTo(maxLifetime) > 0) throw invalid();
    }

    /** 摘要包含全部稳定声明，排除签名key/time，供持久幂等比较；不能替代资格有效性判断。 */
    public static String stableDigest(ReferralAwardAuthorizationClaims c) {
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(CanonicalMapCodec.encode(fields(c, false))));
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    /** 可信调用侧提供固定issuer和完整Scope；最大有效期没有生产默认值。 */
    public record Expected(String issuer, String tenantId, String organizationId, String shopId, Duration maxLifetime) {
        public Expected {
            ReferralAwardAuthorizationClaims.text(issuer, "issuer", 256);
            ReferralAwardAuthorizationClaims.text(tenantId, "tenantId", 64);
            ReferralAwardAuthorizationClaims.text(organizationId, "organizationId", 128);
            ReferralAwardAuthorizationClaims.text(shopId, "shopId", 128);
            if (maxLifetime == null || maxLifetime.isNegative() || maxLifetime.isZero()) throw invalid();
        }
    }
    /** 返回的keyId仅供证据审计；原成功重放应依永久账本，不强制再验已退休旧钥。 */
    public record Verified(String keyId, ReferralAwardAuthorizationClaims claims, String stableDigest) {
        @Override public String toString() { return "VerifiedReferralAuthorization[redacted]"; }
    }

    private static Map<String, String> fields(ReferralAwardAuthorizationClaims c, boolean time) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("format", FORMAT); m.put("issuer", c.issuer()); m.put("audience", c.audience());
        m.put("tenantId", c.tenantId()); m.put("organizationId", c.organizationId()); m.put("shopId", c.shopId());
        m.put("rewardId", c.rewardId()); m.put("sourceRequestId", c.sourceRequestId()); m.put("campaignId", c.campaignId());
        m.put("definitionId", c.definitionId()); m.put("definitionVersion", Long.toString(c.definitionVersion()));
        m.put("generation", Long.toString(c.generation())); m.put("artifactId", c.artifactId()); m.put("artifactHash", c.artifactHash());
        m.put("participantId", c.participantId()); m.put("relationId", c.relationId() == null ? "" : c.relationId());
        m.put("milestone", c.milestone() == null ? "" : Long.toString(c.milestone()));
        m.put("beneficiarySubject", c.beneficiarySubject()); m.put("role", c.role().name()); m.put("ruleId", c.ruleId());
        m.put("quantity", Integer.toString(c.quantity())); m.put("qualificationRevision", Long.toString(c.qualificationRevision()));
        if (time) { m.put("issuedAt", c.issuedAt().toString()); m.put("expiresAt", c.expiresAt().toString()); }
        return m;
    }

    private static ReferralAwardAuthorizationClaims fromFields(Map<String, String> m) {
        if (!FORMAT.equals(m.get("format"))) throw invalid();
        String relation = required(m,"relationId"), milestone = required(m,"milestone");
        return new ReferralAwardAuthorizationClaims(required(m,"issuer"), required(m,"audience"), required(m,"tenantId"),
                required(m,"organizationId"), required(m,"shopId"), required(m,"rewardId"), required(m,"sourceRequestId"),
                required(m,"campaignId"), required(m,"definitionId"), number(m,"definitionVersion"), number(m,"generation"),
                required(m,"artifactId"), required(m,"artifactHash"), required(m,"participantId"),
                relation.isEmpty() ? null : relation, milestone.isEmpty() ? null : Long.valueOf(milestone),
                required(m,"beneficiarySubject"), ReferralAwardAuthorizationClaims.Role.valueOf(required(m,"role")),
                required(m,"ruleId"), Math.toIntExact(number(m,"quantity")), number(m,"qualificationRevision"),
                Instant.parse(required(m,"issuedAt")), Instant.parse(required(m,"expiresAt")));
    }
    private static String required(Map<String, String> m, String k) { return Objects.requireNonNull(m.get(k)); }
    private static long number(Map<String, String> m, String k) { return Long.parseLong(required(m,k)); }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("invalid referral award authorization"); }
}
