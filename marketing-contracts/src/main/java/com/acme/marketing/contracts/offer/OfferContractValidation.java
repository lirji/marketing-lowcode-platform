package com.acme.marketing.contracts.offer;

/** Shared lexical constraints used by compiler, runtime model and signed token contracts. */
public final class OfferContractValidation {
    private OfferContractValidation() { }

    public static String requireOfferReference(String value, String name) {
        return require(value, name, "[a-zA-Z0-9_.:@/-]{1,256}");
    }

    public static String requireFundingReference(String value, String name) {
        return require(value, name, "[a-zA-Z0-9_-]{1,128}");
    }

    public static String requireCurrency(String value) {
        return require(value, "currency", "[A-Z]{3}");
    }

    private static String require(String value, String name, String pattern) {
        if (value == null || !value.matches(pattern)) {
            throw new IllegalArgumentException(name + " is invalid");
        }
        return value;
    }
}
