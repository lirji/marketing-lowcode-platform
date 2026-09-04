package com.acme.marketing.compiler.application;

import com.acme.marketing.decision.model.FundingRule;
import com.acme.marketing.decision.model.HitPolicy;
import com.acme.marketing.decision.model.OfferCandidate;
import com.acme.marketing.decision.model.PriceStage;
import com.acme.marketing.decision.model.PromotionFormula;
import com.acme.marketing.decision.model.PromotionKind;
import com.acme.marketing.decision.pricing.OfferSelector;
import com.acme.marketing.decision.runtime.OfferPolicy;
import com.acme.marketing.decision.runtime.OfferPolicyArtifact;
import com.acme.marketing.lowcode.model.Dialect;
import com.acme.marketing.lowcode.model.GraphDefinition;
import com.acme.marketing.lowcode.model.GraphNode;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Deterministic lowering from the approved low-code offer graph to the online policy ABI. */
final class OfferPolicyGraphCompiler {
    OfferPolicyArtifact compile(GraphDefinition graph) {
        if (graph == null || graph.dialect() != Dialect.OFFER_DECISION_DAG) {
            throw new IllegalArgumentException("offer decision graph is required");
        }
        List<OfferPolicy> policies = graph.nodes().stream()
                .filter(node -> node.stableTypeId().equals("offer.fixed")
                        || node.stableTypeId().equals("offer.percentage"))
                .map(node -> policy(graph, node))
                .toList();
        if (policies.isEmpty() || policies.size() > 1_000
                || policies.stream().map(policy -> policy.candidate().offerId()).distinct().count() != policies.size()) {
            throw new IllegalArgumentException("offer graph must lower to unique bounded policies");
        }
        validateSolverBoundary(policies);
        return new OfferPolicyArtifact(policies);
    }

    private static void validateSolverBoundary(List<OfferPolicy> policies) {
        Set<String> offerIds = policies.stream().map(policy -> policy.candidate().offerId())
                .collect(Collectors.toUnmodifiableSet());
        long optional = policies.stream().map(OfferPolicy::candidate)
                .filter(candidate -> candidate.hitPolicy() == HitPolicy.BEST
                        || candidate.hitPolicy() == HitPolicy.MAX_N)
                .count();
        if (optional > OfferSelector.MAX_OPTIONAL_CANDIDATES) {
            throw new IllegalArgumentException("offer graph exceeds exact solver optional-candidate limit "
                    + OfferSelector.MAX_OPTIONAL_CANDIDATES);
        }
        for (OfferCandidate candidate : policies.stream().map(OfferPolicy::candidate).toList()) {
            if (!offerIds.containsAll(candidate.incompatibleOfferIds())) {
                throw new IllegalArgumentException("offer conflict references an unknown offer");
            }
            if ((candidate.hitPolicy() == HitPolicy.FIRST || candidate.hitPolicy() == HitPolicy.SUM)
                    && !candidate.incompatibleOfferIds().isEmpty()) {
                throw new IllegalArgumentException("FIRST/SUM offers cannot participate in advanced conflicts");
            }
        }
        policies.stream().map(OfferPolicy::candidate).collect(Collectors.groupingBy(OfferCandidate::stackGroup))
                .forEach((group, candidates) -> {
                    HitPolicy hitPolicy = candidates.getFirst().hitPolicy();
                    if (candidates.stream().anyMatch(candidate -> candidate.hitPolicy() != hitPolicy)) {
                        throw new IllegalArgumentException("stack group uses inconsistent hit policies: " + group);
                    }
                    if (hitPolicy == HitPolicy.MAX_N) {
                        int limit = candidates.getFirst().maxGroupHits();
                        if (candidates.stream().anyMatch(candidate -> candidate.maxGroupHits() != limit)) {
                            throw new IllegalArgumentException("MAX_N group uses inconsistent limits: " + group);
                        }
                    }
                });
    }

    private OfferPolicy policy(GraphDefinition graph, GraphNode node) {
        Map<String, String> config = node.config();
        String offerId = config.getOrDefault("offerId", node.id());
        String benefitVersion = required(config, "benefitDefinitionVersion");
        PromotionKind kind = node.stableTypeId().equals("offer.fixed")
                ? PromotionKind.FIXED_OFF : PromotionKind.PERCENT_OFF;
        long valueMinor = kind == PromotionKind.FIXED_OFF ? number(config, "amountMinor", 0) : 0;
        int basisPoints = kind == PromotionKind.PERCENT_OFF ? integer(config, "discountBasisPoints", 0) : 0;
        PromotionFormula formula = new PromotionFormula(kind, number(config, "thresholdMinor", 0), valueMinor,
                basisPoints, number(config, "capMinor", 0), integer(config, "maxApplications", 1));
        OfferCandidate candidate = new OfferCandidate(offerId, benefitVersion,
                PriceStage.valueOf(config.getOrDefault("stage", "PLATFORM")), formula,
                strings(config.get("eligibleLineIds")), config.getOrDefault("stackGroup", "default"),
                HitPolicy.valueOf(config.getOrDefault("hitPolicy", "BEST")), integer(config, "maxGroupHits", 1),
                integer(config, "priority", 0), strings(config.get("incompatibleOfferIds")),
                funding(config.getOrDefault("fundingRules", "PLATFORM|platform|10000")),
                config.getOrDefault("reasonCode", "OFFER_MATCHED"));
        return new OfferPolicy(candidate, Instant.parse(required(config, "validFrom")),
                Instant.parse(required(config, "validTo")), strings(config.get("channels")),
                strings(config.get("skuIds")), strings(config.get("categoryIds")), strings(config.get("shopIds")),
                config.getOrDefault("requiredAudienceSnapshotId", ""));
    }

    private static List<FundingRule> funding(String value) {
        return Arrays.stream(value.split(";"))
                .filter(item -> !item.isBlank())
                .map(item -> {
                    String[] fields = item.split("\\|", -1);
                    if (fields.length != 3) throw new IllegalArgumentException("fundingRules is invalid");
                    return new FundingRule(fields[0].trim(), fields[1].trim(), Integer.parseInt(fields[2].trim()));
                }).toList();
    }

    private static Set<String> strings(String value) {
        if (value == null || value.isBlank()) return Set.of();
        return Arrays.stream(value.split(",")).map(String::trim).filter(item -> !item.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    private static String required(Map<String, String> config, String key) {
        String value = config.get(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException(key + " is required");
        return value;
    }
    private static long number(Map<String, String> config, String key, long fallback) {
        return config.containsKey(key) ? Long.parseLong(config.get(key)) : fallback;
    }
    private static int integer(Map<String, String> config, String key, int fallback) {
        return config.containsKey(key) ? Integer.parseInt(config.get(key)) : fallback;
    }
}
