package com.acme.marketing.decision.model;

import com.acme.marketing.contracts.offer.OfferContractValidation;

public record FundingRule(String funderType, String funderId, int basisPoints) {
    public FundingRule {
        funderType = OfferContractValidation.requireFundingReference(funderType, "funderType");
        funderId = OfferContractValidation.requireFundingReference(funderId, "funderId");
        if (basisPoints < 0 || basisPoints > 10_000) {
            throw new IllegalArgumentException("funding rule is invalid");
        }
    }
}
