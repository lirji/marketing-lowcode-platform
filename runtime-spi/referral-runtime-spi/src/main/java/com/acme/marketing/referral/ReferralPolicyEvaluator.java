package com.acme.marketing.referral;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 无 I/O、无时钟读取的确定性规则计算器。调用方须先验证来源、租户/主体/订单关联及证据修订。
 * 输出仅代表规则候选，不能作为发奖授权；事务中的持久幂等、配额、风险和有效许可仍必须检查。
 */
public final class ReferralPolicyEvaluator {
    private ReferralPolicyEvaluator() { }

    /** 三值权威事实；UNKNOWN 不得转成新客或首单通过。 */
    public enum Fact { YES, NO, UNKNOWN }
    /** 资格结果不是奖励履约状态；PENDING 和 REVIEW 均不能产生新逐人奖励。 */
    public enum State { ELIGIBLE, INELIGIBLE, PENDING, REVIEW }
    /** 稳定原因码用于仿真和审计，不携带用户或订单原始信息。 */
    public enum Reason {
        QUALIFIED, BIND_OUTSIDE_WINDOW, EVIDENCE_UNAVAILABLE, NOT_NEW_CUSTOMER, NOT_FIRST_ORDER,
        FACT_OUTSIDE_WINDOW, FUTURE_EVIDENCE, CURRENCY_MISMATCH, INVALID_AMOUNTS,
        NET_BELOW_THRESHOLD, OBSERVATION_PENDING, OBSERVATION_OUTSIDE_SETTLEMENT, LATE_REVIEW
    }
    /** 可解释结果；dueAt 仅在观察期尚未成熟时存在，由服务持久化调度。 */
    public record Decision(State state, Reason reason, Instant dueAt) { }
    /**
     * 由适配器验证后的累计快照。verified=false 表示权威来源/关联尚未确认。
     * qualifyingFactAt 是权威注册或结算时间；qualifyingFactReceivedAt 是首次可信接收该事实的时间，
     * 不能使用重试时间或后续退款到达时间；新客认定口径为绑定时会员权威结果；注册时间须在活动开始至绑定时刻内。
     * settledAmountMinor 减 cumulativeRefundMinor 得净额，退款先到而缺订单时必须 verified=false。
     */
    public record Evidence(boolean verified, Fact newCustomerAtBind, Fact firstValidOrder,
            Instant qualifyingFactAt, Instant qualifyingFactReceivedAt,
            long settledAmountMinor, long cumulativeRefundMinor, String currency) { }
    /**
     * 返回候选的结构化业务键；持久层必须再补 tenant/campaign/beneficiary/role 唯一作用域。
     * milestoneKey 对逐人奖为 relationId，对阶梯为人数门槛；不拼接字符串，避免分隔符碰撞。
     */
    public record RewardCandidate(ReferralRewardRule rule, String milestoneKey) { }

