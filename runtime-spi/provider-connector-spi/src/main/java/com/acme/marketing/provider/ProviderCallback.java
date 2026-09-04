package com.acme.marketing.provider;

import java.time.Instant;
import java.util.Map;

public record ProviderCallback(
        String providerEventId,
        String providerRequestId,
        DeliveryStatus status,
        Instant occurredAt,
        Map<String, String> attributes) {
    public ProviderCallback {
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }

    public enum DeliveryStatus {
        ACCEPTED, SENT, DELIVERED, FAILED, CLICKED, UNSUBSCRIBED
    }
}
