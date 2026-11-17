package com.acme.marketing.jobs.journey;

import com.acme.marketing.contracts.event.JourneyBundleExecutionProbeMessage;
import com.acme.marketing.contracts.event.JourneyBundleExecutionAckEvent;
import com.acme.marketing.contracts.release.ReleaseBundleProtocolError;
import java.time.Instant;

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

/** 业务转换与只读探针分别处理，所有实际状态及输出故障继续由checkpoint恢复。 */
public final class JourneyRuntimeProcessFunction
        extends KeyedProcessFunction<String, String, KafkaJobOutput> {
    private static final long serialVersionUID = 1L;
    private final JourneyJobReducer reducer;
    private final JourneyPlanResolver plans;
    private final JourneyExecutionProbeHandler probes;
    private static final int MAX_TIMER_RETRIES = 5;
    private transient ValueState<JourneyJobState> valueState;
    private transient ValueState<String> bundleParticipationState;

    /** 原调用方缺省关闭探针签名，维持既有业务执行。 */
    public JourneyRuntimeProcessFunction(JourneyPlanResolver plans) {
        this(plans, JourneyExecutionProbeHandler.disabled());
    }

    /** 探针与业务执行共享一个只读解析器，保持连接和缓存预算不翻倍。 */
    JourneyRuntimeProcessFunction(JourneyPlanResolver plans, JourneyExecutionProbeHandler probes) {
        this.plans = plans; this.probes = probes;
        this.reducer = new JourneyJobReducer(plans);
    }

    /** 恢复运行身份，并保持原受管状态描述符和类型。 */
    @Override
    public void open(OpenContext openContext) {
        probes.open(getRuntimeContext());
        valueState = getRuntimeContext().getState(
                new ValueStateDescriptor<>("journey-enrollment-v2", JourneyJobState.class));
        // 原状态的名称/类型保持不变；字符串凭证使用独立描述符，旧savepoint中此状态为空。
        bundleParticipationState = getRuntimeContext().getState(
                new ValueStateDescriptor<>("journey-bundle-participation-v1", String.class));
    }

    /** 输入校验/探针在状态操作之前，避免坏探针影响已有旅程。 */
    @Override
    public void processElement(String input, Context context, Collector<KafkaJobOutput> output) throws Exception {
        JourneyRuntimeMessage envelope;
        try { envelope = Objects.requireNonNull(JsonCodec.read(input, JourneyRuntimeMessage.class)); }
        catch (RuntimeException malformed) {
            output.collect(KafkaJobOutput.deadLetter("invalid", input, malformed)); return;
        }
        if (envelope.probe() != null) {
            processProbe(envelope.probe(), input, context, output); return;
        }
        JourneyJobInput message = envelope.signal();
        String key = message.partitionKey();
        JourneyJobState before = valueState.value();
        var storedParticipation = storedParticipation();
        long now = context.timerService().currentProcessingTime();
        if (before != null && now >= before.expiresAtEpochMillis()) {
            expire(before, valueState, output, context); return;
        }
        var participation = before == null ? message.bundleParticipation() : storedParticipation;
        JourneyJobReducer.Result result;
        try {
            if (before == null && storedParticipation != null)
                throw ReleaseBundleProtocolError.PARTICIPATION_CONSUMED.conflict();
            result = reducer.apply(before, message, now, participation);
        } catch (JourneyInfrastructureException | JourneyExecutionPausedException unavailable) {
            throw unavailable;
        } catch (RuntimeException invalid) {
            output.collect(KafkaJobOutput.deadLetter(key, input, invalid)); return;
        }
        // 校验与纯转换之后才写状态；此后的任意写入/输出失败必须恢复checkpoint，不能提交半份状态。
        if (before == null && participation != null) bundleParticipationState.update(JsonCodec.write(participation));
        valueState.update(result.state());
        synchronizeTimers(before, result.state(), context);
        emit(key, message.signal().signalId(), result, output);
    }

    /** 纯探测先于所有旅程状态读取；失败不重启业务实例，也不触发任何业务效果。 */
    private void processProbe(JourneyBundleExecutionProbeMessage message,
            String input, Context context, Collector<KafkaJobOutput> output) {
        KafkaJobOutput result;
        try {
            var ack = probes.acknowledge(plans, message.probe(), Instant.ofEpochMilli(context.timerService().currentProcessingTime()));
            result = KafkaJobOutput.data(message.partitionKey(), new JourneyBundleExecutionAckEvent(
                    JourneyBundleExecutionAckEvent.EVENT_TYPE, ack));
        } catch (RuntimeException unavailable) {
            // 只读探测失败由持久发布任务重试；不向DLT泄漏数据库/密钥异常详情。
            result = KafkaJobOutput.deadLetter(message.partitionKey(), input,
                    ReleaseBundleProtocolError.EXECUTION_PROBE_UNAVAILABLE.conflict());
        }
        // 输出失败必须传播，不能把一次collect失败捕获后再collect并推进checkpoint。
        output.collect(result);
    }

    private com.acme.marketing.contracts.release.ReleaseBundleParticipation storedParticipation() throws Exception {
        String content = bundleParticipationState.value();
        if (content == null) return null;
        try {
            var value = JsonCodec.read(content, com.acme.marketing.contracts.release.ReleaseBundleParticipation.class);
            if (value == null || value.signature().isEmpty()) throw new IllegalArgumentException(ReleaseBundleProtocolError.PARTICIPATION_INVALID.message());
            return value;
        } catch (RuntimeException corrupt) { throw new JourneyInfrastructureException(corrupt); }
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
        var participation = storedParticipation();
        JourneyJobReducer.Result result;
        try {
            result = reducer.fireTimer(before, timestamp, participation);
        } catch (JourneyInfrastructureException unavailable) {
            // 基础设施故障必须中断 checkpoint 推进，恢复后重放输入/定时器，不能作为坏消息跳过。
            throw unavailable;
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
            return;
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
            return;
        }
        valueState.update(result.state());
        synchronizeTimers(before, result.state(), context);
        emit(key, "timer:" + before.activeTimerKey() + ':' + timestamp, result, output);
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
        // 只清理旧旅程状态，独立参与凭证留作墓碑，禁止迟到START重开同一组合参与。
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
