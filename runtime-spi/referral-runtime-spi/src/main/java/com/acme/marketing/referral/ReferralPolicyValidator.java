package com.acme.marketing.referral;

import java.time.DateTimeException;
import java.util.HashSet;
import java.util.Objects;

/** 类型化计划校验；图拓扑和 SKU 真实有效性需由后续编译/控制面验证。 */
public final class ReferralPolicyValidator {
    private ReferralPolicyValidator() { }

    /** 无副作用地校验计划；不合法时抛出 IllegalArgumentException，不自动修正运营配置。 */
    public static void validate(ReferralPlan plan) {
        Objects.requireNonNull(plan, "plan");
        require(plan.startsAt() != null && plan.endsAt() != null && plan.settlementEndsAt() != null,
                "活动时间不能为空");
        require(plan.startsAt().isBefore(plan.endsAt()) && !plan.settlementEndsAt().isBefore(plan.endsAt()),
                "活动时间与结算截止顺序错误");
        require(plan.goalType() != null, "资格模式不能为空");
        require(plan.qualificationWindowSeconds() > 0 && plan.observationSeconds() >= 0
                && plan.lateArrivalGraceSeconds() >= 0, "时间窗口非法");
        require(plan.minNetAmountMinor() >= 0 && plan.currency() != null
                && plan.currency().matches("[A-Z]{3}"), "金额或币种非法");
        try {
            plan.settlementEndsAt().plusSeconds(plan.lateArrivalGraceSeconds());
            plan.endsAt().plusSeconds(plan.qualificationWindowSeconds());
            plan.settlementEndsAt().plusSeconds(plan.observationSeconds());
        } catch (DateTimeException | ArithmeticException e) {
            throw new IllegalArgumentException("时间窗口溢出", e);
        }
        require(!plan.rewards().isEmpty(), "至少配置一个奖励");
        var ids = new HashSet<String>();
        for (var rule : plan.rewards()) {
            require(rule != null && nonBlank(rule.ruleId()) && ids.add(rule.ruleId()), "奖励 ID 为空或重复");
            require(rule.role() != null && rule.mode() != null, "奖励角色或模式不能为空");
            require(rule.threshold() > 0 && rule.quantity() == 1, "门槛必须为正，首期数量固定 1");
            require(rule.mode() != ReferralRewardRule.Mode.PER_RELATION || rule.threshold() == 1,
                    "逐人奖励门槛固定 1");
            require(rule.mode() != ReferralRewardRule.Mode.MILESTONE || rule.role() == ReferralRewardRule.Role.INVITER,
                    "人数阶梯仅属于邀请人");
            require(nonBlank(rule.benefitDefinitionVersion()) && nonBlank(rule.skuVersion()), "奖励必须冻结版本");
            require(rule.perSubjectLimit() > 0 && rule.campaignLimit() >= rule.perSubjectLimit(), "额度声明非法");
        }
    }

    private static boolean nonBlank(String value) { return value != null && !value.isBlank(); }
    private static void require(boolean valid, String message) {
        if (!valid) throw new IllegalArgumentException(message);
    }
}
