package com.acme.marketing.jobs.audience;

public record AudienceMembershipDelta(
        String eventType,
        String tenantId,
        String subjectToken,
        String segmentId,
        boolean member,
        long membershipVersion,
        long sourceVersion,
        long occurredAtEpochMillis) {
    public AudienceMembershipDelta {
        if (!"AUDIENCE_MEMBERSHIP_DELTA".equals(eventType)) {
            throw new IllegalArgumentException("invalid audience event type");
        }
    }

    public String outputKey() {
        return tenantId + ':' + segmentId + ':' + subjectToken;
    }
}
