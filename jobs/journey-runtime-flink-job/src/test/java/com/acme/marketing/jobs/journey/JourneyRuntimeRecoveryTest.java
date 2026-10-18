package com.acme.marketing.jobs.journey;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.acme.marketing.jobs.support.JsonCodec;
import com.acme.marketing.jobs.support.KafkaJobOutput;
import com.acme.marketing.journey.JourneyNode;
import com.acme.marketing.journey.JourneyPlan;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.flink.api.common.functions.RuntimeContext;
import org.apache.flink.api.common.state.ValueState;
import org.apache.flink.streaming.api.TimerService;
import org.apache.flink.util.Collector;
import org.junit.jupiter.api.Test;

class JourneyRuntimeRecoveryTest {
    @Test
    void invalidDatabaseConfigurationFailsTheJobInsteadOfDeadLetteringInputs() {
        String name = "JOURNEY_DB_CONNECT_TIMEOUT_MS";
        String previous = System.getProperty(name);
        var key = com.acme.marketing.platform.crypto.Ed25519.generateKeyPair().getPublic();
        String encoded = java.util.Base64.getEncoder().encodeToString(key.getEncoded());
        var resolver = new JdbcJourneyPlanResolver("jdbc:mysql://localhost/unused", "test", "", "key", encoded, "",
                "key", encoded, "", "local", "cell", "main");
        try {
            System.setProperty(name, "0");
            assertThrows(JourneyInfrastructureException.class, () -> resolver.resolve("t", start().planReference(), java.time.Instant.now(), true));
        } finally {
            if (previous == null) System.clearProperty(name); else System.setProperty(name, previous);
        }
    }
    @Test
    @SuppressWarnings("unchecked")
    void databaseFailureEscapesWithoutStateOrDeadLetterAndSameInputCanReplay() throws Exception {
        AtomicBoolean unavailable = new AtomicBoolean(true);
        var function = new JourneyRuntimeProcessFunction((tenant, reference, now, current) -> {
            if (unavailable.get()) throw new JourneyInfrastructureException(new IllegalStateException("db offline"));
            return plan();
        });
        ValueState<JourneyJobState> state = mock(ValueState.class);
        RuntimeContext runtime = mock(RuntimeContext.class);
        when(runtime.<JourneyJobState>getState(any(org.apache.flink.api.common.state.ValueStateDescriptor.class))).thenReturn(state);
        function.setRuntimeContext(runtime);
        function.open(null);
        var context = mock(JourneyRuntimeProcessFunction.Context.class);
        TimerService timers = mock(TimerService.class);
        when(context.timerService()).thenReturn(timers);
        when(timers.currentProcessingTime()).thenReturn(1000L);
        Collector<KafkaJobOutput> output = mock(Collector.class);
        String input = JsonCodec.write(start());
        assertThrows(JourneyInfrastructureException.class, () -> function.processElement(input, context, output));
        verify(state, never()).update(any());
        verifyNoInteractions(output);
        unavailable.set(false);
        function.processElement(input, context, output);
        verify(state).update(any());
        verify(output, atLeastOnce()).collect(any());
        // 格式错误仍是坏消息，不应无限触发作业重启。
        clearInvocations(output);
        function.processElement("invalid-json", context, output);
        verify(output).collect(any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void infrastructureFailureDoesNotConsumeTimerRetryBudget() throws Exception {
        var waiting = new JourneyJobReducer((tenant, reference, now, current) -> plan()).apply(null, start(), 1000).state();
        var function = new JourneyRuntimeProcessFunction((tenant, reference, now, current) -> {
            throw new JourneyInfrastructureException(new IllegalStateException("db offline"));
        });
        ValueState<JourneyJobState> state = mock(ValueState.class);
        when(state.value()).thenReturn(waiting);
        RuntimeContext runtime = mock(RuntimeContext.class);
        when(runtime.<JourneyJobState>getState(any(org.apache.flink.api.common.state.ValueStateDescriptor.class))).thenReturn(state);
        function.setRuntimeContext(runtime);
        function.open(null);
        Collector<KafkaJobOutput> output = mock(Collector.class);
        var context = mock(JourneyRuntimeProcessFunction.OnTimerContext.class);
        assertThrows(JourneyInfrastructureException.class,
                () -> function.onTimer(waiting.activeTimerAtEpochMillis(), context, output));
        verify(state, never()).update(any());
        verifyNoInteractions(output, context);
    }

    private static JourneyJobInput start() {
        return new JourneyJobInput("t", "enrollment", "subject",
                new JourneyJobInput.PlanReference("artifact", 1, 1, "journey", 1),
                new JourneyJobInput.SignalPayload(JourneyJobInput.SignalPayload.Type.START, "start", "", "", 1000, Map.of()));
    }

    private static JourneyPlan plan() {
        return new JourneyPlan("journey", 1, "start", Map.of(
                "start", new JourneyNode("start", JourneyNode.Type.TRIGGER, Map.of(), Map.of("next", "wait")),
                "wait", new JourneyNode("wait", JourneyNode.Type.WAIT_TIMER, Map.of("delaySeconds", "5"), Map.of("elapsed", "end")),
                "end", new JourneyNode("end", JourneyNode.Type.END, Map.of(), Map.of())), 20, 3, Duration.ofSeconds(60));
    }
}
