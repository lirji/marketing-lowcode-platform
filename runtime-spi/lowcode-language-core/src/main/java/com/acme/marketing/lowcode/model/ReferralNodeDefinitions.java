package com.acme.marketing.lowcode.model;

import com.acme.marketing.platform.crypto.Digests;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 裂变首期节点元数据的单一目录；不注册脚本或外部副作用，编译器另做严格配置和拓扑检查。 */
public final class ReferralNodeDefinitions {
    private ReferralNodeDefinitions() { }

    /** 返回不可变的五种 v1 纯节点；目录摘要仅标识内建节点契约，不能替代产物签名或发布许可。 */
    public static List<NodeDefinition> all() {
        return List.of(node("referral.start", false, true), node("referral.bind", true, true),
                node("referral.qualify", true, true), node("referral.reward", true, true),
                node("referral.end", true, false));
    }

    private static NodeDefinition node(String id, boolean input, boolean output) {
        return new NodeDefinition(id, "1.0.0", Set.of(Dialect.REFERRAL_POLICY), "referral",
                input ? Map.of("in", "flow") : Map.of(), output ? Map.of("next", "flow") : Map.of(),
                "schema://nodes/" + id, "schema://ui/" + id, NullSemantics.ERROR,
                MissingSemantics.ERROR_REQUIRED_FIELD, SideEffect.NONE, 10, List.of(),
                "sha256:" + Digests.sha256Hex("builtin-referral-node-contract/1|" + id + "|" + input + "|" + output),
                Map.of(), Set.of("definition:write"));
    }
}
