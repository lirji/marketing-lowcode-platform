package com.acme.marketing.decision.model;

public record CartLine(
        String lineId,
        String skuId,
        String shopId,
        String categoryId,
        String brandId,
        int quantity,
        Money unitPrice,
        Money floorUnitPrice,
        boolean shippingLine) {
    public CartLine {
        lineId = require(lineId, "lineId");
        skuId = require(skuId, "skuId");
        shopId = require(shopId, "shopId");
        categoryId = categoryId == null ? "" : categoryId;
        brandId = brandId == null ? "" : brandId;
        if (quantity <= 0 || quantity > 100_000) {
            throw new IllegalArgumentException("quantity is outside limits");
        }
        if (unitPrice == null || floorUnitPrice == null || unitPrice.isNegative() || floorUnitPrice.isNegative()) {
            throw new IllegalArgumentException("line prices must be non-negative");
        }
        if (!unitPrice.currency().equals(floorUnitPrice.currency()) || floorUnitPrice.compareTo(unitPrice) > 0) {
            throw new IllegalArgumentException("floor price is invalid");
        }
    }

    public Money originalTotal() {
        return unitPrice.multiply(quantity);
    }

    public Money floorTotal() {
        return floorUnitPrice.multiply(quantity);
    }

    private static String require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }
}
