package com.acme.marketing.contracts.release;

import com.acme.marketing.platform.identity.TenantId;
import java.time.Instant;

/** Signed control-plane decision that is distinct from warming an immutable generation. */
public record ActivationDirective(
        String directiveId,
        TenantId tenantId,
        String manifestId,
        String environment,
        String cell,
        String runtime,
        String namespace,
        long activationSequence,
        long generation,
        long stableGeneration,
        int canaryBasisPoints,
        String manifestSignature,
        Instant activatedAt,
        Instant expiresAt,
        String activatedBy,
        String signatureKeyId,
        String signature) {
    public ActivationDirective {
        require(directiveId, "directiveId");
        if (tenantId == null) throw new IllegalArgumentException("tenantId is required");
        require(manifestId, "manifestId");
        require(environment, "environment");
        require(cell, "cell");
        require(runtime, "runtime");
        require(namespace, "namespace");
        require(manifestSignature, "manifestSignature");
        require(activatedBy, "activatedBy");
        require(signatureKeyId, "signatureKeyId");
        if (activationSequence < 1 || generation < 1 || stableGeneration < 0
                || canaryBasisPoints < 0 || canaryBasisPoints > 10_000
                || activatedAt == null || expiresAt == null || !expiresAt.isAfter(activatedAt)) {
            throw new IllegalArgumentException("activation directive is invalid");
        }
        signature = signature == null ? "" : signature;
    }

    public String slotKey() {
        return String.join(":", tenantId.value(), environment, cell, runtime, namespace);
    }

    private static void require(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
    }
}
