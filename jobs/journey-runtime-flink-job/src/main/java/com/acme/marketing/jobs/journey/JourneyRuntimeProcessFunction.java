package com.acme.marketing.jobs.journey;

import com.acme.marketing.jobs.support.JsonCodec;
import com.acme.marketing.jobs.support.KafkaJobOutput;
import com.acme.marketing.contracts.event.JourneyEffectCommand;
import com.acme.marketing.journey.JourneyCommand;
import com.acme.marketing.journey.event.JourneyStateChangedEvent;
import java.util.Objects;
import org.apache.flink.api.common.functions.OpenContext;
import org.apache.flink.api.common.state.ValueState;
import org.apache.flink.api.common.state.ValueStateDescriptor;
import org.apache.flink.streaming.api.functions.KeyedProcessFunction;
import org.apache.flink.util.Collector;

public final class JourneyRuntimeProcessFunction
        extends KeyedProcessFunction<String, String, KafkaJobOutput> {
    private static final long serialVersionUID = 1L;
    private final JourneyJobReducer reducer;
    private static final int MAX_TIMER_RETRIES = 5;
    private transient ValueState<JourneyJobState> valueState;

    public JourneyRuntimeProcessFunction(JourneyPlanResolver plans) {
        this.reducer = new JourneyJobReducer(plans);
    }

    @Override
    public void open(OpenContext openContext) {
        valueState = getRuntimeContext().getState(
                new ValueStateDescriptor<>("journey-enrollment-v2", JourneyJobState.class));
    }

    @Override
    public void processElement(String input, Context context, Collector<KafkaJobOutput> output) throws Exception {
        String key = "invalid";
        try {
            JourneyJobInput message = JsonCodec.read(input, JourneyJobInput.class);
            key = message.partitionKey();
            JourneyJobState before = valueState.value();
            long now = context.timerService().currentProcessingTime();
            if (before != null && now >= before.expiresAtEpochMillis()) {
                expire(before, valueState, output, context);
                return;
            }
            JourneyJobReducer.Result result = reducer.apply(before, message, now);
            valueState.update(result.state());
            synchronizeTimers(before, result.state(), context);
            emit(key, message.signal().signalId(), result, output);
        } catch (JourneyExecutionPausedException paused) {
            throw paused;
        } catch (RuntimeException failure) {
            output.collect(KafkaJobOutput.deadLetter(key, input, failure));
        }
    }

    @Override
    public void onTimer(long timestamp, OnTimerContext context, Collector<KafkaJobOutput> output) throws Exception {
        JourneyJobState before = valueState.value();
        if (before == null) return;
        String key = before.snapshot().tenantId() + ':' + before.snapshot().enrollmentId();
        if (timestamp == before.expiresAtEpochMillis()) {
            expire(before, valueState, output, context);
            return;
        }
        if (timestamp != before.activeTimerAtEpochMillis()) return;
        try {
            JourneyJobReducer.Result result = reducer.fireTimer(before, timestamp);
            valueState.update(result.state());
            synchronizeTimers(before, result.state(), context);
            emit(key, "timer:" + before.activeTimerKey() + ':' + timestamp, result, output);
        } catch (JourneyExecutionPausedException paused) {
            long now = context.timerService().currentProcessingTime();
            long resumeAt = Math.min(now + 5_000, before.expiresAtEpochMillis() - 1);
            if (resumeAt <= now) {
                expire(before, valueState, output, context);
                return;
            }
            JourneyJobState pausedState = new JourneyJobState(before.planReference(), before.snapshot(),
                    before.activeTimerKey(), resumeAt, before.timerRetryCount(), before.expiresAtEpochMillis());
            valueState.update(pausedState);
            context.timerService().registerProcessingTimeTimer(resumeAt);
            output.collect(KafkaJobOutput.data(key, new ExecutionPaused("JOURNEY_EXECUTION_PAUSED",
                    before.snapshot().tenantId(), before.snapshot().enrollmentId(), resumeAt)));
        } catch (RuntimeException failure) {
            output.collect(KafkaJobOutput.deadLetter(key, "timer:" + timestamp, failure));
            long now = context.timerService().currentProcessingTime();
            int retry = before.timerRetryCount() + 1;
            if (retry <= MAX_TIMER_RETRIES) {
                long delay = 1_000L << (retry - 1);
                long retryAt = Math.min(now + delay, before.expiresAtEpochMillis() - 1);
                if (retryAt > now) {
                    JourneyJobState retryState = new JourneyJobState(before.planReference(), before.snapshot(),
                            before.activeTimerKey(), retryAt, retry, before.expiresAtEpochMillis());
                    valueState.update(retryState);
                    context.timerService().registerProcessingTimeTimer(retryAt);
                    return;
                }
            }
            JourneyJobState exhausted = new JourneyJobState(before.planReference(), before.snapshot(), "", 0,
                    retry, before.expiresAtEpochMillis());
            valueState.update(exhausted);
            output.collect(KafkaJobOutput.data(key, new TimerRetriesExhausted("JOURNEY_TIMER_RETRIES_EXHAUSTED",
                    before.snapshot().tenantId(), before.snapshot().enrollmentId(), before.activeTimerKey(), retry)));
        }
    }

    private static void emit(String key, String sourceSignalId,
            JourneyJobReducer.Result result, Collector<KafkaJobOutput> output) {
        var snapshot = result.state().snapshot();
        long projectedAt = snapshot.updatedAt().toEpochMilli();
        output.collect(KafkaJobOutput.data(key, new JourneyStateChangedEvent("JOURNEY_STATE_CHANGED",
                snapshot, sourceSignalId, result.transition().duplicate(), projectedAt,
                result.state().expiresAtEpochMillis())));
        for (JourneyCommand command : result.transition().commands()) {
            output.collect(KafkaJobOutput.data(key, new JourneyEffectCommand("JOURNEY_EFFECT_COMMAND",
                    snapshot.tenantId(), snapshot.enrollmentId(), snapshot.subjectToken(),
                    snapshot.journeyId(), snapshot.journeyVersion(), command.commandId(), command.type().name(),
                    command.nodeId(), command.payload(), projectedAt)));
        }
    }

    private static void expire(
            JourneyJobState state,
            ValueState<JourneyJobState> valueState,
            Collector<KafkaJobOutput> output,
            Context context) {
        valueState.clear();
        if (!state.activeTimerKey().isBlank()) {
            context.timerService().deleteProcessingTimeTimer(state.activeTimerAtEpochMillis());
        }
        String key = state.snapshot().tenantId() + ':' + state.snapshot().enrollmentId();
        output.collect(KafkaJobOutput.data(key, new StateExpired("JOURNEY_STATE_EXPIRED",
                state.snapshot().tenantId(), state.snapshot().enrollmentId(), state.expiresAtEpochMillis())));
    }

    private static void synchronizeTimers(
            JourneyJobState before, JourneyJobState after, Context context) {
        var timers = context.timerService();
        if (before != null) {
            if (before.activeTimerAtEpochMillis() > 0
                    && before.activeTimerAtEpochMillis() != after.activeTimerAtEpochMillis()) {
                timers.deleteProcessingTimeTimer(before.activeTimerAtEpochMillis());
            }
            if (before.expiresAtEpochMillis() != after.expiresAtEpochMillis()) {
                timers.deleteProcessingTimeTimer(before.expiresAtEpochMillis());
            }
        }
        if (after.activeTimerAtEpochMillis() > 0
                && (before == null || before.activeTimerAtEpochMillis() != after.activeTimerAtEpochMillis()
                || !Objects.equals(before.activeTimerKey(), after.activeTimerKey()))) {
            timers.registerProcessingTimeTimer(after.activeTimerAtEpochMillis());
        }
        if (before == null || before.expiresAtEpochMillis() != after.expiresAtEpochMillis()) {
            timers.registerProcessingTimeTimer(after.expiresAtEpochMillis());
        }
    }

    private record StateExpired(String eventType, String tenantId, String enrollmentId, long expiredAtEpochMillis) {
    }

    private record TimerRetriesExhausted(String eventType, String tenantId, String enrollmentId,
            String timerKey, int attempts) { }

    private record ExecutionPaused(String eventType, String tenantId, String enrollmentId,
            long resumeAtEpochMillis) { }
}
