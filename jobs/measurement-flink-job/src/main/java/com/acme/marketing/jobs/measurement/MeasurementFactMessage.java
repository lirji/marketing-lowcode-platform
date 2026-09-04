package com.acme.marketing.jobs.measurement;

import com.acme.marketing.contracts.event.MarketingFact;
import java.time.Instant;
import java.util.Map;

public record MeasurementFactMessage(
        String eventId,
        String tenantId,
        MarketingFact.Type type,
        String businessKey,
        String subjectToken,
        String occurredAt,
        String ingestedAt,
        String schemaVersion,
        Map<String, String> attributes,
        String correctionOf,
        String correctionRootId) {
    public MeasurementFactMessage {
        if (eventId == null || eventId.isBlank() || tenantId == null || tenantId.isBlank() || type == null
                || businessKey == null || businessKey.isBlank() || occurredAt == null || ingestedAt == null
                || schemaVersion == null || schemaVersion.isBlank()) {
            throw new IllegalArgumentException("measurement fact is incomplete");
        }
        subjectToken = subjectToken == null ? "" : subjectToken;
        attributes = Map.copyOf(attributes == null ? Map.of() : attributes);
        correctionOf = correctionOf == null ? "" : correctionOf;
        correctionRootId = correctionRootId == null ? "" : correctionRootId;
        Instant.parse(occurredAt);
        Instant.parse(ingestedAt);
        if (type == MarketingFact.Type.EXPOSURE
                && !"true".equalsIgnoreCase(attributes.get("actualAction"))) {
            throw new IllegalArgumentException("assignment without actual action is not an exposure");
        }
        requireNonNegativeAmount(attributes, "revenueMinor");
        requireNonNegativeAmount(attributes, "costMinor");
        if (correctionOf.isBlank() && !eventId.equals(correctionRootId)) {
            throw new IllegalArgumentException("original fact must identify itself as correction root");
        }
        if (!correctionOf.isBlank() && (correctionRootId.isBlank() || correctionOf.equals(eventId))) {
            throw new IllegalArgumentException("correction must identify its immutable root and prior event");
        }
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

    public String rootEventId() {
        return correctionRootId;
    }

    public String partitionKey() {
        return tenantId + ':' + rootEventId();
    }

    public long occurredAtEpochMillis() {
        return Instant.parse(occurredAt).toEpochMilli();
    }

    public long ingestedAtEpochMillis() {
        return Instant.parse(ingestedAt).toEpochMilli();
    }
}
