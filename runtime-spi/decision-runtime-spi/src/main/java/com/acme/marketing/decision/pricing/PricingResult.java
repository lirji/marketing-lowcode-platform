package com.acme.marketing.decision.pricing;

import com.acme.marketing.decision.model.Money;
import com.acme.marketing.decision.model.PriceStage;
import com.acme.marketing.decision.model.PromotionKind;
import java.util.List;
import java.util.Map;

public record PricingResult(
        Money original,
        Money payable,
        Map<String, Money> linePayables,
        List<AppliedOffer> appliedOffers,
        List<String> reasonCodes) {
    public PricingResult {
        linePayables = Map.copyOf(linePayables);
        appliedOffers = List.copyOf(appliedOffers);
        reasonCodes = List.copyOf(reasonCodes);
        if (!original.currency().equals(payable.currency()) || payable.isNegative() || payable.compareTo(original) > 0) {
            throw new IllegalArgumentException("pricing result totals are invalid");
        }
        long lineTotal = linePayables.values().stream().mapToLong(Money::minorUnits).sum();
        if (lineTotal != payable.minorUnits()) {
            throw new IllegalArgumentException("line payable sum does not equal order payable");
        }
    }

    public Money totalDiscount() {
        return original.subtract(payable);
    }

    public record AppliedOffer(
            String offerId,
            String benefitDefinitionVersion,
            PriceStage stage,
            PromotionKind kind,
            Money discount,
            Map<String, Money> lineDiscounts,
            List<FundingAllocation> funding,
            String reasonCode) {
        public AppliedOffer {
            lineDiscounts = Map.copyOf(lineDiscounts);
            funding = List.copyOf(funding);
        }
    }

    public record FundingAllocation(String funderType, String funderId, Money amount) {
    }
}
