package com.acme.marketing.contracts.release;

import com.acme.marketing.platform.identity.TenantId;
import java.time.Instant;

/** Signed, monotonic emergency state. It remains effective until a newer signed directive replaces it. */
public record KillSwitchDirective(
        String directiveId,
        TenantId tenantId,
        String namespace,
        long switchSequence,
        boolean enabled,
        String reason,
        Instant activatedAt,
        String activatedBy,
        String signatureKeyId,
        String signature) {
    public KillSwitchDirective {
        require(directiveId, "directiveId");
        if (tenantId == null) throw new IllegalArgumentException("tenantId is required");
        require(namespace, "namespace");
        require(reason, "reason");
        require(activatedBy, "activatedBy");
        require(signatureKeyId, "signatureKeyId");
        if (switchSequence < 1 || activatedAt == null) {
            throw new IllegalArgumentException("kill switch directive is invalid");
        }
        signature = signature == null ? "" : signature;
    }

    public String streamKey() { return tenantId.value() + ':' + namespace; }

    private static void require(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
    }
}
