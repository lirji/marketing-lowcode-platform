package com.acme.marketing.control.domain;

import com.acme.marketing.platform.error.ConflictException;
import java.time.Instant;

public final class Campaign {
    private final String tenantId;
    private final String id;
    private final String name;
    private final String objective;
    private Status status;
    private final Instant createdAt;
    private Instant updatedAt;

    public Campaign(String tenantId, String id, String name, String objective, Status status,
            Instant createdAt, Instant updatedAt) {
        this.tenantId = required(tenantId, "tenantId");
        this.id = required(id, "id");
        this.name = required(name, "name");
        this.objective = required(objective, "objective");
        this.status = status;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public void submit(Instant now) {
        transition(Status.DRAFT, Status.IN_REVIEW, now);
    }

    public void approve(Instant now) {
        transition(Status.IN_REVIEW, Status.APPROVED, now);
    }

    public void activate(Instant now) {
        transition(Status.APPROVED, Status.ACTIVE, now);
    }

    public void pause(Instant now) {
        transition(Status.ACTIVE, Status.PAUSED, now);
    }

    private void transition(Status expected, Status target, Instant now) {
        if (status != expected) {
            throw new ConflictException("CAMPAIGN_STATE_CONFLICT",
                    "campaign must be " + expected + " before transition to " + target);
        }
        status = target;
        updatedAt = now;
    }

    public String tenantId() { return tenantId; }
    public String id() { return id; }
    public String name() { return name; }
    public String objective() { return objective; }
    public Status status() { return status; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }

    public enum Status { DRAFT, IN_REVIEW, APPROVED, ACTIVE, PAUSED, ENDED }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }
}
