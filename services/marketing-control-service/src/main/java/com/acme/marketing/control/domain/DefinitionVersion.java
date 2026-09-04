package com.acme.marketing.control.domain;

import com.acme.marketing.lowcode.model.Dialect;
import com.acme.marketing.platform.error.ConflictException;
import java.time.Instant;

public record DefinitionVersion(
        String tenantId,
        String definitionId,
        String campaignId,
        long version,
        Dialect dialect,
        String semanticHash,
        Status status,
        String createdBy,
        Instant createdAt,
        Instant updatedAt) {
    public DefinitionVersion {
        if (version < 1 || tenantId == null || definitionId == null || campaignId == null || dialect == null
                || semanticHash == null || status == null || createdBy == null || createdAt == null || updatedAt == null) {
            throw new IllegalArgumentException("definition version is incomplete");
        }
    }

    public DefinitionVersion validated(Instant now) {
        return transition(Status.DRAFT, Status.VALIDATED, now);
    }

    public DefinitionVersion submitted(Instant now) {
        return transition(Status.VALIDATED, Status.IN_REVIEW, now);
    }

    public DefinitionVersion approved(Instant now) {
        return transition(Status.IN_REVIEW, Status.APPROVED, now);
    }

    public DefinitionVersion released(Instant now) {
        return transition(Status.APPROVED, Status.RELEASED, now);
    }

    private DefinitionVersion transition(Status expected, Status target, Instant now) {
        if (status != expected) {
            throw new ConflictException("DEFINITION_STATE_CONFLICT",
                    "definition must be " + expected + " before transition to " + target);
        }
        return new DefinitionVersion(tenantId, definitionId, campaignId, version, dialect,
                semanticHash, target, createdBy, createdAt, now);
    }

    public enum Status { DRAFT, VALIDATED, IN_REVIEW, APPROVED, RELEASED, REJECTED }
}
