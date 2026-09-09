package com.acme.marketing.control.domain;

import com.acme.marketing.platform.error.ConflictException;
import java.time.Instant;

/** 活动类型创建时冻结；类型本身不授予审批或发布资格。 */
public final class Campaign {
    private final String tenantId;
    private final String id;
    private final String name;
    private final String objective;
    private Status status;
    private final Type campaignType;
    private final Instant createdAt;
    private Instant updatedAt;

    public Campaign(String tenantId, String id, String name, String objective, Status status,
            Instant createdAt, Instant updatedAt) {
        this(tenantId, id, name, objective, status, createdAt, updatedAt, Type.STANDARD);
    }

    /** 旧活动缺省STANDARD，新裂变活动显式声明REFERRAL。 */
    public Campaign(String tenantId, String id, String name, String objective, Status status,
            Instant createdAt, Instant updatedAt, Type campaignType) {
        this.campaignType = java.util.Objects.requireNonNull(campaignType);
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

    /** 返回创建时固定类型，禁止由名称或页面路由推导。 */
    public Type campaignType() { return campaignType; }

    public enum Type { STANDARD, REFERRAL }

    public enum Status { DRAFT, IN_REVIEW, APPROVED, ACTIVE, PAUSED, ENDED }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }
}
