package com.acme.marketing.control.application;

import com.acme.marketing.lowcode.model.Dialect;
import com.acme.marketing.lowcode.model.MissingSemantics;
import com.acme.marketing.lowcode.model.NodeDefinition;
import com.acme.marketing.lowcode.model.NullSemantics;
import com.acme.marketing.lowcode.model.SideEffect;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public final class DefaultNodeRegistry {
    private static final String PLUGIN = "sha256:" + "0".repeat(64);
    private final List<NodeDefinition> definitions = definitions();

    public List<NodeDefinition> all() {
        return definitions;
    }

    private static List<NodeDefinition> definitions() {
        List<NodeDefinition> result = new ArrayList<>();
        result.add(node("offer.start", Set.of(Dialect.OFFER_DECISION_DAG), Map.of(), Map.of("next", "flow"), SideEffect.NONE));
        result.add(node("offer.condition", Set.of(Dialect.OFFER_DECISION_DAG), Map.of("in", "flow"),
                Map.of("true", "flow", "false", "flow"), SideEffect.NONE));
        result.add(node("offer.percentage", Set.of(Dialect.OFFER_DECISION_DAG), Map.of("in", "flow"),
                Map.of("next", "flow"), SideEffect.NONE));
        result.add(node("offer.fixed", Set.of(Dialect.OFFER_DECISION_DAG), Map.of("in", "flow"),
                Map.of("next", "flow"), SideEffect.NONE));
        result.add(node("offer.end", Set.of(Dialect.OFFER_DECISION_DAG), Map.of("in", "flow"), Map.of(), SideEffect.NONE));
        result.add(node("audience.source", Set.of(Dialect.AUDIENCE_EXPRESSION), Map.of(), Map.of("next", "flow"), SideEffect.NONE));
        result.add(node("audience.predicate", Set.of(Dialect.AUDIENCE_EXPRESSION), Map.of("in", "flow"),
                Map.of("next", "flow"), SideEffect.NONE));
        result.add(node("audience.result", Set.of(Dialect.AUDIENCE_EXPRESSION), Map.of("in", "flow"), Map.of(), SideEffect.NONE));
        result.add(node("journey.trigger", Set.of(Dialect.JOURNEY_STATE_MACHINE), Map.of(), Map.of("next", "flow"), SideEffect.NONE));
        result.add(node("journey.condition", Set.of(Dialect.JOURNEY_STATE_MACHINE), Map.of("in", "flow"),
                Map.of("true", "flow", "false", "flow"), SideEffect.NONE));
        result.add(node("journey.wait-event", Set.of(Dialect.JOURNEY_STATE_MACHINE), Map.of("in", "flow"),
                Map.of("matched", "flow"), SideEffect.NONE));
        result.add(node("journey.wait-timer", Set.of(Dialect.JOURNEY_STATE_MACHINE), Map.of("in", "flow"),
                Map.of("elapsed", "flow"), SideEffect.NONE));
        result.add(node("journey.send", Set.of(Dialect.JOURNEY_STATE_MACHINE), Map.of("in", "flow"),
                Map.of("next", "flow"), SideEffect.CONTACT));
        result.add(node("journey.grant", Set.of(Dialect.JOURNEY_STATE_MACHINE), Map.of("in", "flow"),
                Map.of("next", "flow"), SideEffect.GRANT));
        result.add(node("journey.webhook", Set.of(Dialect.JOURNEY_STATE_MACHINE), Map.of("in", "flow"),
                Map.of("next", "flow"), SideEffect.WEBHOOK));
        result.add(node("journey.end", Set.of(Dialect.JOURNEY_STATE_MACHINE), Map.of("in", "flow"), Map.of(), SideEffect.NONE));
        // 编译器和节点查询共享同一纯节点目录，避免控制面误将奖励节点标为即时副作用。
        result.addAll(com.acme.marketing.lowcode.model.ReferralNodeDefinitions.all());
        return List.copyOf(result);
    }

    private static NodeDefinition node(String id, Set<Dialect> dialects, Map<String, String> inputs,
            Map<String, String> outputs, SideEffect sideEffect) {
        return new NodeDefinition(id, "1.0.0", dialects, "java-runtime", inputs, outputs,
                "schema://nodes/" + id, "schema://ui/" + id,
                NullSemantics.NO_MATCH, MissingSemantics.NO_MATCH, sideEffect, 10,
                List.of(), PLUGIN, Map.of(), Set.of("definition:write"));
    }
}
