package com.acme.marketing.platform.identity;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.regex.Pattern;

/** 营销平台发号对象的稳定协议，不用于令牌、事件或领取凭证。 */
public final class BusinessNumber {
    public static final int MAX_ISSUE_ATTEMPTS = 4;
    private static final DateTimeFormatter BUSINESS_DATE = DateTimeFormatter.ofPattern("uuMMdd", Locale.ROOT)
        .withZone(ZoneId.of("Asia/Shanghai"));
    private static final long SUFFIX_SPACE = 10_000_000_000L;
    private static final SecureRandom RANDOM = new SecureRandom();

    private BusinessNumber() {}

    /** 只注册已实现且具有明确发号 owner 的业务编号。 */
    public enum Type {
        CAMPAIGN("CMP"), APPROVAL("APR"), FUNDING_APPLICATION("FAP"), LEDGER("LED"), RELEASE_BUNDLE("BND"), RELEASE_OPERATION("RLS"), RELEASE_SCHEDULE("SCH"), AUDIENCE_SNAPSHOT("SNP"), JOURNEY_ENROLLMENT("JIN");

        private final String prefix;
        private final Pattern pattern;

        Type(String code) {
            this.prefix = "MKT-" + code + "-";
            this.pattern = Pattern.compile(prefix + "[0-9]{6}-[0-9]{10}");
        }
    }

    /** 对象的创建时刻决定业务日期，最终唯一性由持久化约束裁决。 */
    public static String next(Type type, Instant createdAt) {
        return type.prefix + BUSINESS_DATE.format(createdAt) + "-"
            + String.format(Locale.ROOT, "%010d", RANDOM.nextLong(SUFFIX_SPACE));
    }

    /** 按编号族选择查询列，避免切换时对两套索引做模糊检索。 */
    public static boolean matches(Type type, String value) {
        return value != null && type.pattern.matcher(value).matches();
    }
}
