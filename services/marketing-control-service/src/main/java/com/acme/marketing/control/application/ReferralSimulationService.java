package com.acme.marketing.control.application;

import com.acme.marketing.lowcode.model.GraphDefinition;
import com.acme.marketing.referral.ReferralPlanCompiler;
import com.acme.marketing.referral.ReferralPolicyEvaluator;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/** 运营纯预览：输入是显式模拟事实，不访问权威渠道，不保存参与者/奖励，不产生授权。 */
@Component
public final class ReferralSimulationService {
    private static final Set<String> FIELDS = Set.of("boundAt", "now", "relationId", "validCount", "verified",
            "newCustomerAtBind", "firstValidOrder", "qualifyingFactAt", "qualifyingFactReceivedAt",
            "settledAmountMinor", "cumulativeRefundMinor", "currency");

    /**
     * 使用与签名编译相同的parser和SPI evaluator。validCount必须是本次假设变更后的有效人数。
     * 时间/规则/人数均显式输入；缺失权威事实用UNKNOWN/verified=false表达，不把模拟结果用于发奖。
     */
    public ReferralSimulation simulate(GraphDefinition graph, Map<String, String> facts) {
        if (facts == null || !facts.keySet().equals(FIELDS))
            throw new IllegalArgumentException("REFERRAL_SIMULATION_FIELDS_REQUIRED: " + FIELDS.stream().sorted().toList());
        var compiled = new ReferralPlanCompiler().compile(graph);
        var evidence = new ReferralPolicyEvaluator.Evidence(bool(facts, "verified"),
                ReferralPolicyEvaluator.Fact.valueOf(facts.get("newCustomerAtBind")),
                ReferralPolicyEvaluator.Fact.valueOf(facts.get("firstValidOrder")),
                optionalInstant(facts, "qualifyingFactAt"), optionalInstant(facts, "qualifyingFactReceivedAt"),
                number(facts, "settledAmountMinor"), number(facts, "cumulativeRefundMinor"), facts.get("currency"));
        var decision = ReferralPolicyEvaluator.evaluate(compiled.policy(), Instant.parse(facts.get("boundAt")),
                evidence, Instant.parse(facts.get("now")));
        long validCount = number(facts, "validCount");
        var candidates = ReferralPolicyEvaluator.rewardCandidates(compiled.policy(), facts.get("relationId"),
                decision.state() == ReferralPolicyEvaluator.State.ELIGIBLE, validCount);
        return new ReferralSimulation(true, EvidenceAuthority.SIMULATED_INPUT, decision, validCount, candidates);
    }

    /** 来源标签不能由facts指定，防止模拟结果被标成真实权威证明。 */
    public enum EvidenceAuthority { SIMULATED_INPUT }

    /** 只返回资格和规则候选；没有awardId、授权receipt或外部履约成功字段。 */
    public record ReferralSimulation(boolean simulationOnly, EvidenceAuthority evidenceAuthority,
            ReferralPolicyEvaluator.Decision qualification, long validCount,
            List<ReferralPolicyEvaluator.RewardCandidate> rewardCandidates) implements GraphSimulationService.SimulationResult {
        /** 防御性冻结候选列表，避免结果返回后被调用方改写。 */
        public ReferralSimulation { rewardCandidates = List.copyOf(rewardCandidates); }
    }

    private static long number(Map<String, String> facts, String key) {
        String value = facts.get(key);
        if (value == null || !value.matches("-?(0|[1-9][0-9]*)"))
            throw new IllegalArgumentException("REFERRAL_SIMULATION_INTEGER_REQUIRED: " + key);
        try { return Long.parseLong(value); }
        catch (NumberFormatException invalid) { throw new IllegalArgumentException("REFERRAL_SIMULATION_INTEGER_OVERFLOW: " + key, invalid); }
    }
    private static boolean bool(Map<String, String> facts, String key) {
        String value = facts.get(key);
        if (!"true".equals(value) && !"false".equals(value))
            throw new IllegalArgumentException("REFERRAL_SIMULATION_BOOLEAN_REQUIRED: " + key);
        return "true".equals(value);
    }
    private static Instant optionalInstant(Map<String, String> facts, String key) {
        String value = facts.get(key);
        if (value == null) throw new IllegalArgumentException("REFERRAL_SIMULATION_FIELD_REQUIRED: " + key);
        return value.isEmpty() ? null : Instant.parse(value);
    }
}
