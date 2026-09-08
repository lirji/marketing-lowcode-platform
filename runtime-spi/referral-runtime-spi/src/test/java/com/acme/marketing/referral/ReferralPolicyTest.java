package com.acme.marketing.referral;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import static com.acme.marketing.referral.ReferralPlan.GoalType.*;
import static com.acme.marketing.referral.ReferralPolicyEvaluator.*;
import static com.acme.marketing.referral.ReferralRewardRule.Mode.*;
import static com.acme.marketing.referral.ReferralRewardRule.Role.*;
import static org.junit.jupiter.api.Assertions.*;

/** 规则层风险回归；数据仅是纯单测输入，不代表生产配置或权威渠道已验收。 */
class ReferralPolicyTest {
    private static final Instant START = Instant.parse("2026-09-01T00:00:00Z");
    private static final Instant BIND = START.plusSeconds(10);
    private static final Instant FACT = BIND.plusSeconds(10);
    private static final ReferralRewardRule INVITER_RULE = rule("inviter", INVITER, PER_RELATION, 1);
    private static final ReferralRewardRule INVITEE_RULE = rule("invitee", INVITEE, PER_RELATION, 1);
    private static final List<ReferralRewardRule> RULES = List.of(INVITER_RULE, INVITEE_RULE,
            rule("three", INVITER, MILESTONE, 3), rule("five", INVITER, MILESTONE, 5));

