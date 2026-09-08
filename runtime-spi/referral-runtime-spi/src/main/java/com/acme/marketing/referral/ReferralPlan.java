package com.acme.marketing.referral;

import java.time.Instant;
import java.util.List;

/**
 * 冻结后的纯规则计划；时间使用 UTC 半开区间，金额为最小货币单位。
 * 不提供运营默认值，不承担图解析、签名验证、主体映射或版本持久化。
 */
public record ReferralPlan(Instant startsAt, Instant endsAt, Instant settlementEndsAt,
        long qualificationWindowSeconds, long observationSeconds, long lateArrivalGraceSeconds,
        GoalType goalType, long minNetAmountMinor, String currency, List<ReferralRewardRule> rewards) {
    /** 防御性复制奖励列表，防止调用者在校验后改变冻结规则。 */
    public ReferralPlan {
        rewards = List.copyOf(rewards);
    }

    /** 首期只允许已审查的两种资格策略，未知字符串应在编译入口拒绝。 */
    public enum GoalType { REGISTERED_NEW_CUSTOMER, FIRST_ORDER_SETTLED }
}
