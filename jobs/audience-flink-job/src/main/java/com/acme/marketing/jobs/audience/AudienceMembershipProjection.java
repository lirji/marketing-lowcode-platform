package com.acme.marketing.jobs.audience;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class AudienceMembershipProjection {
    public Result apply(AudienceMembershipState current, AudienceProfileChange change) {
        AudienceMembershipState state = current == null ? AudienceMembershipState.empty() : current;
        if (change.profileVersion() <= state.sourceVersion()) {
            return new Result(state, List.of(), true);
        }
        Set<String> removed = new HashSet<>(state.segmentIds());
        removed.removeAll(change.segmentIds());
        Set<String> added = new HashSet<>(change.segmentIds());
        added.removeAll(state.segmentIds());

        List<MembershipChange> ordered = new ArrayList<>();
        removed.forEach(segment -> ordered.add(new MembershipChange(segment, false)));
        added.forEach(segment -> ordered.add(new MembershipChange(segment, true)));
        ordered.sort(Comparator.comparing(MembershipChange::segmentId)
                .thenComparing(MembershipChange::member));

        long version = state.membershipVersion();
        List<AudienceMembershipDelta> deltas = new ArrayList<>(ordered.size());
        for (MembershipChange item : ordered) {
            version++;
            deltas.add(new AudienceMembershipDelta("AUDIENCE_MEMBERSHIP_DELTA", change.tenantId(),
                    change.subjectToken(), item.segmentId(), item.member(), version,
                    change.profileVersion(), change.occurredAtEpochMillis()));
        }
        return new Result(new AudienceMembershipState(change.profileVersion(), version, change.segmentIds()),
                deltas, false);
    }

    private record MembershipChange(String segmentId, boolean member) {
    }

    public record Result(AudienceMembershipState state, List<AudienceMembershipDelta> deltas, boolean stale) {
        public Result {
            deltas = List.copyOf(deltas);
        }
    }
}
