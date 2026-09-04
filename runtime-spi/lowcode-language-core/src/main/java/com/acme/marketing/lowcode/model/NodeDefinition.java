package com.acme.marketing.lowcode.model;

import java.util.List;
import java.util.Map;
import java.util.Set;

public record NodeDefinition(
        String stableTypeId,
        String semanticVersion,
        Set<Dialect> dialects,
        String runtimeTarget,
        Map<String, String> inputPorts,
        Map<String, String> outputPorts,
        String configSchemaRef,
        String uiSchemaRef,
        NullSemantics nullSemantics,
        MissingSemantics missingSemantics,
        SideEffect sideEffect,
        int costWeight,
        List<RequiredField> requiredFields,
        String compilerPluginDigest,
        Map<String, String> migrators,
        Set<String> permissions) {
    public NodeDefinition {
        stableTypeId = requirePattern(stableTypeId, "[a-z][a-z0-9.-]{2,127}", "stableTypeId");
        semanticVersion = requirePattern(semanticVersion, "[0-9]+\\.[0-9]+\\.[0-9]+", "semanticVersion");
        dialects = Set.copyOf(dialects);
        if (dialects.isEmpty()) {
            throw new IllegalArgumentException("at least one dialect is required");
        }
        runtimeTarget = requirePattern(runtimeTarget, "[a-z][a-z0-9-]{2,63}", "runtimeTarget");
        inputPorts = Map.copyOf(inputPorts == null ? Map.of() : inputPorts);
        outputPorts = Map.copyOf(outputPorts == null ? Map.of() : outputPorts);
        configSchemaRef = requireText(configSchemaRef, "configSchemaRef");
        uiSchemaRef = requireText(uiSchemaRef, "uiSchemaRef");
        if (nullSemantics == null || missingSemantics == null || sideEffect == null) {
            throw new IllegalArgumentException("node semantics are required");
        }
        if (costWeight < 0 || costWeight > 10_000) {
            throw new IllegalArgumentException("costWeight is outside limits");
        }
        requiredFields = List.copyOf(requiredFields == null ? List.of() : requiredFields);
        compilerPluginDigest = requirePattern(compilerPluginDigest, "sha256:[a-f0-9]{64}", "compilerPluginDigest");
        migrators = Map.copyOf(migrators == null ? Map.of() : migrators);
        permissions = Set.copyOf(permissions == null ? Set.of() : permissions);
    }

    public String versionedTypeId() {
        return stableTypeId + "@" + semanticVersion;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }

    private static String requirePattern(String value, String pattern, String name) {
        requireText(value, name);
        if (!value.matches(pattern)) {
            throw new IllegalArgumentException(name + " has invalid format");
        }
        return value;
    }
}
