package com.acme.marketing.referral;

import com.acme.marketing.lowcode.model.*;
import com.acme.marketing.lowcode.validation.GraphValidator;
import com.acme.marketing.referral.ReferralPlan;
import com.acme.marketing.referral.ReferralPolicyValidator;
import com.acme.marketing.referral.ReferralRewardRule;
import java.time.Instant;
import java.util.*;

/**
 * 将首期纯线性图降级为类型化裂变计划，不访问数据库、网络或执行用户脚本。
 * 复用 SPI 业务校验；本类只负责图结构、字符串转换及保留归因范围，不构造第二套资格规则。
 */
public final class ReferralPlanCompiler {
    private static final Map<String, Set<String>> FIELDS = Map.of(
            "referral.start", Set.of("startsAt", "endsAt", "settlementEndsAt", "organizationId", "shopId"),
            "referral.bind", Set.of("attribution", "maxBindAgeSeconds", "inviteeScope"),
            "referral.qualify", Set.of("goalType", "qualificationWindowSeconds", "minNetAmountMinor", "currency", "observationSeconds", "lateArrivalGraceSeconds"),
            "referral.reward", Set.of("ruleId", "role", "mode", "threshold", "benefitDefinitionVersion", "skuVersion", "quantity", "perSubjectLimit", "campaignLimit"),
            "referral.end", Set.of());

    /** 固定归因算法；未知算法由 enum 严格拒绝，不回退为最后点击归因。 */
    public enum Attribution { FIRST_VALID_BIND }
    /** 首期只编译权威新客范围；实际会员口径由受信适配器和已审批版本提供。 */
    public enum InviteeScope { NEW_CUSTOMER }
    /** 冻结活动范围，不能由 runtime 从当前运营配置替换。 */
    public record Scope(String organizationId, String shopId) { }
    /** 绑定约束保留在编译产物中，后续服务必须在绑定事务校验。 */
    public record BindingPolicy(Attribution attribution, long maxBindAgeSeconds, InviteeScope inviteeScope) { }
    /** 完整可序列化载荷；definitionVersion 由现有 ArtifactBundle 的签名范围携带。 */
    public record CompiledReferralPlan(String definitionId, Scope scope, BindingPolicy binding, ReferralPlan policy) { }