    /**
     * 按冻结计划计算单关系资格；now 必须由调用方注入可信时间，持锁后应重新调用。
     * 所有输入不完整均失败关闭；非法计划或缺少 boundAt/now 抛出参数异常，无任何外部副作用。
     */
    public static Decision evaluate(ReferralPlan plan, Instant boundAt, Evidence evidence, Instant now) {
        ReferralPolicyValidator.validate(plan);
        Objects.requireNonNull(boundAt, "boundAt");
        Objects.requireNonNull(now, "now");
        if (boundAt.isBefore(plan.startsAt()) || !boundAt.isBefore(plan.endsAt()))
            return decision(State.INELIGIBLE, Reason.BIND_OUTSIDE_WINDOW);
        if (boundAt.isAfter(now)) return decision(State.PENDING, Reason.FUTURE_EVIDENCE);
        if (evidence == null || !evidence.verified() || evidence.newCustomerAtBind() == null
                || evidence.newCustomerAtBind() == Fact.UNKNOWN)
            return decision(State.PENDING, Reason.EVIDENCE_UNAVAILABLE);
        if (evidence.newCustomerAtBind() == Fact.NO)
            return decision(State.INELIGIBLE, Reason.NOT_NEW_CUSTOMER);
        boolean order = plan.goalType() == ReferralPlan.GoalType.FIRST_ORDER_SETTLED;
        if (order && (evidence.firstValidOrder() == null || evidence.firstValidOrder() == Fact.UNKNOWN))
            return decision(State.PENDING, Reason.EVIDENCE_UNAVAILABLE);
        if (order && evidence.firstValidOrder() == Fact.NO)
            return decision(State.INELIGIBLE, Reason.NOT_FIRST_ORDER);
        if (evidence.qualifyingFactAt() == null || evidence.qualifyingFactReceivedAt() == null)
            return decision(State.PENDING, Reason.EVIDENCE_UNAVAILABLE);
        Instant deadline = earlier(boundAt.plusSeconds(plan.qualificationWindowSeconds()), plan.settlementEndsAt());
        // 注册先于登录/绑定是正常路径；是否新客由绑定时的会员权威口径决定。
        // 订单仍须绑定后发生，不能把邀请前的历史首单归因给本活动。
        Instant factStart = order ? boundAt : plan.startsAt();
        if (evidence.qualifyingFactAt().isBefore(factStart)
                || (!order && evidence.qualifyingFactAt().isAfter(boundAt))
                || !evidence.qualifyingFactAt().isBefore(deadline))
            return decision(State.INELIGIBLE, Reason.FACT_OUTSIDE_WINDOW);
        if (evidence.qualifyingFactAt().isAfter(now) || evidence.qualifyingFactReceivedAt().isAfter(now)
                || evidence.qualifyingFactReceivedAt().isBefore(evidence.qualifyingFactAt()))
            return decision(State.PENDING, Reason.FUTURE_EVIDENCE);
        if (order) {
            if (!plan.currency().equals(evidence.currency()))
                return decision(State.PENDING, Reason.CURRENCY_MISMATCH);
            if (evidence.settledAmountMinor() < 0 || evidence.cumulativeRefundMinor() < 0
                    || evidence.cumulativeRefundMinor() > evidence.settledAmountMinor())
                return decision(State.PENDING, Reason.INVALID_AMOUNTS);
            // 先处理退款净额，不因观察期或晚到检查而掩盖已知资格失效。
            if (evidence.settledAmountMinor() - evidence.cumulativeRefundMinor() < plan.minNetAmountMinor())
                return decision(State.INELIGIBLE, Reason.NET_BELOW_THRESHOLD);
        }
        Instant matureAt = evidence.qualifyingFactAt().plusSeconds(plan.observationSeconds());
        if (!matureAt.isBefore(plan.settlementEndsAt()))
            return decision(State.INELIGIBLE, Reason.OBSERVATION_OUTSIDE_SETTLEMENT);
        // 用首次事实接收时间裁决宽限；同一证据在晚些时候重放不应改变原资格判断。
        if (!evidence.qualifyingFactReceivedAt().isBefore(deadline.plusSeconds(plan.lateArrivalGraceSeconds())))
            return decision(State.REVIEW, Reason.LATE_REVIEW);
        if (now.isBefore(matureAt)) return new Decision(State.PENDING, Reason.OBSERVATION_PENDING, matureAt);
        return decision(State.ELIGIBLE, Reason.QUALIFIED);
    }

    /**
     * 从锁定后的当前有效人数及本关系资格计算规则候选；阶梯累计追加，双边互不依赖履约。
     * 这是当前应有资格集合，重复调用可能返回同一候选；持久 reward/取消墓碑保证重达不重发。
     * 不能直接用集合差异取消全部奖励：逐人候选只覆盖传入的单个 relationId。
     */
    public static List<RewardCandidate> rewardCandidates(ReferralPlan plan, String relationId,
            boolean relationQualified, long validCount) {
        ReferralPolicyValidator.validate(plan);
        if (relationId == null || relationId.isBlank() || validCount < 0
                || (relationQualified && validCount == 0))
            throw new IllegalArgumentException("关系 ID 或锁定后的有效人数非法");
        var result = new ArrayList<RewardCandidate>();
        for (var rule : plan.rewards()) {
            if (rule.mode() == ReferralRewardRule.Mode.PER_RELATION && relationQualified)
                result.add(new RewardCandidate(rule, relationId));
            else if (rule.mode() == ReferralRewardRule.Mode.MILESTONE && validCount >= rule.threshold())
                result.add(new RewardCandidate(rule, Long.toString(rule.threshold())));
        }
        return List.copyOf(result);
    }

    private static Instant earlier(Instant a, Instant b) { return a.isBefore(b) ? a : b; }
    private static Decision decision(State state, Reason reason) { return new Decision(state, reason, null); }
}
