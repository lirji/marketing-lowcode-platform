package com.acme.marketing.provider;

import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

public record ProviderRequest(
        String tenantId,
        String contactKey,
        String channel,
        String recipientToken,
        String templateVersion,
        URI endpoint,
        Map<String, Object> variables,
        Instant requestedAt) {
    public ProviderRequest {
        tenantId = require(tenantId, "tenantId");
        contactKey = require(contactKey, "contactKey");
        channel = require(channel, "channel");
        recipientToken = require(recipientToken, "recipientToken");
        templateVersion = require(templateVersion, "templateVersion");
        variables = Map.copyOf(Objects.requireNonNull(variables, "variables"));
        requestedAt = Objects.requireNonNull(requestedAt, "requestedAt");
    }

    private static String require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }
}
