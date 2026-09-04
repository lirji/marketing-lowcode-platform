package com.acme.marketing.decision.model;

import com.acme.marketing.platform.crypto.Digests;
import java.text.Normalizer;
import java.util.Comparator;
import java.util.List;

public record Cart(String currency, List<CartLine> lines) {
    public Cart {
        if (currency == null || !currency.matches("[A-Z]{3}")) {
            throw new IllegalArgumentException("currency is invalid");
        }
        lines = List.copyOf(lines);
        if (lines.isEmpty() || lines.size() > 100) {
            throw new IllegalArgumentException("cart must contain between 1 and 100 lines");
        }
        if (lines.stream().anyMatch(line -> !line.unitPrice().currency().equals(currency))) {
            throw new IllegalArgumentException("cart line currency mismatch");
        }
        if (lines.stream().map(CartLine::lineId).distinct().count() != lines.size()) {
            throw new IllegalArgumentException("cart line ids must be unique");
        }
    }

    public Money originalTotal() {
        return lines.stream().map(CartLine::originalTotal).reduce(Money.zero(currency), Money::add);
    }

    public String digest() {
        StringBuilder canonical = new StringBuilder(currency).append('\n');
        lines.stream().sorted(Comparator.comparing(CartLine::lineId)).forEach(line -> canonical
                .append(normalize(line.lineId())).append('|').append(normalize(line.skuId())).append('|')
                .append(normalize(line.shopId())).append('|').append(normalize(line.categoryId())).append('|')
                .append(normalize(line.brandId())).append('|').append(line.quantity()).append('|')
                .append(line.unitPrice().minorUnits()).append('|').append(line.floorUnitPrice().minorUnits())
                .append('|').append(line.shippingLine()).append('\n'));
        return "sha256:" + Digests.sha256Hex(canonical.toString());
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFC);
    }
}
