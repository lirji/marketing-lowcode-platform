package com.acme.marketing.platform.event;

import com.acme.marketing.platform.identity.TenantId;
import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

public record EventEnvelope<T>(
        String specVersion,
        String id,
        String type,
        URI source,
        Instant occurredAt,
        Instant ingestedAt,
        TenantId tenantId,
        String subjectToken,
        String schemaVersion,
        Map<String, String> extensions,
        T data) {
    public EventEnvelope {
        specVersion = requireText(specVersion, "specVersion");
        id = requireText(id, "id");
        type = requireText(type, "type");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(ingestedAt, "ingestedAt");
        Objects.requireNonNull(tenantId, "tenantId");
        subjectToken = requireText(subjectToken, "subjectToken");
        schemaVersion = requireText(schemaVersion, "schemaVersion");
        extensions = Map.copyOf(extensions == null ? Map.of() : extensions);
        Objects.requireNonNull(data, "data");
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }
}
