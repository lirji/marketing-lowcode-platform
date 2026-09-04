package com.acme.marketing.jobs.journey;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.acme.marketing.journey.EnrollmentSnapshot;
import com.acme.marketing.journey.JourneyNode;
import com.acme.marketing.journey.JourneyPlan;
import com.acme.marketing.jobs.support.JsonCodec;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JourneyJobReducerTest {
    private final JourneyJobReducer reducer = new JourneyJobReducer((tenantId, execution, now, requireCurrent) ->
            plan(execution.journeyVersion()));

    @Test
    void pinsPlanPreservesTimerOnDuplicatesAndEmitsIdempotentEffect() {
        JourneyJobInput.PlanReference execution = execution(1);
        JourneyJobInput start = new JourneyJobInput("t1", "e1", "subject", execution,
                signal(JourneyJobInput.SignalPayload.Type.START, "start-1", 1_000));
        JourneyJobReducer.Result waiting = reducer.apply(null, start, 1_000);
        assertEquals(EnrollmentSnapshot.Status.WAITING, waiting.state().snapshot().status());
        assertEquals("e1:wait", waiting.state().activeTimerKey());
        assertEquals(6_000, waiting.state().activeTimerAtEpochMillis());

        JourneyJobReducer.Result duplicate = reducer.apply(waiting.state(), start, 2_000);
        assertTrue(duplicate.transition().duplicate());
        assertEquals(6_000, duplicate.state().activeTimerAtEpochMillis());

        JourneyJobReducer.Result completed = reducer.fireTimer(duplicate.state(), 6_000);
        assertEquals(EnrollmentSnapshot.Status.COMPLETED, completed.state().snapshot().status());
        assertEquals(1, completed.transition().commands().size());
        assertEquals("", completed.state().activeTimerKey());
        assertFalse(completed.transition().duplicate());

        JourneyJobInput wrongPlan = new JourneyJobInput("t1", "e1", "subject", execution(2),
                signal(JourneyJobInput.SignalPayload.Type.START, "start-2", 7_000));
        assertThrows(IllegalArgumentException.class,
                () -> reducer.apply(waiting.state(), wrongPlan, 7_000));
    }

    @Test
    void journeyTransportRoundTripsVersionedPlan() {
        JourneyJobInput input = new JourneyJobInput("t1", "e1", "subject", execution(3),
                signal(JourneyJobInput.SignalPayload.Type.START, "start", 1_000));
        JourneyJobInput restored = JsonCodec.read(JsonCodec.write(input), JourneyJobInput.class);
        assertEquals(input, restored);
    }

    private static JourneyPlan plan(long version) {
        Map<String, JourneyNode> nodes = Map.of(
                "start", new JourneyNode("start", JourneyNode.Type.TRIGGER, Map.of(), Map.of("next", "wait")),
                "wait", new JourneyNode("wait", JourneyNode.Type.WAIT_TIMER,
                        Map.of("delaySeconds", "5"), Map.of("elapsed", "send")),
                "send", new JourneyNode("send", JourneyNode.Type.SEND,
                        Map.of("template", "welcome"), Map.of("next", "end")),
                "end", new JourneyNode("end", JourneyNode.Type.END, Map.of(), Map.of()));
        return new JourneyPlan("journey", version, "start", nodes, 20, 3, Duration.ofSeconds(60));
    }

    private static JourneyJobInput.PlanReference execution(long version) {
        return new JourneyJobInput.PlanReference("journey-artifact-" + version, version, version,
                "journey", version);
    }

    private static JourneyJobInput.SignalPayload signal(
            JourneyJobInput.SignalPayload.Type type, String id, long occurredAt) {
        return new JourneyJobInput.SignalPayload(type, id, "", "", occurredAt, Map.of());
    }
}
