package com.acme.marketing.jobs.audience;

import java.util.Set;

public record AudienceProfileChange(
        String tenantId,
        String subjectToken,
        long profileVersion,
        Set<String> segmentIds,
        long occurredAtEpochMillis) {
    public AudienceProfileChange {
        if (tenantId == null || tenantId.isBlank() || subjectToken == null || subjectToken.isBlank()
                || profileVersion < 1 || occurredAtEpochMillis < 1) {
            throw new IllegalArgumentException("audience profile change is invalid");
        }
        segmentIds = Set.copyOf(segmentIds == null ? Set.of() : segmentIds);
        if (segmentIds.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException("segment id is blank");
        }
    }

    public String partitionKey() {
        return tenantId + ':' + subjectToken;
    }
}
