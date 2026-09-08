package com.acme.marketing.referral;

import java.time.Instant;
import java.util.Objects;

/** 归一化累计证据合同；不解释交易paidAt/memberId，也不包含事件增量累加逻辑。 */
public final class ReferralOrderEvidence {
    private ReferralOrderEvidence() { }

    /** 完整隔离范围；组织/店铺不得在同订单修订中悄悄换值，主体使用权威映射后的精确标识。 */
    public record Scope(String tenantId, String sourceSystem, String canonicalSubject, String orderId,
            String organizationId, String shopId) {
        /** 所有范围字段必填；此处不归一化大小写或Unicode，避免跨主体串单。 */
        public Scope {
            text(tenantId); text(sourceSystem); text(canonicalSubject); text(orderId); text(organizationId); text(shopId);
            if (canonicalSubject.codePointCount(0, canonicalSubject.length()) > 256
                    || !java.nio.charset.StandardCharsets.UTF_8.newEncoder().canEncode(canonicalSubject))
                throw new IllegalArgumentException("规范主体必须为不超过256码点的合法Unicode，禁止自动改写");
        }
        /** 防止Snapshot/Observation/State默认record输出递归泄露规范主体及业务范围。 */
        @Override public String toString() { return "Scope[redacted]"; }
    }
    /** 冻结C03的归一状态；SETTLED不能由适配器未经确认地等同于支付成功。 */
    public enum OrderState { PENDING, SETTLED, CANCELLED, REFUNDED }

    /**
     * 同revision的稳定业务内容，所有金额为同币种最小单位且均为累计快照。
     * mappingConfirmed只表示调用侧已确认交易→本合同映射，不能替代sourceVerified或会员新客证据。
     * settledAt/policyVersion可为空表示尚不完整；同revision补充不同内容仍属于冲突，不能隐式合并。
     */
    public record Snapshot(Scope scope, long revision, String firstOrderPolicyVersion,
            ReferralPolicyEvaluator.Fact firstEligibleOrder, boolean mappingConfirmed, OrderState orderState,
            Instant settledAt, long grossEligibleMinor, long cumulativeRefundMinor, long pendingRefundMinor,
            long netEligibleMinor, String currency) {
        /** 基础身份必须可索引；金额/策略完整性由合并器返回可解释失败，不在构造时伪修正。 */
        public Snapshot {
            Objects.requireNonNull(scope, "scope");
            Objects.requireNonNull(firstEligibleOrder, "firstEligibleOrder");
            Objects.requireNonNull(orderState, "orderState");
            if (revision <= 0) throw new IllegalArgumentException("订单证据revision必须为正");
        }
    }

    /**
     * 传输/查询元数据不参与同revision业务内容比较，重试的时间/evidenceId变化不能制造冲突。
     * sourceVerified必须来自调用侧真实认证与来源绑定，不能采用客户端自报bool。
     */
    public record Observation(Snapshot snapshot, String evidenceId, Instant snapshotAt, Instant receivedAt,
            boolean sourceVerified) {
        /** 快照与时间必填；未来/逆序元数据由合并器拒绝且不能抢占首次接收时间。 */
        public Observation {
            Objects.requireNonNull(snapshot, "snapshot"); text(evidenceId);
            Objects.requireNonNull(snapshotAt, "snapshotAt"); Objects.requireNonNull(receivedAt, "receivedAt");
        }
    }

    /**
     * 调用侧需持久化的不可变合并状态。firstReceivedAt为首次可信观察，firstSettlementReceivedAt专供资格宽限。
     * 冲突隔离状态只能由后续明确的审查恢复流程处理，本纯合并器不会靠新事件自动清除。
     * historyPendingRevision是可恢复的查重水位，只有该修订或更新完整快照通过历史核验才可清除。
     */
    public record State(Snapshot latest, Instant firstReceivedAt, Instant settledAtAnchor,
            Instant firstSettlementReceivedAt, boolean quarantined, long historyPendingRevision) {
        /** 重建状态需保持时间锚点成对存在，不允许伪造半个历史接收记录。 */
        public State {
            Objects.requireNonNull(latest, "latest"); Objects.requireNonNull(firstReceivedAt, "firstReceivedAt");
            if (historyPendingRevision < 0) throw new IllegalArgumentException("待核验历史revision不可为负");
            if ((settledAtAnchor == null) != (firstSettlementReceivedAt == null)
                    || (firstSettlementReceivedAt != null && firstSettlementReceivedAt.isBefore(settledAtAnchor)))
                throw new IllegalArgumentException("结算事实时间锚点不完整或无效");
        }
    }

    /** 同revision历史查重结果必须由调用方数据库短事务显式提供，纯合并器不执行隐藏I/O。 */
    public sealed interface HistoryLookup permits NotSeen, Seen, Unavailable { }
    /** 已在权威历史中查明该scope/revision尚未出现；不是“没有执行查询”。 */
    public record NotSeen() implements HistoryLookup { }
    /** 同scope/revision的已存业务内容；运输元数据不纳入比较。 */
    public record Seen(Snapshot snapshot) implements HistoryLookup {
        /** 已见历史必须有完整可比较内容，不能只返回一个存在bool。 */
        public Seen { Objects.requireNonNull(snapshot, "snapshot"); }
    }
    /** 历史未查或暂不可用；必须失败关闭，不得当NotSeen推进。 */
    public record Unavailable() implements HistoryLookup { }

    private static void text(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("证据身份字段不能为空");
    }
}
