package com.acme.marketing.platform.isolation;

public record TenantQuota(
        int requestsPerSecond,
        int concurrentRequests,
        long cacheBytes,
        int maxCandidates,
        int maxJourneyTimers) {
    public TenantQuota {
        if (requestsPerSecond < 1 || concurrentRequests < 1 || cacheBytes < 1
                || maxCandidates < 1 || maxJourneyTimers < 1) {
            throw new IllegalArgumentException("all tenant quotas must be positive");
        }
    }
}
