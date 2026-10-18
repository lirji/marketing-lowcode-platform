package com.acme.marketing.decision.runtime;

import com.acme.marketing.decision.model.FundingRule;
import com.acme.marketing.decision.model.HitPolicy;
import com.acme.marketing.decision.model.OfferCandidate;
import com.acme.marketing.decision.model.PriceStage;
import com.acme.marketing.decision.model.PromotionFormula;
import com.acme.marketing.decision.model.PromotionKind;
import com.acme.marketing.decision.pricing.OfferSelector;
import com.acme.marketing.lowcode.model.Dialect;
import com.acme.marketing.lowcode.model.GraphDefinition;
import com.acme.marketing.lowcode.model.GraphNode;
import com.acme.marketing.lowcode.validation.OfferGraphInputGuard;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** 仿真与编译共享优惠参数解析和定价候选构建，签名元数据仍只由编译流程提供。 */
public final class OfferPolicyGraphCompiler {
    /** 标识已修复比例别名语义的编译器；该 ID 前缀被 ArtifactAttestation 签名覆盖。 */
    public static final String ARTIFACT_ID_PREFIX = "offer_policy-v2-";
    private static final String PREVIEW_BENEFIT_VERSION = "preview-only";

    /** 旧编译器把仅含 basisPoints 的策略编译成零比例；重新激活前必须重新编译。 */
    public static boolean requiresRecompilation(GraphDefinition graph, String artifactId) {
        return !artifactId.startsWith(ARTIFACT_ID_PREFIX) && graph.nodes().stream().anyMatch(node ->
                "offer.percentage".equals(node.stableTypeId()) && node.config().containsKey("basisPoints")
                        && !node.config().containsKey("discountBasisPoints"));
    }

    /** 严格编译已有策略 ABI；不支持的图语义不得静默丢弃。 */
    public OfferPolicyArtifact compile(GraphDefinition graph) {
        if (graph == null || graph.dialect() != Dialect.OFFER_DECISION_DAG) {
            throw new IllegalArgumentException("offer decision graph is required");
        }
        OfferGraphInputGuard.requireSupported(graph);
        List<OfferPolicy> policies = graph.nodes().stream()
                .filter(node -> node.stableTypeId().equals("offer.fixed")
                        || node.stableTypeId().equals("offer.percentage"))
                .map(this::policy)
                .toList();
        if (policies.isEmpty() || policies.size() > 1_000
                || policies.stream().map(policy -> policy.candidate().offerId()).distinct().count() != policies.size()) {
            throw new IllegalArgumentException("offer graph must lower to unique bounded policies");
        }
        validateSolverBoundary(policies.stream().map(OfferPolicy::candidate).toList());
        return new OfferPolicyArtifact(policies);
    }

    private static void validateSolverBoundary(List<OfferCandidate> candidates) {
        if (candidates.size() > 1_000 || candidates.stream().map(OfferCandidate::offerId).distinct().count() != candidates.size()) {
            throw new IllegalArgumentException("offer graph must lower to unique bounded policies");
        }
        Set<String> offerIds = candidates.stream().map(OfferCandidate::offerId)
                .collect(Collectors.toUnmodifiableSet());
        long optional = candidates.stream()
                .filter(candidate -> candidate.hitPolicy() == HitPolicy.BEST
                        || candidate.hitPolicy() == HitPolicy.MAX_N)
                .count();
        if (optional > OfferSelector.MAX_OPTIONAL_CANDIDATES) {
            throw new IllegalArgumentException("offer graph exceeds exact solver optional-candidate limit "
                    + OfferSelector.MAX_OPTIONAL_CANDIDATES);
        }
        for (OfferCandidate candidate : candidates) {
            if (!offerIds.containsAll(candidate.incompatibleOfferIds())) {
                throw new IllegalArgumentException("offer conflict references an unknown offer");
            }
            if ((candidate.hitPolicy() == HitPolicy.FIRST || candidate.hitPolicy() == HitPolicy.SUM)
                    && !candidate.incompatibleOfferIds().isEmpty()) {
                throw new IllegalArgumentException("FIRST/SUM offers cannot participate in advanced conflicts");
            }
        }
        candidates.stream().collect(Collectors.groupingBy(OfferCandidate::stackGroup))
                .forEach((group, grouped) -> {
                    HitPolicy hitPolicy = grouped.getFirst().hitPolicy();
                    if (grouped.stream().anyMatch(candidate -> candidate.hitPolicy() != hitPolicy)) {
                        throw new IllegalArgumentException("stack group uses inconsistent hit policies: " + group);
                    }
                    if (hitPolicy == HitPolicy.MAX_N) {
                        int limit = grouped.getFirst().maxGroupHits();
                        if (grouped.stream().anyMatch(candidate -> candidate.maxGroupHits() != limit)) {
                            throw new IllegalArgumentException("MAX_N group uses inconsistent limits: " + group);
                        }
                    }
                });
    }

    /** 金额预览复用同一候选规则；草稿缺少权益绑定时仅使用不可发布的本地占位标识。 */
    public List<OfferCandidate> previewCandidates(GraphDefinition graph) {
        OfferGraphInputGuard.requireSupported(graph);
        List<OfferCandidate> candidates = graph.nodes().stream()
                .filter(node -> node.stableTypeId().equals("offer.fixed") || node.stableTypeId().equals("offer.percentage"))
                .map(node -> candidate(node, false)).toList();
        // 预览和发布遵守相同的组合求解边界，防止预览接受线上无法处理的组合。
        validateSolverBoundary(candidates);
        return candidates;
    }

    private OfferPolicy policy(GraphNode node) {
        Map<String, String> config = node.config();
        return new OfferPolicy(candidate(node, true), Instant.parse(required(config, "validFrom")),
                Instant.parse(required(config, "validTo")), strings(config.get("channels")),
                strings(config.get("skuIds")), strings(config.get("categoryIds")), strings(config.get("shopIds")),
                config.getOrDefault("requiredAudienceSnapshotId", ""));
    }

    private OfferCandidate candidate(GraphNode node, boolean publishing) {
        Map<String, String> config = node.config();
        String benefitVersion = publishing ? required(config, "benefitDefinitionVersion")
                : config.getOrDefault("benefitDefinitionVersion", PREVIEW_BENEFIT_VERSION);
        PromotionKind kind = node.stableTypeId().equals("offer.fixed") ? PromotionKind.FIXED_OFF : PromotionKind.PERCENT_OFF;
        PromotionFormula formula = new PromotionFormula(kind, number(config, "thresholdMinor", 0),
                kind == PromotionKind.FIXED_OFF ? number(config, "amountMinor", 0) : 0,
                kind == PromotionKind.PERCENT_OFF ? OfferGraphInputGuard.discountBasisPoints(config) : 0,
                number(config, "capMinor", 0), integer(config, "maxApplications", 1));
        return new OfferCandidate(config.getOrDefault("offerId", node.id()), benefitVersion,
                PriceStage.valueOf(config.getOrDefault("stage", "PLATFORM")), formula,
                strings(config.get("eligibleLineIds")), config.getOrDefault("stackGroup", "default"),
                HitPolicy.valueOf(config.getOrDefault("hitPolicy", "BEST")), integer(config, "maxGroupHits", 1),
                integer(config, "priority", 0), strings(config.get("incompatibleOfferIds")),
                funding(config.getOrDefault("fundingRules", "PLATFORM|platform|10000")),
                config.getOrDefault("reasonCode", "OFFER_MATCHED"));
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
