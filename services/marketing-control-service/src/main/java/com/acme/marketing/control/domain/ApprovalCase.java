package com.acme.marketing.control.domain;

import com.acme.marketing.platform.error.ConflictException;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

public final class ApprovalCase {
    private final String id;
    private final String definitionId;
    private final long definitionVersion;
    private final String submittedBy;
    private final Set<Role> requiredRoles;
    private final EnumMap<Role, String> approvals;
    private Status status;
    private Instant updatedAt;

    public ApprovalCase(String id, String definitionId, long definitionVersion, String submittedBy,
            Set<Role> requiredRoles, Map<Role, String> approvals, Status status, Instant updatedAt) {
        this.id = id;
        this.definitionId = definitionId;
        this.definitionVersion = definitionVersion;
        this.submittedBy = submittedBy;
        this.requiredRoles = Set.copyOf(requiredRoles);
        this.approvals = new EnumMap<>(Role.class);
        this.approvals.putAll(approvals);
        this.status = status;
        this.updatedAt = updatedAt;
    }

    public void approve(Role role, String actorId, Instant now) {
        requireCanDecide(role, actorId);
        approvals.putIfAbsent(role, actorId);
        if (approvals.keySet().containsAll(requiredRoles)) {
            status = Status.APPROVED;
        }
        updatedAt = now;
    }

    public void reject(Role role, String actorId, Instant now) {
        requireCanDecide(role, actorId);
        status = Status.REJECTED;
        updatedAt = now;
    }

    private void requireCanDecide(Role role, String actorId) {
        if (status != Status.OPEN || !requiredRoles.contains(role)) {
            throw new ConflictException("APPROVAL_NOT_OPEN", "approval role is not open");
        }
        if (submittedBy.equals(actorId)) {
            throw new ConflictException("FOUR_EYES_VIOLATION", "submitter cannot decide the same definition");
        }
        if (approvals.containsValue(actorId) && !actorId.equals(approvals.get(role))) {
            throw new ConflictException("APPROVER_SEPARATION_VIOLATION", "one actor cannot satisfy multiple roles");
        }
    }

    public String id() { return id; }
    public String definitionId() { return definitionId; }
    public long definitionVersion() { return definitionVersion; }
    public String submittedBy() { return submittedBy; }
    public Set<Role> requiredRoles() { return requiredRoles; }
    public Map<Role, String> approvals() { return Map.copyOf(approvals); }
    public Status status() { return status; }
    public Instant updatedAt() { return updatedAt; }

    public enum Role { BUSINESS, FINANCE, COMPLIANCE, MERCHANT }
    public enum Status { OPEN, APPROVED, REJECTED }
}
