package com.acme.marketing.decision.model;

import java.math.BigDecimal;
import java.math.RoundingMode;

public record PromotionFormula(
        PromotionKind kind,
        long thresholdMinor,
        long valueMinor,
        int discountBasisPoints,
        long capMinor,
        int maxApplications) {
    public PromotionFormula {
        if (kind == null || thresholdMinor < 0 || valueMinor < 0 || capMinor < 0
                || discountBasisPoints < 0 || discountBasisPoints > 10_000 || maxApplications < 0) {
            throw new IllegalArgumentException("promotion formula is invalid");
        }
        if (kind == PromotionKind.EVERY_FULL_REDUCTION && thresholdMinor == 0) {
            throw new IllegalArgumentException("every-full promotion needs a positive threshold");
        }
    }

    public boolean applicable(long eligibleSubtotal, int eligibleQuantity) {
        return eligibleSubtotal >= thresholdMinor && eligibleQuantity > 0;
    }

    public long discount(long eligibleSubtotal, int eligibleQuantity) {
        if (!applicable(eligibleSubtotal, eligibleQuantity)) {
            return 0;
        }
        long result = switch (kind) {
            case FIXED_OFF, FREE_SHIPPING, CASHBACK, RED_PACKET -> valueMinor;
            case PERCENT_OFF -> BigDecimal.valueOf(eligibleSubtotal)
                    .multiply(BigDecimal.valueOf(discountBasisPoints))
                    .divide(BigDecimal.valueOf(10_000), 0, RoundingMode.DOWN)
                    .longValueExact();
            case EVERY_FULL_REDUCTION -> {
                long applications = eligibleSubtotal / thresholdMinor;
                if (maxApplications > 0) {
                    applications = Math.min(applications, maxApplications);
                }
                yield Math.multiplyExact(applications, valueMinor);
            }
            case FIXED_TOTAL -> Math.max(0, eligibleSubtotal - valueMinor);
            case GIFT, POINTS, DRAW_CHANCE -> 0;
        };
        return capMinor > 0 ? Math.min(result, capMinor) : result;
    }

    public boolean createsNonMonetaryBenefit() {
        return kind == PromotionKind.GIFT || kind == PromotionKind.POINTS || kind == PromotionKind.DRAW_CHANCE;
    }
}
