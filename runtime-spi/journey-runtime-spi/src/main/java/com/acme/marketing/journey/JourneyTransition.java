package com.acme.marketing.journey;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record JourneyTransition(
        EnrollmentSnapshot snapshot,
        List<JourneyCommand> commands,
        Map<String, Instant> timers,
        boolean duplicate) {
    public JourneyTransition {
        commands = List.copyOf(commands);
        timers = Map.copyOf(timers);
    }
}
