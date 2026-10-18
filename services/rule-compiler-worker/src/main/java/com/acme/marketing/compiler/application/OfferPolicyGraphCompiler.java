package com.acme.marketing.compiler.application;

import com.acme.marketing.decision.runtime.OfferPolicyArtifact;
import com.acme.marketing.lowcode.model.GraphDefinition;

/** 编译 worker 的包内适配入口；实际策略语义由共享纯内核唯一维护。 */
final class OfferPolicyGraphCompiler {
    OfferPolicyArtifact compile(GraphDefinition graph) {
        return new com.acme.marketing.decision.runtime.OfferPolicyGraphCompiler().compile(graph);
    }
}
