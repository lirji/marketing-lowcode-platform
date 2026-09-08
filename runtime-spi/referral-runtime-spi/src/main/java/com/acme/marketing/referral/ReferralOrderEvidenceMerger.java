package com.acme.marketing.referral;

import java.time.Instant;
import java.util.Objects;
import static com.acme.marketing.referral.ReferralOrderEvidence.*;

/**
 * 累计订单证据的纯确定性合并器，不访问数据库/网络，不验签、不推断新客或交易结算政策。
 * 调用者必须将Inbox、历史查重、此状态与资格修订同事务提交；返回新状态不是持久幂等证明。
 */
public final class ReferralOrderEvidenceMerger {
    private ReferralOrderEvidenceMerger() { }

    /** 合并结果与资格结果分离：APPLIED不表示已达到活动规则。 */
    public enum Outcome { APPLIED, REPLAY, IGNORED_OLDER, REJECTED, CONFLICT }
    /** 稳定原因码供审计/待复核，不包含原始主体或完整证据。 */
    public enum Reason {
        READY_FOR_EVALUATOR, SETTLEMENT_PENDING, MAPPING_UNCONFIRMED, FIRST_ORDER_POLICY_PENDING,
        REFUND_PENDING, ORDER_NOT_SETTLED, SOURCE_UNVERIFIED, FUTURE_OR_INCONSISTENT_TIME,
        SCOPE_MISMATCH, INVALID_AMOUNTS, INVALID_CURRENCY, INVALID_STATE, REVISION_CONFLICT,
        REFUND_REGRESSION, POLICY_CHANGED, CURRENCY_CHANGED, SETTLEMENT_CHANGED, QUARANTINED, OLDER_REVISION,
        HISTORY_UNAVAILABLE, HISTORY_SCOPE_MISMATCH, HISTORY_STATE_MISMATCH
    }
    /** state可能为null，表示尚无可信状态；所有返回state（含pending/quarantine）均需由调用方持久提交。 */
    public record MergeResult(Outcome outcome, Reason reason, State state) { }

