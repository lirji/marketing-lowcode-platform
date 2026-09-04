package com.acme.marketing.journey;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class JourneyRuntimeTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void waitResumesOnceAndProducesStableCommand() {
        JourneyPlan plan = plan(1);
        EnrollmentSnapshot start = EnrollmentSnapshot.start("tenant-a", "enrollment-1", "subject-1", plan, NOW);
        JourneyRuntime runtime = new JourneyRuntime();

        JourneyTransition waiting = runtime.advance(plan, start, new JourneySignal.Start("s-1", NOW));
        assertEquals(EnrollmentSnapshot.Status.WAITING, waiting.snapshot().status());

        JourneySignal.Event paid = new JourneySignal.Event("event-1", "ORDER_PAID", NOW.plusSeconds(10), Map.of());
        JourneyTransition completed = runtime.advance(plan, waiting.snapshot(), paid);
        assertEquals(EnrollmentSnapshot.Status.COMPLETED, completed.snapshot().status());
        assertEquals(1, completed.commands().size());

        JourneyTransition duplicate = runtime.advance(plan, completed.snapshot(), paid);
        assertTrue(duplicate.duplicate());
        assertEquals(0, duplicate.commands().size());
    }

    @Test
    void enrollmentCannotSilentlyMoveToNewVersion() {
        EnrollmentSnapshot enrollment = EnrollmentSnapshot.start("tenant-a", "e-1", "s-1", plan(1), NOW);
        assertThrows(IllegalArgumentException.class,
                () -> new JourneyRuntime().advance(plan(2), enrollment, new JourneySignal.Start("s", NOW)));
    }

    @Test
    void repeatedEffectGetsDistinctDeterministicCommandIds() {
        Map<String, JourneyNode> nodes = Map.of(
                "start", new JourneyNode("start", JourneyNode.Type.TRIGGER, Map.of(), Map.of("next", "send")),
                "send", new JourneyNode("send", JourneyNode.Type.SEND,
                        Map.of("template", "reminder-v1"), Map.of("next", "repeat")),
                "repeat", new JourneyNode("repeat", JourneyNode.Type.REPEAT, Map.of(),
                        Map.of("repeat", "send", "exit", "end")),
                "end", new JourneyNode("end", JourneyNode.Type.END, Map.of(), Map.of()));
        JourneyPlan repeated = new JourneyPlan("journey-repeat", 1, "start", nodes, 20, 1,
                Duration.ofDays(1));
        EnrollmentSnapshot enrollment = EnrollmentSnapshot.start("tenant-a", "e-repeat", "subject", repeated, NOW);

        JourneyTransition first = new JourneyRuntime().advance(repeated, enrollment,
                new JourneySignal.Start("signal-repeat", NOW));
        JourneyTransition retried = new JourneyRuntime().advance(repeated, enrollment,
                new JourneySignal.Start("signal-repeat", NOW));

        assertEquals(2, first.commands().size());
        assertEquals(2, first.commands().stream().map(JourneyCommand::commandId).distinct().count());
        assertEquals(first.commands(), retried.commands());
    }

    private static JourneyPlan plan(long version) {
        Map<String, JourneyNode> nodes = Map.of(
                "start", new JourneyNode("start", JourneyNode.Type.TRIGGER, Map.of(), Map.of("next", "wait")),
                "wait", new JourneyNode("wait", JourneyNode.Type.WAIT_EVENT,
                        Map.of("eventType", "ORDER_PAID"), Map.of("matched", "send")),
                "send", new JourneyNode("send", JourneyNode.Type.SEND,
                        Map.of("template", "receipt-v1"), Map.of("next", "end")),
                "end", new JourneyNode("end", JourneyNode.Type.END, Map.of(), Map.of()));
        return new JourneyPlan("journey-1", version, "start", nodes, 20, 3, Duration.ofDays(30));
    }
}
