package com.acme.marketing.measurement.application;

import com.acme.marketing.platform.error.DomainException;

/** 仅收窄当前租户的投影查询；空缺参数与显式无效参数严格区分。 */
public final class MeasurementQueryFilter {
    private static final int MAX_CAMPAIGN_ID_LENGTH = 128;
    private static final String INVALID_CODE = "MEASUREMENT_CAMPAIGN_FILTER_INVALID";
    private static final String INVALID_MESSAGE = "campaignId must be nonblank, at most 128 characters, without surrounding whitespace or control characters";
    private MeasurementQueryFilter() { }

    /** 不对活动ID截断或静默去空格，以免查询到与操作者输入不同的活动。 */
    public static String campaignId(String value) {
        if (value == null) return "";
        if (value.isBlank() || value.length() > MAX_CAMPAIGN_ID_LENGTH || !value.equals(value.strip())
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new DomainException(INVALID_CODE, INVALID_MESSAGE);
        }
        return value;
    }
}