    /**
     * 用注入now及明确历史查重结果合并。相同revision在当前或历史中的不同内容均隔离；旧revision不补齐字段。
     * receivedAt/asOf/evidenceId是运输元数据，不改变已固定首次接收时间；未查历史不是NotSeen。
     */
    public static MergeResult merge(State current, Observation observation, HistoryLookup history, Instant now) {
        Objects.requireNonNull(observation, "observation"); Objects.requireNonNull(now, "now");
        Snapshot incoming = observation.snapshot();
        if (!observation.sourceVerified()) return rejected(Reason.SOURCE_UNVERIFIED, current);
        if (observation.receivedAt().isAfter(now) || observation.snapshotAt().isAfter(observation.receivedAt())
                || (incoming.settledAt() != null && incoming.settledAt().isAfter(observation.snapshotAt())))
            return rejected(Reason.FUTURE_OR_INCONSISTENT_TIME, current);
        if (current != null && !current.latest().scope().equals(incoming.scope()))
            return rejected(Reason.SCOPE_MISMATCH, current);
        if (current != null && current.quarantined()) return new MergeResult(Outcome.CONFLICT, Reason.QUARANTINED, current);
        if (current != null && incoming.revision() == current.latest().revision() && !incoming.equals(current.latest()))
            return conflict(Reason.REVISION_CONFLICT, current);
        if (history == null || history instanceof Unavailable) return pending(Reason.HISTORY_UNAVAILABLE, current, incoming.revision());
        if (history instanceof Seen seen) {
            if (!seen.snapshot().scope().equals(incoming.scope()) || seen.snapshot().revision() != incoming.revision())
                return pending(Reason.HISTORY_SCOPE_MISMATCH, current, incoming.revision());
            if (!seen.snapshot().equals(incoming)) return conflict(Reason.REVISION_CONFLICT, current);
            // 历史存在但聚合缺失/落后时必须先恢复原锚点，不能用本次重试时间伪造首接收。
            if (current == null || current.latest().revision() < incoming.revision())
                return pending(Reason.HISTORY_STATE_MISMATCH, current, incoming.revision());
        } else if (current != null && current.latest().revision() == incoming.revision()) {
            return pending(Reason.HISTORY_STATE_MISMATCH, current, incoming.revision());
        }
        // 同revision即使是非法金额变化也属于已验证来源自相矛盾，不能让旧READY状态继续发奖。
        if (current != null && incoming.revision() == current.latest().revision()) {
            if (!incoming.equals(current.latest())) return conflict(Reason.REVISION_CONFLICT, current);
            State replay = clearPendingThrough(current, incoming.revision());
            return new MergeResult(Outcome.REPLAY, readiness(replay), replay);
        }
        if (!validAmounts(incoming)) return invalidTrusted(Reason.INVALID_AMOUNTS, current, incoming.revision());
        if (incoming.currency() == null || !incoming.currency().matches("[A-Z]{3}"))
            return invalidTrusted(Reason.INVALID_CURRENCY, current, incoming.revision());
        if (incoming.orderState() == OrderState.REFUNDED && incoming.cumulativeRefundMinor() != incoming.grossEligibleMinor())
            return invalidTrusted(Reason.INVALID_STATE, current, incoming.revision());
        if (current != null) {
            Snapshot previous = current.latest();
            if (incoming.revision() < previous.revision()) {
                if (incoming.cumulativeRefundMinor() > previous.cumulativeRefundMinor()) return conflict(Reason.REFUND_REGRESSION, current);
                return new MergeResult(history instanceof Seen ? Outcome.REPLAY : Outcome.IGNORED_OLDER,
                        Reason.OLDER_REVISION, clearPendingThrough(current, incoming.revision()));
            }
            if (incoming.cumulativeRefundMinor() < previous.cumulativeRefundMinor()) return conflict(Reason.REFUND_REGRESSION, current);
            if (!incoming.currency().equals(previous.currency())) return conflict(Reason.CURRENCY_CHANGED, current);
            if (previous.firstOrderPolicyVersion() != null && !previous.firstOrderPolicyVersion().isBlank()
                    && !previous.firstOrderPolicyVersion().equals(incoming.firstOrderPolicyVersion()))
                return conflict(Reason.POLICY_CHANGED, current);
            if (current.settledAtAnchor() != null && incoming.settledAt() != null
                    && !current.settledAtAnchor().equals(incoming.settledAt())) return conflict(Reason.SETTLEMENT_CHANGED, current);
        }
        Instant firstReceived = current == null ? observation.receivedAt() : current.firstReceivedAt();
        Instant settledAt = current == null ? null : current.settledAtAnchor();
        Instant settlementReceived = current == null ? null : current.firstSettlementReceivedAt();
        if (settledAt == null && incoming.mappingConfirmed() && incoming.settledAt() != null
                && (incoming.orderState() == OrderState.SETTLED || incoming.orderState() == OrderState.REFUNDED)) {
            settledAt = incoming.settledAt(); settlementReceived = observation.receivedAt();
        }
        long pendingRevision = current == null || incoming.revision() >= current.historyPendingRevision() ? 0 : current.historyPendingRevision();
        State next = new State(incoming, firstReceived, settledAt, settlementReceived, false, pendingRevision);
        return new MergeResult(Outcome.APPLIED, readiness(next), next);
    }

