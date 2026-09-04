package com.acme.marketing.decision.model;

import java.math.BigDecimal;
import java.math.RoundingMode;

public record Money(String currency, long minorUnits) implements Comparable<Money> {
    public Money {
        if (currency == null || !currency.matches("[A-Z]{3}")) {
            throw new IllegalArgumentException("currency must be an ISO-4217 uppercase code");
        }
    }

    public static Money zero(String currency) {
        return new Money(currency, 0);
    }

    public Money add(Money other) {
        requireCurrency(other);
        return new Money(currency, Math.addExact(minorUnits, other.minorUnits));
    }

    public Money subtract(Money other) {
        requireCurrency(other);
        return new Money(currency, Math.subtractExact(minorUnits, other.minorUnits));
    }

    public Money multiply(long multiplier) {
        return new Money(currency, Math.multiplyExact(minorUnits, multiplier));
    }

    public Money basisPoints(int basisPoints, RoundingMode roundingMode) {
        if (basisPoints < 0 || basisPoints > 10_000) {
            throw new IllegalArgumentException("basisPoints must be in [0,10000]");
        }
        long result = BigDecimal.valueOf(minorUnits)
                .multiply(BigDecimal.valueOf(basisPoints))
                .divide(BigDecimal.valueOf(10_000), 0, roundingMode)
                .longValueExact();
        return new Money(currency, result);
    }

    public Money min(Money other) {
        requireCurrency(other);
        return minorUnits <= other.minorUnits ? this : other;
    }

    public Money max(Money other) {
        requireCurrency(other);
        return minorUnits >= other.minorUnits ? this : other;
    }

    public boolean isNegative() {
        return minorUnits < 0;
    }

    @Override
    public int compareTo(Money other) {
        requireCurrency(other);
        return Long.compare(minorUnits, other.minorUnits);
    }

    private void requireCurrency(Money other) {
        if (!currency.equals(other.currency)) {
            throw new IllegalArgumentException("currency mismatch: " + currency + " vs " + other.currency);
        }
    }
}
