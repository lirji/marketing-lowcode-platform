package com.acme.marketing.decision.model;

import com.acme.marketing.contracts.offer.OfferContractValidation;
import java.util.List;
import java.util.Set;

public record OfferCandidate(
        String offerId,
        String benefitDefinitionVersion,
        PriceStage stage,
        PromotionFormula formula,
        Set<String> eligibleLineIds,
        String stackGroup,
        HitPolicy hitPolicy,
        int maxGroupHits,
        int priority,
        Set<String> incompatibleOfferIds,
        List<FundingRule> fundingRules,
        String reasonCode) {
    public OfferCandidate {
        offerId = OfferContractValidation.requireOfferReference(offerId, "offerId");
        benefitDefinitionVersion = OfferContractValidation.requireOfferReference(
                benefitDefinitionVersion, "benefitDefinitionVersion");
        if (stage == null || formula == null) {
            throw new IllegalArgumentException("offer candidate identity is incomplete");
        }
        eligibleLineIds = Set.copyOf(eligibleLineIds == null ? Set.of() : eligibleLineIds);
        stackGroup = stackGroup == null || stackGroup.isBlank() ? "default" : stackGroup;
        if (hitPolicy == null || maxGroupHits < 0) {
            throw new IllegalArgumentException("offer hit policy is invalid");
        }
        if (hitPolicy == HitPolicy.MAX_N && maxGroupHits == 0) {
            throw new IllegalArgumentException("MAX_N requires a positive maxGroupHits");
        }
        incompatibleOfferIds = Set.copyOf(incompatibleOfferIds == null ? Set.of() : incompatibleOfferIds);
        incompatibleOfferIds.forEach(id -> OfferContractValidation.requireOfferReference(id,
                "incompatibleOfferId"));
        fundingRules = List.copyOf(fundingRules == null ? List.of() : fundingRules);
        if (!fundingRules.isEmpty() && fundingRules.stream().mapToInt(FundingRule::basisPoints).sum() != 10_000) {
            throw new IllegalArgumentException("funding basis points must sum to 10000");
        }
        if (fundingRules.stream().map(rule -> rule.funderType() + ':' + rule.funderId()).distinct().count()
                != fundingRules.size()) {
            throw new IllegalArgumentException("funding rules must have unique funder keys");
        }
        reasonCode = reasonCode == null || reasonCode.isBlank() ? "OFFER_MATCHED" : reasonCode;
    }
}
