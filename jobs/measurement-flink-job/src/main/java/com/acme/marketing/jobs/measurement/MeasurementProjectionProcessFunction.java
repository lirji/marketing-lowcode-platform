package com.acme.marketing.jobs.measurement;

import com.acme.marketing.contracts.event.MeasurementProjectionDelta;
import com.acme.marketing.jobs.support.JsonCodec;
import com.acme.marketing.jobs.support.KafkaJobOutput;
import org.apache.flink.api.common.functions.OpenContext;
import org.apache.flink.api.common.state.ValueState;
import org.apache.flink.api.common.state.ValueStateDescriptor;
import org.apache.flink.streaming.api.functions.KeyedProcessFunction;
import org.apache.flink.util.Collector;

public final class MeasurementProjectionProcessFunction
        extends KeyedProcessFunction<String, String, KafkaJobOutput> {
    private static final long serialVersionUID = 1L;
    private static final MeasurementReorderBuffer REORDER = new MeasurementReorderBuffer();
    private final long stateTtlMillis;
    private transient ValueState<MeasurementRuntimeState> valueState;

    public MeasurementProjectionProcessFunction(long stateTtlMillis) {
        if (stateTtlMillis < 1) throw new IllegalArgumentException("measurement state TTL must be positive");
        this.stateTtlMillis = stateTtlMillis;
    }

    @Override
    public void open(OpenContext openContext) {
        valueState = getRuntimeContext().getState(
                new ValueStateDescriptor<>("measurement-fact-v2", MeasurementRuntimeState.class));
    }

    @Override
    public void processElement(String input, Context context, Collector<KafkaJobOutput> output) throws Exception {
        String key = "invalid";
        try {
            MeasurementFactMessage message = JsonCodec.read(input, MeasurementFactMessage.class);
            key = message.partitionKey();
            MeasurementRuntimeState before = valueState.value();
            long now = context.timerService().currentProcessingTime();
            if (before != null && now >= before.expiresAtEpochMillis()) {
                valueState.clear();
                before = null;
            }
            MeasurementReorderBuffer.Result result = REORDER.apply(before, message, now, stateTtlMillis);
            valueState.update(result.state());
            if (before != null) context.timerService().deleteProcessingTimeTimer(before.expiresAtEpochMillis());
            context.timerService().registerProcessingTimeTimer(result.state().expiresAtEpochMillis());
            result.deltas().forEach(delta -> output.collect(KafkaJobOutput.data(delta.deltaId(), delta)));
            result.rejected().forEach(rejected -> output.collect(KafkaJobOutput.deadLetter(message.partitionKey(),
                    JsonCodec.write(rejected.message()), new IllegalArgumentException(rejected.reason()))));
            if (result.outcome() != MeasurementReorderBuffer.Outcome.APPLIED) {
                output.collect(KafkaJobOutput.data(key, new ProjectionReceipt(
                        result.outcome() == MeasurementReorderBuffer.Outcome.PARKED
                                ? "MEASUREMENT_CORRECTION_PARKED" : "MEASUREMENT_DUPLICATE_IGNORED",
                        message.tenantId(), message.eventId(),
                        result.state().projection() == null ? 0 : result.state().projection().revision())));
            }
        } catch (RuntimeException failure) {
            output.collect(KafkaJobOutput.deadLetter(key, input, failure));
        }
    }

    @Override
    public void onTimer(long timestamp, OnTimerContext context, Collector<KafkaJobOutput> output) throws Exception {
        MeasurementRuntimeState state = valueState.value();
        if (state == null || state.expiresAtEpochMillis() != timestamp) return;
        valueState.clear();
        state.pending().values().forEach(message -> output.collect(KafkaJobOutput.deadLetter(
                message.partitionKey(), JsonCodec.write(message),
                new IllegalStateException("parked correction expired before its parent arrived"))));
        if (state.projection() != null) {
            MeasurementProjectionState projection = state.projection();
            String key = projection.tenantId() + ':' + projection.rootEventId();
            output.collect(KafkaJobOutput.data(key, new ProjectionReceipt(
                    "MEASUREMENT_DEDUP_STATE_EXPIRED", projection.tenantId(), projection.activeEventId(),
                    projection.revision())));
        }
    }

    private record ProjectionReceipt(String eventType, String tenantId, String eventId, long revision) { }
}