    @Test void eligibleOrderAtExactObservationBoundary() {
        assertEquals(Reason.OBSERVATION_PENDING, eval(plan(), evidence(100, 0), FACT.plusSeconds(9)).reason());
        assertEquals(FACT.plusSeconds(10), eval(plan(), evidence(100, 0), FACT).dueAt());
        assertEquals(State.ELIGIBLE, eval(plan(), evidence(100, 0), FACT.plusSeconds(10)).state());
    }
    @Test void refundNetBoundaryAndOverflowSafety() {
        assertEquals(State.ELIGIBLE, eval(plan(), evidence(Long.MAX_VALUE, Long.MAX_VALUE - 100), FACT.plusSeconds(10)).state());
        assertEquals(Reason.NET_BELOW_THRESHOLD, eval(plan(), evidence(100, 1), FACT).reason());
        assertEquals(Reason.INVALID_AMOUNTS, eval(plan(), evidence(100, 101), FACT).reason());
        assertEquals(Reason.INVALID_AMOUNTS, eval(plan(), evidence(-1, 0), FACT).reason());
        assertEquals(Reason.INVALID_AMOUNTS, eval(plan(), evidence(100, -1), FACT).reason());
    }
    @Test void unknownAuthorityCannotQualify() {
        assertEquals(State.PENDING, eval(plan(), null, FACT).state());
        for (Fact value : new Fact[]{null, Fact.UNKNOWN}) {
            assertEquals(State.PENDING, eval(plan(), new Evidence(true, value, Fact.YES, FACT, FACT, 100, 0, "CNY"), FACT).state());
            assertEquals(State.PENDING, eval(plan(), new Evidence(true, Fact.YES, value, FACT, FACT, 100, 0, "CNY"), FACT).state());
        }
        assertEquals(State.PENDING, eval(plan(), new Evidence(false, Fact.YES, Fact.YES, FACT, FACT, 100, 0, "CNY"), FACT).state());
        assertEquals(Reason.NOT_NEW_CUSTOMER, eval(plan(), new Evidence(true, Fact.NO, Fact.YES, FACT, FACT, 100, 0, "CNY"), FACT).reason());
        assertEquals(Reason.NOT_FIRST_ORDER, eval(plan(), new Evidence(true, Fact.YES, Fact.NO, FACT, FACT, 100, 0, "CNY"), FACT).reason());
    }
    @Test void currencyMismatchFailsClosed() {
        assertEquals(State.PENDING, eval(plan(), new Evidence(true, Fact.YES, Fact.YES, FACT, FACT, 100, 0, "USD"), FACT).state());
    }
    @Test void bindingAndQualificationUseHalfOpenWindows() {
        assertEquals(Reason.BIND_OUTSIDE_WINDOW, evaluate(plan(), START.minusSeconds(1), evidence(100, 0), FACT).reason());
        assertEquals(Reason.BIND_OUTSIDE_WINDOW, evaluate(plan(), plan().endsAt(), evidence(100, 0), plan().endsAt()).reason());
        assertEquals(Reason.FACT_OUTSIDE_WINDOW, eval(plan(), at(BIND.minusSeconds(1)), FACT).reason());
        assertEquals(Reason.FACT_OUTSIDE_WINDOW, eval(plan(), at(BIND.plusSeconds(100)), BIND.plusSeconds(100)).reason());
        assertEquals(State.ELIGIBLE, evaluate(plan(), START, at(START), START.plusSeconds(10)).state());
    }
    @Test void registrationAcceptsRegisteredLoginBeforeBindOnlyWithAuthority() {
        var p = withGoal(REGISTERED_NEW_CUSTOMER);
        assertEquals(State.ELIGIBLE, eval(p, new Evidence(true, Fact.YES, Fact.UNKNOWN, BIND.minusSeconds(1), BIND, 0, 0, null), FACT.plusSeconds(10)).state());
        assertEquals(Reason.FACT_OUTSIDE_WINDOW, eval(p, at(START.minusSeconds(1)), FACT).reason());
        assertEquals(Reason.FACT_OUTSIDE_WINDOW, eval(p, at(FACT), FACT).reason());
        assertEquals(State.ELIGIBLE, eval(p, at(BIND), FACT).state());
        assertEquals(State.PENDING, eval(p, new Evidence(true, Fact.UNKNOWN, Fact.UNKNOWN,
                BIND.minusSeconds(1), BIND, 0, 0, null), FACT).state());
    }
    @Test void registrationBeforeBindPreservesOriginalReceiptAndObservationOrigin() {
        var p = withGoal(REGISTERED_NEW_CUSTOMER);
        var early = new Evidence(true, Fact.YES, Fact.UNKNOWN, START, START.plusSeconds(1), 0, 0, null);
        assertEquals(State.ELIGIBLE, eval(p, early, BIND).state());
        var observing = new Evidence(true, Fact.YES, Fact.UNKNOWN,
                START.plusSeconds(5), START.plusSeconds(6), 0, 0, null);
        assertEquals(START.plusSeconds(15), eval(p, observing, BIND).dueAt());
        assertEquals(State.ELIGIBLE, eval(p, observing, START.plusSeconds(15)).state());
    }
    @Test void futureAndIncompleteEvidenceStayPending() {
        assertEquals(Reason.FUTURE_EVIDENCE, eval(plan(), evidence(100, 0), FACT.minusSeconds(1)).reason());
        assertEquals(Reason.FUTURE_EVIDENCE, eval(plan(), new Evidence(true, Fact.YES, Fact.YES, FACT, BIND, 100, 0, "CNY"), FACT).reason());
        assertEquals(Reason.EVIDENCE_UNAVAILABLE, eval(plan(), new Evidence(true, Fact.YES, Fact.YES, null, FACT, 100, 0, "CNY"), FACT).reason());
    }
    @Test void lateArrivalUsesFirstReceiptAndRemainsReplayable() {
        var deadline = BIND.plusSeconds(100);
        var accepted = new Evidence(true, Fact.YES, Fact.YES, FACT, deadline.plusSeconds(19), 100, 0, "CNY");
        assertEquals(State.ELIGIBLE, eval(plan(), accepted, deadline.plusSeconds(19)).state());
        assertEquals(State.ELIGIBLE, eval(plan(), accepted, START.plusSeconds(10000)).state());
        assertEquals(State.REVIEW, eval(plan(), new Evidence(true, Fact.YES, Fact.YES, FACT, deadline.plusSeconds(20), 100, 0, "CNY"), deadline.plusSeconds(20)).state());
    }
    @Test void settlementCapsWindowAndObservation() {
        var p = new ReferralPlan(START, START.plusSeconds(25), START.plusSeconds(30), 100, 10, 20, FIRST_ORDER_SETTLED, 100, "CNY", RULES);
        assertEquals(Reason.OBSERVATION_OUTSIDE_SETTLEMENT, eval(p, at(FACT), START.plusSeconds(30)).reason());
        assertEquals(Reason.FACT_OUTSIDE_WINDOW, eval(p, at(START.plusSeconds(30)), START.plusSeconds(30)).reason());
    }
    @Test void bilateralRewardsAndAllCrossedMilestonesAreIndependent() {
        var candidates = rewardCandidates(plan(), "relation", true, 5);
        assertEquals(List.of("inviter", "invitee", "three", "five"), candidates.stream().map(c -> c.rule().ruleId()).toList());
        assertEquals(List.of("relation", "relation", "3", "5"), candidates.stream().map(RewardCandidate::milestoneKey).toList());
        assertEquals(List.of("three"), rewardCandidates(plan(), "relation", false, 3).stream().map(c -> c.rule().ruleId()).toList());
        assertTrue(rewardCandidates(plan(), "relation", false, 2).isEmpty());
        assertEquals(candidates, rewardCandidates(plan(), "relation", true, 5));
        assertThrows(UnsupportedOperationException.class, () -> candidates.clear());
    }
    @Test void rejectsInvalidCountsAndRuleConfiguration() {
        assertThrows(IllegalArgumentException.class, () -> rewardCandidates(plan(), "relation", true, 0));
        assertThrows(IllegalArgumentException.class, () -> rewardCandidates(plan(), "relation", false, -1));
        for (var invalid : List.of(
                new ReferralRewardRule("a", INVITER, PER_RELATION, 1, "b", "s", 2, 1, 10),
                new ReferralRewardRule("a", INVITEE, MILESTONE, 3, "b", "s", 1, 1, 10),
                new ReferralRewardRule("a", INVITER, PER_RELATION, 2, "b", "s", 1, 1, 10),
                new ReferralRewardRule("a", INVITER, PER_RELATION, 1, "", "s", 1, 1, 10),
                new ReferralRewardRule("a", INVITER, PER_RELATION, 1, "b", "s", 1, 11, 10))) {
            assertThrows(IllegalArgumentException.class, () -> ReferralPolicyValidator.validate(withRules(List.of(invalid))));
        }
        assertThrows(IllegalArgumentException.class, () -> ReferralPolicyValidator.validate(withRules(List.of(INVITER_RULE, INVITER_RULE))));
        assertThrows(IllegalArgumentException.class, () -> ReferralPolicyValidator.validate(withRules(List.of())));
    }
    @Test void freezesRewardListAndRejectsTimeOverflow() {
        var mutable = new ArrayList<>(RULES);
        var p = withRules(mutable);
        mutable.clear();
        assertEquals(4, p.rewards().size());
        assertThrows(IllegalArgumentException.class, () -> ReferralPolicyValidator.validate(new ReferralPlan(START, START.plusSeconds(1000), Instant.MAX, 100, 10, 20, FIRST_ORDER_SETTLED, 100, "CNY", RULES)));
    }
    private static ReferralRewardRule rule(String id, ReferralRewardRule.Role role, ReferralRewardRule.Mode mode, long threshold) {
        return new ReferralRewardRule(id, role, mode, threshold, "benefit-v1", "sku-v1", 1, 10, 100);
    }
    private static ReferralPlan plan() { return withGoal(FIRST_ORDER_SETTLED); }
    private static ReferralPlan withGoal(ReferralPlan.GoalType goal) {
        return new ReferralPlan(START, START.plusSeconds(1000), START.plusSeconds(2000), 100, 10, 20, goal, 100, "CNY", RULES);
    }
    private static ReferralPlan withRules(List<ReferralRewardRule> rules) {
        var p = plan();
        return new ReferralPlan(p.startsAt(), p.endsAt(), p.settlementEndsAt(), 100, 10, 20, p.goalType(), 100, "CNY", rules);
    }
    private static Evidence at(Instant fact) { return new Evidence(true, Fact.YES, Fact.YES, fact, fact, 100, 0, "CNY"); }
    private static Evidence evidence(long settled, long refunded) { return new Evidence(true, Fact.YES, Fact.YES, FACT, FACT, settled, refunded, "CNY"); }
    private static Decision eval(ReferralPlan plan, Evidence evidence, Instant now) { return evaluate(plan, BIND, evidence, now); }
}
