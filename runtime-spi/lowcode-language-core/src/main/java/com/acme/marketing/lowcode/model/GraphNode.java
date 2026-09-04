package com.acme.marketing.lowcode.model;

import java.util.Map;

public record GraphNode(String id, String stableTypeId, String semanticVersion, Map<String, String> config) {
    public GraphNode {
        id = require(id, "id");
        stableTypeId = require(stableTypeId, "stableTypeId");
        semanticVersion = require(semanticVersion, "semanticVersion");
        config = Map.copyOf(config == null ? Map.of() : config);
    }

    public String versionedTypeId() {
        return stableTypeId + "@" + semanticVersion;
    }

    private static String require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }
}