    /**
     * 严格编译受支持的 v1 图。非法结构/字段/数字/日期/版本抛 IllegalArgumentException。
     * 图数组排列不定义执行顺序，沿有向边排序；注解不影响业务，变量首期禁止以免忽略动态规则。
     */
    public CompiledReferralPlan compile(GraphDefinition graph) {
        require(graph != null && graph.dialect() == Dialect.REFERRAL_POLICY, "REFERRAL_GRAPH_REQUIRED");
        require(graph.nodes().size() <= 500 && graph.edges().size() <= 1000, "REFERRAL_GRAPH_TOO_LARGE");
        require("1.0.0".equals(graph.dialectVersion()), "REFERRAL_DIALECT_VERSION_UNSUPPORTED");
        require(graph.variables().isEmpty(), "REFERRAL_VARIABLES_FORBIDDEN");
        var issues = new GraphValidator(ReferralNodeDefinitions.all(), GraphValidator.Limits.productionDefaults()).validate(graph);
        require(issues.isEmpty(), "REFERRAL_GRAPH_INVALID: " + issues);
        for (var node : graph.nodes()) {
            var fields = FIELDS.get(node.stableTypeId());
            require(fields != null && node.config().keySet().equals(fields), "REFERRAL_CONFIG_FIELDS: " + node.id());
            for (var value : node.config().values()) require(!value.isBlank(), "REFERRAL_CONFIG_BLANK: " + node.id());
        }
        var nodes = new HashMap<String, GraphNode>();
        graph.nodes().forEach(node -> nodes.put(node.id(), node));
        var next = new HashMap<String, String>();
        var incoming = new HashSet<String>();
        for (var edge : graph.edges()) {
            require(next.putIfAbsent(edge.sourceNodeId(), edge.targetNodeId()) == null
                    && incoming.add(edge.targetNodeId()), "REFERRAL_BRANCH_OR_MERGE_FORBIDDEN");
        }
        var starts = graph.nodes().stream().filter(n -> !incoming.contains(n.id())).toList();
        require(starts.size() == 1, "REFERRAL_SINGLE_ENTRY_REQUIRED");
        var ordered = new ArrayList<GraphNode>();
        var seen = new HashSet<String>();
        for (String id = starts.getFirst().id(); id != null; id = next.get(id)) {
            require(seen.add(id), "REFERRAL_CYCLE_FORBIDDEN");
            ordered.add(nodes.get(id));
        }
        require(ordered.size() == graph.nodes().size() && ordered.size() >= 5, "REFERRAL_COMPLETE_CHAIN_REQUIRED");
        require(type(ordered, 0, "referral.start") && type(ordered, 1, "referral.bind")
                && type(ordered, 2, "referral.qualify") && type(ordered, ordered.size() - 1, "referral.end"),
                "REFERRAL_NODE_ORDER_INVALID");
        var rewards = new ArrayList<ReferralRewardRule>();
        for (int i = 3; i < ordered.size() - 1; i++) {
            require(type(ordered, i, "referral.reward"), "REFERRAL_REWARD_CHAIN_REQUIRED");
            var c = ordered.get(i).config();
            rewards.add(new ReferralRewardRule(c.get("ruleId"), ReferralRewardRule.Role.valueOf(c.get("role")),
                    ReferralRewardRule.Mode.valueOf(c.get("mode")), number(c, "threshold"),
                    c.get("benefitDefinitionVersion"), c.get("skuVersion"), quantity(c),
                    number(c, "perSubjectLimit"), number(c, "campaignLimit")));
        }
        var start = ordered.get(0).config();
        var bind = ordered.get(1).config();
        var qualify = ordered.get(2).config();
        var binding = new BindingPolicy(Attribution.valueOf(bind.get("attribution")),
                number(bind, "maxBindAgeSeconds"), InviteeScope.valueOf(bind.get("inviteeScope")));
        require(binding.maxBindAgeSeconds() > 0, "REFERRAL_MAX_BIND_AGE_INVALID");
        var policy = new ReferralPlan(instant(start, "startsAt"), instant(start, "endsAt"),
                instant(start, "settlementEndsAt"), number(qualify, "qualificationWindowSeconds"),
                number(qualify, "observationSeconds"), number(qualify, "lateArrivalGraceSeconds"),
                ReferralPlan.GoalType.valueOf(qualify.get("goalType")), number(qualify, "minNetAmountMinor"),
                qualify.get("currency"), rewards);
        ReferralPolicyValidator.validate(policy);
        // 上限不可在运行时算到溢出，不能依赖适配器意外溢出来拒绝绑定。
        try { policy.endsAt().plusSeconds(binding.maxBindAgeSeconds()); }
        catch (RuntimeException invalid) { throw new IllegalArgumentException("REFERRAL_MAX_BIND_AGE_OVERFLOW", invalid); }
        return new CompiledReferralPlan(graph.definitionId(), new Scope(start.get("organizationId"), start.get("shopId")), binding, policy);
    }

    private static long number(Map<String, String> config, String key) {
        String value = config.get(key);
        require(value != null && value.matches("0|[1-9][0-9]*"), "REFERRAL_INTEGER_REQUIRED: " + key);
        try { return Long.parseLong(value); }
        catch (NumberFormatException invalid) { throw new IllegalArgumentException("REFERRAL_INTEGER_OVERFLOW: " + key, invalid); }
    }
    private static int quantity(Map<String, String> config) {
        long quantity = number(config, "quantity");
        require(quantity <= Integer.MAX_VALUE, "REFERRAL_QUANTITY_OVERFLOW");
        return (int) quantity;
    }
    private static Instant instant(Map<String, String> config, String key) {
        try { return Instant.parse(config.get(key)); }
        catch (RuntimeException invalid) { throw new IllegalArgumentException("REFERRAL_INSTANT_REQUIRED: " + key, invalid); }
    }
    private static boolean type(List<GraphNode> nodes, int index, String type) { return type.equals(nodes.get(index).stableTypeId()); }
    private static void require(boolean valid, String message) { if (!valid) throw new IllegalArgumentException(message); }
}
