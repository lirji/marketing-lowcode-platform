package com.acme.marketing.decision.runtime;

import java.util.List;

/** Versioned portable payload loaded by the online decision runtime. */
public record OfferPolicyArtifact(List<OfferPolicy> policies) {
    public OfferPolicyArtifact {
        policies = List.copyOf(policies == null ? List.of() : policies);
    }
}
