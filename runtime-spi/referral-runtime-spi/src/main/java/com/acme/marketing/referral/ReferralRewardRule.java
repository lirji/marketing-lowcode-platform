package com.acme.marketing.referral;

/**
 * 一个规则对应一个受益人和一个固定 SKU 单位；额度只是声明，实际占用必须由持久事务裁决。
 * ruleId 跨版本稳定，删除后不可复用为其他奖励；该历史约束由发布服务检查。
 */
public record ReferralRewardRule(String ruleId, Role role, Mode mode, long threshold,
        String benefitDefinitionVersion, String skuVersion, int quantity,
        long perSubjectLimit, long campaignLimit) {
    /** 双边分别产生候选，不要求另一方已履约。 */
    public enum Role { INVITER, INVITEE }
    /** 人数阶梯累计追加；逐人规则 threshold 固定为 1。 */
    public enum Mode { PER_RELATION, MILESTONE }
}