    /**
     * 转为规则输入；newCustomer始终UNKNOWN，会员结果必须在外部另行做范围/政策关联。
     * 在途退款、查重pending、隔离、未确认映射均verified=false，不用pendingRefund重新定义成功退款金额。
     */
    public static ReferralPolicyEvaluator.Evidence toEvaluatorEvidence(State state) {
        if (state == null) return new ReferralPolicyEvaluator.Evidence(false, ReferralPolicyEvaluator.Fact.UNKNOWN,
                ReferralPolicyEvaluator.Fact.UNKNOWN, null, null, 0, 0, null);
        var snapshot = state.latest();
        return new ReferralPolicyEvaluator.Evidence(readiness(state) == Reason.READY_FOR_EVALUATOR,
                ReferralPolicyEvaluator.Fact.UNKNOWN, snapshot.firstEligibleOrder(), snapshot.settledAt(),
                state.firstSettlementReceivedAt(), snapshot.grossEligibleMinor(), snapshot.cumulativeRefundMinor(), snapshot.currency());
    }

    /** 返回证据完整性原因，不等同于活动资格或发奖授权。 */
    public static Reason readiness(State state) {
        if (state == null) return Reason.SETTLEMENT_PENDING;
        if (state.quarantined()) return Reason.QUARANTINED;
        if (state.historyPendingRevision() > 0) return Reason.HISTORY_UNAVAILABLE;
        var snapshot = state.latest();
        if (!validAmounts(snapshot)) return Reason.INVALID_AMOUNTS;
        if (snapshot.currency() == null || !snapshot.currency().matches("[A-Z]{3}")) return Reason.INVALID_CURRENCY;
        if (!snapshot.mappingConfirmed()) return Reason.MAPPING_UNCONFIRMED;
        if (snapshot.settledAt() == null || state.firstSettlementReceivedAt() == null) return Reason.SETTLEMENT_PENDING;
        if (snapshot.firstOrderPolicyVersion() == null || snapshot.firstOrderPolicyVersion().isBlank()
                || snapshot.firstEligibleOrder() == ReferralPolicyEvaluator.Fact.UNKNOWN) return Reason.FIRST_ORDER_POLICY_PENDING;
        if (snapshot.orderState() != OrderState.SETTLED) return Reason.ORDER_NOT_SETTLED;
        if (snapshot.pendingRefundMinor() > 0) return Reason.REFUND_PENDING;
        return Reason.READY_FOR_EVALUATOR;
    }

    private static boolean validAmounts(Snapshot snapshot) {
        return snapshot.grossEligibleMinor() >= 0 && snapshot.cumulativeRefundMinor() >= 0 && snapshot.pendingRefundMinor() >= 0
                && snapshot.cumulativeRefundMinor() <= snapshot.grossEligibleMinor()
                && snapshot.pendingRefundMinor() <= snapshot.grossEligibleMinor() - snapshot.cumulativeRefundMinor()
                && snapshot.netEligibleMinor() == snapshot.grossEligibleMinor() - snapshot.cumulativeRefundMinor();
    }
    private static MergeResult rejected(Reason reason, State current) { return new MergeResult(Outcome.REJECTED, reason, current); }
    private static MergeResult invalidTrusted(Reason reason, State current, long revision) {
        return current != null && revision >= current.latest().revision() ? conflict(reason, current) : rejected(reason, current);
    }
    private static MergeResult pending(Reason reason, State current, long revision) {
        return rejected(reason, current == null ? null : new State(current.latest(), current.firstReceivedAt(), current.settledAtAnchor(),
                current.firstSettlementReceivedAt(), current.quarantined(), Math.max(current.historyPendingRevision(), revision)));
    }
    private static State clearPendingThrough(State state, long revision) {
        if (state.historyPendingRevision() == 0 || revision < state.historyPendingRevision()) return state;
        return new State(state.latest(), state.firstReceivedAt(), state.settledAtAnchor(), state.firstSettlementReceivedAt(), state.quarantined(), 0);
    }
    private static MergeResult conflict(Reason reason, State current) {
        return new MergeResult(Outcome.CONFLICT, reason, current == null ? null : new State(current.latest(), current.firstReceivedAt(),
                current.settledAtAnchor(), current.firstSettlementReceivedAt(), true, current.historyPendingRevision()));
    }
}
