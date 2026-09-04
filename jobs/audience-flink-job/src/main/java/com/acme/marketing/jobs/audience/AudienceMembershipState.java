package com.acme.marketing.jobs.audience;

import java.util.Set;

public record AudienceMembershipState(long sourceVersion, long membershipVersion, Set<String> segmentIds) {
    public AudienceMembershipState {
        segmentIds = Set.copyOf(segmentIds == null ? Set.of() : segmentIds);
    }

    public static AudienceMembershipState empty() {
        return new AudienceMembershipState(0, 0, Set.of());
    }
}
