package com.acme.marketing.contracts.event;

import com.acme.marketing.platform.identity.TenantId;
import java.time.Instant;
import java.util.Map;

public record MarketingFact(
        String eventId,
        TenantId tenantId,
        Type type,
        String businessKey,
        String subjectToken,
        Instant occurredAt,
        Instant ingestedAt,
        String schemaVersion,
        Map<String, String> attributes,
        String correctionOf) {
    public MarketingFact {
        if (eventId == null || eventId.isBlank() || tenantId == null || type == null
                || businessKey == null || businessKey.isBlank() || occurredAt == null || ingestedAt == null) {
            throw new IllegalArgumentException("marketing fact identity is incomplete");
        }
        subjectToken = subjectToken == null ? "" : subjectToken;
        schemaVersion = schemaVersion == null ? "1.0.0" : schemaVersion;
        attributes = Map.copyOf(attributes == null ? Map.of() : attributes);
        requireNonNegativeAmount(attributes, "revenueMinor");
        requireNonNegativeAmount(attributes, "costMinor");
        correctionOf = correctionOf == null ? "" : correctionOf;
    }

    private static void requireNonNegativeAmount(Map<String, String> attributes, String key) {
        String value = attributes.get(key);
        if (value == null || value.isBlank()) return;
        try {
            if (Long.parseLong(value) < 0) throw new IllegalArgumentException(key + " must be non-negative");
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException(key + " must be a 64-bit integer", invalid);
        }
    }

    public enum Type {
        DECISION,
        OFFER_SHOWN,
        PROMOTION_APPLIED,
        BENEFIT_GRANTED,
        CONTACT_SENT,
        CONTACT_DELIVERED,
        CLICK,
        CONVERSION,
        REFUND,
        COST,
        EXPOSURE
    }
}
