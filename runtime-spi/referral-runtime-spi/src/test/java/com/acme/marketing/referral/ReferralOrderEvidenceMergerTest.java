package com.acme.marketing.referral;

import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static com.acme.marketing.referral.ReferralOrderEvidence.*;
import static com.acme.marketing.referral.ReferralOrderEvidenceMerger.*;
import static org.junit.jupiter.api.Assertions.*;

/** 合并器纯合同回归，fixture来源标识不代表真实渠道已经签收。 */
class ReferralOrderEvidenceMergerTest {
    private static final Instant SETTLED = Instant.parse("2026-09-01T00:00:10Z");
    private static final Instant FIRST = SETTLED.plusSeconds(10);
    private static final Instant NOW = SETTLED.plusSeconds(100);
    private static final Scope SCOPE = new Scope("tenant", "source", "subject", "order", "org", "shop");

    @Test void replayWithDifferentTransportMetadataDoesNotAddRefundOrMoveFirstReceipt() {
        var snapshot = snapshot(1, 20, 0, SETTLED);
        var first = merge(null, observation(snapshot, FIRST, true), NOW);
        var retry = merge(first.state(), new Observation(snapshot, "other-event-and-evidence-id", NOW, NOW, true), NOW);
        assertEquals(Outcome.REPLAY, retry.outcome());
        assertEquals(first.state(), retry.state());
        assertEquals(20, retry.state().latest().cumulativeRefundMinor());
        assertEquals(FIRST, retry.state().firstReceivedAt());
        assertEquals(FIRST, retry.state().firstSettlementReceivedAt());
    }
    @Test void sameRevisionDifferentStableContentQuarantinesEvenIfNewMoneyIsInvalid() {
        var state = accepted(snapshot(1, 20, 0, SETTLED));
        var changed = merge(state, observation(snapshot(1, 30, 0, SETTLED), NOW, true), NOW);
        assertEquals(Reason.REVISION_CONFLICT, changed.reason());
        assertTrue(changed.state().quarantined());
        assertEquals(state.latest(), changed.state().latest());
        var invalid = new Snapshot(SCOPE, 1, "policy-v1", ReferralPolicyEvaluator.Fact.YES, true, OrderState.SETTLED,
                SETTLED, 100, 200, 0, -100, "CNY");
        assertEquals(Reason.REVISION_CONFLICT, merge(state, observation(invalid, NOW, true), NOW).reason());
        assertFalse(toEvaluatorEvidence(changed.state()).verified());
    }
    @Test void refundBeforeSettlementRemainsPendingAndOldRevisionCannotFillOrRollBack() {
        var refundFirst = merge(null, observation(snapshot(8, 30, 0, null), FIRST, true), NOW);
        assertEquals(Reason.SETTLEMENT_PENDING, refundFirst.reason());
        assertEquals(30, refundFirst.state().latest().cumulativeRefundMinor());
        assertNull(refundFirst.state().firstSettlementReceivedAt());
        var old = merge(refundFirst.state(), observation(snapshot(7, 0, 0, SETTLED), FIRST.plusSeconds(1), true), NOW);
        assertEquals(Outcome.IGNORED_OLDER, old.outcome());
        assertEquals(refundFirst.state(), old.state());
        assertFalse(toEvaluatorEvidence(old.state()).verified());
        assertEquals(Reason.REVISION_CONFLICT, merge(old.state(), observation(snapshot(8, 30, 0, SETTLED), NOW, true), NOW).reason());
    }
    @Test void newerCompleteSnapshotUsesFixedFirstSettlementReceiptAcrossFurtherRefunds() {
        var state = accepted(snapshot(8, 30, 0, null));
        var complete = merge(state, observation(snapshot(9, 30, 0, SETTLED), FIRST.plusSeconds(20), true), NOW);
        assertEquals(Reason.READY_FOR_EVALUATOR, complete.reason());
        var refunded = merge(complete.state(), observation(snapshot(10, 40, 0, SETTLED), FIRST.plusSeconds(30), true), NOW);
        assertEquals(40, refunded.state().latest().cumulativeRefundMinor());
        assertEquals(FIRST, refunded.state().firstReceivedAt());
        assertEquals(FIRST.plusSeconds(20), toEvaluatorEvidence(refunded.state()).qualifyingFactReceivedAt());
    }
    @Test void refundRegressionAndOlderContradictionQuarantineWithoutAutomaticRecovery() {
        var state = accepted(snapshot(8, 30, 0, SETTLED));
        assertEquals(Reason.REFUND_REGRESSION, merge(state, observation(snapshot(7, 40, 0, SETTLED), NOW, true), NOW).reason());
        var conflict = merge(state, observation(snapshot(9, 20, 0, SETTLED), NOW, true), NOW);
        assertEquals(Reason.REFUND_REGRESSION, conflict.reason());
        assertEquals(30, conflict.state().latest().cumulativeRefundMinor());
        assertEquals(Reason.QUARANTINED, merge(conflict.state(), observation(snapshot(10, 40, 0, SETTLED), NOW, true), NOW).reason());
    }
    @Test void unverifiedOrFutureObservationsCannotOccupyEitherFirstReceipt() {
        var snapshot = snapshot(1, 0, 0, SETTLED);
        assertNull(merge(null, observation(snapshot, FIRST, false), NOW).state());
        assertEquals(Reason.SOURCE_UNVERIFIED, merge(null, observation(snapshot, FIRST, false), NOW).reason());
        for (var observation : List.of(observation(snapshot, NOW.plusSeconds(1), true),
                new Observation(snapshot, "e", FIRST.plusSeconds(1), FIRST, true),
                observation(snapshot(1, 0, 0, NOW.plusSeconds(1)), NOW, true))) {
            var result = merge(null, observation, NOW);
            assertEquals(Reason.FUTURE_OR_INCONSISTENT_TIME, result.reason());
            assertNull(result.state());
        }
        var accepted = merge(null, observation(snapshot, FIRST, true), NOW);
        assertEquals(FIRST, accepted.state().firstSettlementReceivedAt());
        assertEquals(accepted.state(), merge(accepted.state(), observation(snapshot(2, 20, 0, SETTLED), NOW, false), NOW).state());
    }
    @Test void everyScopeDimensionIsExactAndCrossScopeCannotPoisonState() {
        var state = accepted(snapshot(1, 0, 0, SETTLED));
        var scopes = List.of(new Scope("other", "source", "subject", "order", "org", "shop"),
                new Scope("tenant", "other", "subject", "order", "org", "shop"),
                new Scope("tenant", "source", "Subject", "order", "org", "shop"),
                new Scope("tenant", "source", "subject", "other", "org", "shop"),
                new Scope("tenant", "source", "subject", "order", "other", "shop"),
                new Scope("tenant", "source", "subject", "order", "org", "other"));
        for (var scope : scopes) {
            var snapshot = new Snapshot(scope, 2, "policy-v1", ReferralPolicyEvaluator.Fact.YES, true,
                    OrderState.SETTLED, SETTLED, 100, 0, 0, 100, "CNY");
            var result = merge(state, observation(snapshot, NOW, true), NOW);
            assertEquals(Reason.SCOPE_MISMATCH, result.reason()); assertEquals(state, result.state());
        }
    }
    @Test void pendingRefundDoesNotGetTreatedAsSucceededAndReleaseCanResumeEvidenceReadiness() {
        var pending = merge(null, observation(snapshot(1, 10, 20, SETTLED), FIRST, true), NOW);
        assertEquals(Reason.REFUND_PENDING, pending.reason());
        var projection = toEvaluatorEvidence(pending.state());
        assertFalse(projection.verified()); assertEquals(10, projection.cumulativeRefundMinor());
        var released = merge(pending.state(), observation(snapshot(2, 10, 0, SETTLED), NOW, true), NOW);
        assertEquals(Reason.READY_FOR_EVALUATOR, released.reason());
        assertEquals(FIRST, released.state().firstSettlementReceivedAt());
    }
    @Test void missingMappingOrFirstOrderPolicyNeverInfersNewCustomer() {
        for (var snapshot : List.of(
                new Snapshot(SCOPE, 1, "policy-v1", ReferralPolicyEvaluator.Fact.YES, false, OrderState.SETTLED, SETTLED, 100, 0, 0, 100, "CNY"),
                new Snapshot(SCOPE, 1, null, ReferralPolicyEvaluator.Fact.YES, true, OrderState.SETTLED, SETTLED, 100, 0, 0, 100, "CNY"),
                new Snapshot(SCOPE, 1, "policy-v1", ReferralPolicyEvaluator.Fact.UNKNOWN, true, OrderState.SETTLED, SETTLED, 100, 0, 0, 100, "CNY"))) {
            var result = merge(null, observation(snapshot, FIRST, true), NOW);
            assertFalse(toEvaluatorEvidence(result.state()).verified());
            assertEquals(ReferralPolicyEvaluator.Fact.UNKNOWN, toEvaluatorEvidence(result.state()).newCustomerAtBind());
        }
        var ready = toEvaluatorEvidence(accepted(snapshot(1, 0, 0, SETTLED)));
        assertTrue(ready.verified());
        assertEquals(ReferralPolicyEvaluator.Fact.UNKNOWN, ready.newCustomerAtBind());
        var plan = new ReferralPlan(SETTLED.minusSeconds(10), NOW.plusSeconds(100), NOW.plusSeconds(200), 100, 0, 100,
                ReferralPlan.GoalType.FIRST_ORDER_SETTLED, 1, "CNY", List.of(new ReferralRewardRule("r", ReferralRewardRule.Role.INVITER,
                ReferralRewardRule.Mode.PER_RELATION, 1, "benefit-v1", "sku-v1", 1, 1, 1)));
        assertEquals(ReferralPolicyEvaluator.Reason.EVIDENCE_UNAVAILABLE,
                ReferralPolicyEvaluator.evaluate(plan, SETTLED.minusSeconds(1), ready, NOW).reason());
    }
    @Test void monetaryInconsistencyIsRejectedWithoutOverflowOrMutation() {
        for (var values : List.of(new long[]{100, 101, 0, -1}, new long[]{100, 20, 81, 80},
                new long[]{100, 20, 0, 100}, new long[]{-1, 0, 0, -1}, new long[]{100, -1, 0, 101})) {
            var snapshot = new Snapshot(SCOPE, 1, "policy-v1", ReferralPolicyEvaluator.Fact.YES, true, OrderState.SETTLED,
                    SETTLED, values[0], values[1], values[2], values[3], "CNY");
            assertEquals(Reason.INVALID_AMOUNTS, merge(null, observation(snapshot, FIRST, true), NOW).reason());
        }
        var max = new Snapshot(SCOPE, 1, "policy-v1", ReferralPolicyEvaluator.Fact.YES, true, OrderState.SETTLED,
                SETTLED, Long.MAX_VALUE, Long.MAX_VALUE - 10, 10, 10, "CNY");
        assertEquals(Reason.REFUND_PENDING, merge(null, observation(max, FIRST, true), NOW).reason());
    }
    @Test void currencyPolicyAndSettlementIdentityChangesRequireReview() {
        var state = accepted(snapshot(1, 0, 0, SETTLED));
        var currency = new Snapshot(SCOPE, 2, "policy-v1", ReferralPolicyEvaluator.Fact.YES, true, OrderState.SETTLED, SETTLED, 100, 0, 0, 100, "USD");
        var policy = new Snapshot(SCOPE, 2, "policy-v2", ReferralPolicyEvaluator.Fact.YES, true, OrderState.SETTLED, SETTLED, 100, 0, 0, 100, "CNY");
        assertEquals(Reason.CURRENCY_CHANGED, merge(state, observation(currency, NOW, true), NOW).reason());
        assertEquals(Reason.POLICY_CHANGED, merge(state, observation(policy, NOW, true), NOW).reason());
        assertEquals(Reason.SETTLEMENT_CHANGED, merge(state, observation(snapshot(2, 0, 0, SETTLED.plusSeconds(1)), NOW, true), NOW).reason());
    }
    @Test void cancelledAndFullyRefundedOrdersAreNeverProjectedAsSettled() {
        for (var status : List.of(OrderState.CANCELLED, OrderState.REFUNDED)) {
            var snapshot = new Snapshot(SCOPE, 1, "policy-v1", ReferralPolicyEvaluator.Fact.YES, true, status, SETTLED, 100, 100, 0, 0, "CNY");
            assertFalse(toEvaluatorEvidence(merge(null, observation(snapshot, FIRST, true), NOW).state()).verified());
        }
    }
    @Test void reorderedValidCumulativeSnapshotsConvergeWithoutSummingEvents() {
        var revisions = List.of(snapshot(1, 0, 0, SETTLED), snapshot(2, 10, 0, SETTLED), snapshot(3, 20, 0, SETTLED));
        for (var order : List.of(List.of(0, 1, 2), List.of(2, 0, 1), List.of(1, 2, 0))) {
            State state = null;
            for (int index : order) state = merge(state, observation(revisions.get(index), FIRST, true), NOW).state();
            assertEquals(revisions.getLast(), state.latest());
            assertEquals(20, state.latest().cumulativeRefundMinor());
            assertEquals(state, merge(state, observation(revisions.getLast(), NOW, true), NOW).state());
        }
    }
    @Test void explicitHistoryDetectsConflictsAfterCurrentRevisionHasAdvanced() {
        var previous = snapshot(1, 0, 0, SETTLED);
        var state = accepted(snapshot(2, 20, 0, SETTLED));
        var conflict = ReferralOrderEvidenceMerger.merge(state, observation(snapshot(1, 10, 0, SETTLED), NOW, true), new Seen(previous), NOW);
        assertEquals(Reason.REVISION_CONFLICT, conflict.reason()); assertTrue(conflict.state().quarantined());
        var replay = ReferralOrderEvidenceMerger.merge(state, observation(previous, NOW, true), new Seen(previous), NOW);
        assertEquals(Outcome.REPLAY, replay.outcome()); assertEquals(state, replay.state());
        var wrongScope = new Scope("other", "source", "subject", "order", "org", "shop");
        var wrong = new Snapshot(wrongScope, 1, "policy-v1", ReferralPolicyEvaluator.Fact.YES, true, OrderState.SETTLED,
                SETTLED, 100, 0, 0, 100, "CNY");
        var pending = ReferralOrderEvidenceMerger.merge(state, observation(previous, NOW, true), new Seen(wrong), NOW);
        assertEquals(Reason.HISTORY_SCOPE_MISMATCH, pending.reason()); assertFalse(toEvaluatorEvidence(pending.state()).verified());
        assertTrue(toEvaluatorEvidence(ReferralOrderEvidenceMerger.merge(pending.state(), observation(previous, NOW, true), new Seen(previous), NOW).state()).verified());
    }
    @Test void unavailableHistoryBlocksProjectionAndOlderReplayCannotClearNewerPendingWatermark() {
        var state = accepted(snapshot(1, 0, 0, SETTLED));
        var incoming = observation(snapshot(2, 20, 0, SETTLED), NOW, true);
        var pending = ReferralOrderEvidenceMerger.merge(state, incoming, new Unavailable(), NOW);
        assertEquals(Reason.HISTORY_UNAVAILABLE, pending.reason());
        assertEquals(2, pending.state().historyPendingRevision());
        assertFalse(toEvaluatorEvidence(pending.state()).verified());
        var oldReplay = ReferralOrderEvidenceMerger.merge(pending.state(), observation(state.latest(), NOW, true), new Seen(state.latest()), NOW);
        assertFalse(toEvaluatorEvidence(oldReplay.state()).verified());
        var recovered = ReferralOrderEvidenceMerger.merge(oldReplay.state(), incoming, new NotSeen(), NOW);
        assertTrue(toEvaluatorEvidence(recovered.state()).verified());
        assertEquals(FIRST, recovered.state().firstSettlementReceivedAt());
        assertEquals(0, recovered.state().historyPendingRevision());
        assertNull(ReferralOrderEvidenceMerger.merge(null, incoming, new Unavailable(), NOW).state());
        assertNull(ReferralOrderEvidenceMerger.merge(null, incoming, new Seen(incoming.snapshot()), NOW).state());
    }
    @Test void trustedNewInvalidContentQuarantinesAndCannotReturnToOldReadyByReplay() {
        var state = accepted(snapshot(1, 0, 0, SETTLED));
        var badInputs = List.of(
                new Snapshot(SCOPE, 2, "policy-v1", ReferralPolicyEvaluator.Fact.YES, true, OrderState.SETTLED, SETTLED, 100, 50, 0, 100, "CNY"),
                new Snapshot(SCOPE, 2, "policy-v1", ReferralPolicyEvaluator.Fact.YES, true, OrderState.SETTLED, SETTLED, 100, 50, 0, 50, "bad"),
                new Snapshot(SCOPE, 2, "policy-v1", ReferralPolicyEvaluator.Fact.YES, true, OrderState.REFUNDED, SETTLED, 100, 50, 0, 50, "CNY"));
        for (var bad : badInputs) {
            var quarantined = ReferralOrderEvidenceMerger.merge(state, observation(bad, NOW, true), new NotSeen(), NOW);
            assertTrue(quarantined.state().quarantined()); assertFalse(toEvaluatorEvidence(quarantined.state()).verified());
            assertEquals(state.latest(), quarantined.state().latest());
            var replay = ReferralOrderEvidenceMerger.merge(quarantined.state(), observation(state.latest(), NOW, true), new Seen(state.latest()), NOW);
            assertEquals(Reason.QUARANTINED, replay.reason()); assertFalse(toEvaluatorEvidence(replay.state()).verified());
        }
    }
    @Test void scopePreservesUnicodeIdentityAndDoesNotLeakThroughNestedRecordOutput() {
        var emoji = "😀".repeat(256);
        assertEquals(emoji, new Scope("t", "s", emoji, "o", "org", "shop").canonicalSubject());
        assertThrows(IllegalArgumentException.class, () -> new Scope("t", "s", "x".repeat(257), "o", "org", "shop"));
        assertThrows(IllegalArgumentException.class, () -> new Scope("t", "s", String.valueOf((char) 0xD800), "o", "org", "shop"));
        assertNotEquals(new Scope("t", "s", "é", "o", "org", "shop"), new Scope("t", "s", "e\u0301", "o", "org", "shop"));
        var snapshot = snapshot(1, 0, 0, SETTLED); var observation = observation(snapshot, FIRST, true);
        var result = ReferralOrderEvidenceMerger.merge(null, observation, new NotSeen(), NOW);
        for (Object object : List.of(SCOPE, snapshot, observation, result.state(), result))
            assertFalse(object.toString().contains(SCOPE.canonicalSubject()));
    }
    /** 测试夹具显式提供模拟历史查询；生产API不存在绕过lookup的三参重载。 */
    private static MergeResult merge(State current, Observation observation, Instant now) {
        HistoryLookup history = current != null && current.latest().revision() == observation.snapshot().revision()
                ? new Seen(current.latest()) : new NotSeen();
        return ReferralOrderEvidenceMerger.merge(current, observation, history, now);
    }
    private static State accepted(Snapshot snapshot) { return merge(null, observation(snapshot, FIRST, true), NOW).state(); }
    private static Observation observation(Snapshot snapshot, Instant receivedAt, boolean verified) {
        return new Observation(snapshot, "fixture-evidence", receivedAt, receivedAt, verified);
    }
    private static Snapshot snapshot(long revision, long refunded, long pending, Instant settledAt) {
        return new Snapshot(SCOPE, revision, "policy-v1", ReferralPolicyEvaluator.Fact.YES, true,
                settledAt == null ? OrderState.PENDING : OrderState.SETTLED, settledAt, 100, refunded, pending, 100 - refunded, "CNY");
    }
}
