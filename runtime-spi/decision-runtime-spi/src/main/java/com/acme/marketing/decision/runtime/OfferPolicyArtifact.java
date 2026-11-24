package com.acme.marketing.decision.runtime;

import java.util.List;

/** Versioned portable payload loaded by the online decision runtime. */
public record OfferPolicyArtifact(List<OfferPolicy> policies) {
    public static final String LEGACY_ABI = "marketing-offer-policy/1";
    public static final String SCOPED_ABI = "marketing-offer-policy/2";
    public OfferPolicyArtifact {
        policies = List.copyOf(policies == null ? List.of() : policies);
    }

    /** 含币种的制品须由支持商品逐行限域的运行时解释。 */
    public String abi() {
        return policies.stream().anyMatch(policy -> !policy.pricingCurrency().isEmpty()) ? SCOPED_ABI : LEGACY_ABI;
    }

    public static boolean supports(String abi) {
        return LEGACY_ABI.equals(abi) || SCOPED_ABI.equals(abi);
    }
}
