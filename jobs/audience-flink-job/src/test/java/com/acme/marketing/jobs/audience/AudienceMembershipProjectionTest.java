package com.acme.marketing.jobs.audience;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;

class AudienceMembershipProjectionTest {
    private final AudienceMembershipProjection projection = new AudienceMembershipProjection();

    @Test
    void emitsDeterministicVersionedDeltasAndRejectsStaleProfiles() {
        var first = projection.apply(null,
                new AudienceProfileChange("t1", "s1", 4, Set.of("vip", "buyer"), 1_000));
        assertFalse(first.stale());
        assertEquals(2, first.deltas().size());
        assertEquals("buyer", first.deltas().getFirst().segmentId());
        assertEquals(1, first.deltas().getFirst().membershipVersion());
        assertEquals("vip", first.deltas().getLast().segmentId());
        assertEquals(2, first.deltas().getLast().membershipVersion());

        var second = projection.apply(first.state(),
                new AudienceProfileChange("t1", "s1", 5, Set.of("vip", "new"), 2_000));
        assertEquals(2, second.deltas().size());
        assertEquals("buyer", second.deltas().getFirst().segmentId());
        assertFalse(second.deltas().getFirst().member());
        assertEquals(3, second.deltas().getFirst().membershipVersion());
        assertEquals("new", second.deltas().getLast().segmentId());
        assertTrue(second.deltas().getLast().member());

        var stale = projection.apply(second.state(),
                new AudienceProfileChange("t1", "s1", 4, Set.of(), 3_000));
        assertTrue(stale.stale());
        assertTrue(stale.deltas().isEmpty());
        assertEquals(second.state(), stale.state());
    }
}
