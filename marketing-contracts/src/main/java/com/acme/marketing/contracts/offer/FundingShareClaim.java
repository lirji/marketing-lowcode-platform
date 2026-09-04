package com.acme.marketing.contracts.offer;

public record FundingShareClaim(String funderType, String funderId, String currency, long minorUnits) {
    public FundingShareClaim {
        funderType = OfferContractValidation.requireFundingReference(funderType, "funderType");
        funderId = OfferContractValidation.requireFundingReference(funderId, "funderId");
        currency = OfferContractValidation.requireCurrency(currency);
        if (minorUnits < 0) {
            throw new IllegalArgumentException("funding amount must not be negative");
        }
    }

}
