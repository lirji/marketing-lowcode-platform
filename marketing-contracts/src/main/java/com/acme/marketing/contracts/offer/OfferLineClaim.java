package com.acme.marketing.contracts.offer;

import java.util.List;

public record OfferLineClaim(
        String offerId,
        String benefitDefinitionVersion,
        String currency,
        long minorUnits,
        int quantity,
        List<FundingShareClaim> fundingShares) {
    public OfferLineClaim {
        offerId = OfferContractValidation.requireOfferReference(offerId, "offerId");
        benefitDefinitionVersion = OfferContractValidation.requireOfferReference(
                benefitDefinitionVersion, "benefitDefinitionVersion");
        currency = OfferContractValidation.requireCurrency(currency);
        String expectedCurrency = currency;
        if (minorUnits < 0 || quantity <= 0) {
            throw new IllegalArgumentException("offer amount and quantity are invalid");
        }
        fundingShares = List.copyOf(fundingShares == null ? List.of() : fundingShares);
        long funded = fundingShares.stream().map(FundingShareClaim::minorUnits).reduce(0L, Math::addExact);
        if (minorUnits > 0 && fundingShares.isEmpty()) {
            throw new IllegalArgumentException("positive monetary offers require funding shares");
        }
        if (!fundingShares.isEmpty() && funded != minorUnits) {
            throw new IllegalArgumentException("funding shares must equal offer amount");
        }
        if (fundingShares.stream().anyMatch(share -> !share.currency().equals(expectedCurrency))) {
            throw new IllegalArgumentException("funding currency mismatch");
        }
    }

}
