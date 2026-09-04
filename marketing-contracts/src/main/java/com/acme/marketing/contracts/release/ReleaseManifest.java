package com.acme.marketing.contracts.release;

import com.acme.marketing.platform.identity.TenantId;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public record ReleaseManifest(
        String manifestId,
        TenantId tenantId,
        String environment,
        String cell,
        String runtime,
        String namespace,
        long generation,
        long stableGeneration,
        List<Long> canaryGenerations,
        List<Long> retainedGenerations,
        List<ArtifactReference> artifacts,
        Map<String, String> schemaVersions,
        int canaryBasisPoints,
        Instant activationAt,
        Instant expiresAt,
        String createdBy,
        List<String> approvalCaseIds,
        Instant createdAt,
        String signature) {
    public ReleaseManifest {
        manifestId = require(manifestId, "manifestId");
        if (tenantId == null) {
            throw new IllegalArgumentException("tenantId is required");
        }
        environment = require(environment, "environment");
        cell = require(cell, "cell");
        runtime = require(runtime, "runtime");
        namespace = require(namespace, "namespace");
        if (generation <= 0 || stableGeneration < 0 || stableGeneration > generation) {
            throw new IllegalArgumentException("manifest generations are invalid");
        }
        canaryGenerations = List.copyOf(canaryGenerations == null ? List.of() : canaryGenerations);
        retainedGenerations = List.copyOf(retainedGenerations == null ? List.of() : retainedGenerations);
        artifacts = List.copyOf(artifacts);
        if (artifacts.isEmpty()) {
            throw new IllegalArgumentException("manifest requires an artifact closure");
        }
        schemaVersions = Map.copyOf(schemaVersions == null ? Map.of() : schemaVersions);
        if (canaryBasisPoints < 0 || canaryBasisPoints > 10_000) {
            throw new IllegalArgumentException("canaryBasisPoints is invalid");
        }
        if (activationAt == null || expiresAt == null || createdAt == null || !expiresAt.isAfter(createdAt)) {
            throw new IllegalArgumentException("manifest timestamps are invalid");
        }
        createdBy = require(createdBy, "createdBy");
        approvalCaseIds = List.copyOf(approvalCaseIds);
        signature = signature == null ? "" : signature;
    }

    public String slotKey() {
        return String.join(":", tenantId.value(), environment, cell, runtime, namespace);
    }

    private static String require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }
}
