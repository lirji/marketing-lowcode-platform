package com.acme.marketing.jobs.journey;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.acme.marketing.contracts.release.ReleaseBundleParticipation;
import com.acme.marketing.jobs.support.JsonCodec;
import com.acme.marketing.jobs.support.KafkaJobOutput;
import com.acme.marketing.journey.JourneyPlan;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.apache.flink.api.common.functions.RuntimeContext;
import org.apache.flink.api.common.state.ValueState;
import org.apache.flink.api.common.state.ValueStateDescriptor;
import org.apache.flink.streaming.api.TimerService;
import org.apache.flink.util.Collector;
import org.junit.jupiter.api.Test;

/** 验证受管状态恢复和失败传播；内存状态替身不替代真实生产savepoint验收。 */
class JourneyBundleStateTest {
    private final BundleJourneyFixture fixture = new BundleJourneyFixture("tenant", Instant.parse("2026-11-18T00:00:00Z"), "campaign", 2);
    private final MemoryState<JourneyJobState> primary = new MemoryState<>();
    private final MemoryState<String> participation = new MemoryState<>();
    private final TimerService timers = mock(TimerService.class);
    private final List<KafkaJobOutput> outputs = new ArrayList<>();
    private final Collector<KafkaJobOutput> output = new Collector<>() {
        @Override public void collect(KafkaJobOutput value) { outputs.add(value); }
        @Override public void close() { }
    };

    @Test void firstStartSavesSeparateProofAndRecreatedOperatorUsesItForTimer() throws Exception {
        var function = function(); start(function, output);
        assertNotNull(primary.value()); assertEquals(fixture.participation, JsonCodec.read(participation.value(), ReleaseBundleParticipation.class));
        var before = primary.value();
        var restored = function(); var context = timerContext();
        when(timers.currentProcessingTime()).thenReturn(before.activeTimerAtEpochMillis());
        restored.onTimer(before.activeTimerAtEpochMillis(), context, output);
        assertEquals(fixture.participation, JsonCodec.read(participation.value(), ReleaseBundleParticipation.class));
        assertEquals(0, primary.value().timerRetryCount()); assertTrue(outputs.stream().noneMatch(KafkaJobOutput::deadLetter));
    }

    @Test void expirationLeavesTombstoneAndRejectsBothBoundAndLegacyStartReplays() throws Exception {
        var function = function(); start(function, output);
        var before = primary.value(); var stored = participation.value();
        function.onTimer(before.expiresAtEpochMillis(), timerContext(), output);
        assertNull(primary.value()); assertEquals(stored, participation.value()); outputs.clear();
        when(timers.currentProcessingTime()).thenReturn(before.expiresAtEpochMillis() + 1);
        function.processElement(JsonCodec.write(fixture.start()), context(), output);
        var legacy = new JourneyJobInput(fixture.tenant, fixture.enrollment, "subject", fixture.reference, fixture.start().signal());
        function.processElement(JsonCodec.write(legacy), context(), output);
        assertEquals(2, outputs.size()); assertTrue(outputs.stream().allMatch(KafkaJobOutput::deadLetter));
        assertNull(primary.value()); assertEquals(stored, participation.value());
    }

    @Test void repeatedStartCannotReplaceProofOrIdentityAndLegacyWireHasNoNewNullField() throws Exception {
        var function = function(); start(function, output);
        var before = primary.value(); String stored = participation.value(); outputs.clear();
        var other = new BundleJourneyFixture("tenant", fixture.now, "other", 3);
        var changed = new JourneyJobInput(fixture.tenant, fixture.enrollment, "subject", other.reference, fixture.start().signal(), other.participation);
        function.processElement(JsonCodec.write(changed), context(), output);
        var changedSubject = new JourneyJobInput(fixture.tenant, fixture.enrollment, "other", fixture.reference, fixture.start().signal(), fixture.participation);
        function.processElement(JsonCodec.write(changedSubject), context(), output);
        assertTrue(outputs.stream().allMatch(KafkaJobOutput::deadLetter)); assertEquals(2, outputs.size());
        assertEquals(before, primary.value()); assertEquals(stored, participation.value());
        var legacy = new JourneyJobInput(fixture.tenant, fixture.enrollment, "subject", fixture.reference, fixture.start().signal());
        assertFalse(JsonCodec.write(legacy).contains("bundleParticipation"));
        assertEquals(legacy, JsonCodec.read(JsonCodec.write(legacy), JourneyJobInput.class));
        assertEquals(List.of("artifactId", "generation", "activationSequence", "journeyId", "journeyVersion"),
                java.util.Arrays.stream(JourneyJobInput.PlanReference.class.getRecordComponents()).map(java.lang.reflect.RecordComponent::getName).toList());
    }

    @Test void stateWriteFailureEscapesAndCheckpointReplayCanPersistBothStates() throws Exception {
        var function = function(); primary.failWrite = true;
        assertThrows(IllegalStateException.class, () -> start(function, output)); assertTrue(outputs.isEmpty());
        // 模拟从上个checkpoint恢复两个状态；失败后的半次内存修改不能被当作已提交检查点。
        primary.current = null; participation.current = null; primary.failWrite = false;
        start(function(), output);
        assertNotNull(primary.value()); assertNotNull(participation.value()); assertTrue(outputs.stream().noneMatch(KafkaJobOutput::deadLetter));
    }

    @Test @SuppressWarnings("unchecked")
    void outputFailureAfterStateWriteIsNotConvertedToDeadLetterOrTimerRetry() throws Exception {
        Collector<KafkaJobOutput> failed = mock(Collector.class);
        doThrow(new IllegalStateException("sink failed")).when(failed).collect(any());
        var first = function();
        assertThrows(IllegalStateException.class, () -> start(first, failed)); verify(failed, times(1)).collect(any());
        primary.current = null; participation.current = null;
        final var restored = function(); start(restored, output); var checkpoint = primary.value(); clearInvocations(failed);
        assertThrows(IllegalStateException.class, () -> restored.onTimer(checkpoint.activeTimerAtEpochMillis(), timerContext(), failed));
        assertEquals(0, primary.value().timerRetryCount()); verify(failed, times(1)).collect(any());
        primary.current = checkpoint;
        function().onTimer(checkpoint.activeTimerAtEpochMillis(), timerContext(), output);
        assertTrue(outputs.stream().noneMatch(KafkaJobOutput::deadLetter));
    }

    @Test void corruptSavedProofAndBackendReadFailureStopProcessingWithoutDeadLetter() throws Exception {
        var function = function(); start(function, output); outputs.clear();
        participation.current = "{}";
        assertThrows(JourneyInfrastructureException.class, () -> function.processElement(JsonCodec.write(fixture.start()), context(), output));
        participation.current = JsonCodec.write(fixture.participation).replace("\"signature\":\"" + fixture.participation.signature() + "\"", "\"signature\":\"\"");
        assertThrows(JourneyInfrastructureException.class, () -> function.processElement(JsonCodec.write(fixture.start()), context(), output));
        participation.current = JsonCodec.write(fixture.participation); primary.failRead = true;
        assertThrows(IllegalStateException.class, () -> function.processElement(JsonCodec.write(fixture.start()), context(), output));
        assertTrue(outputs.isEmpty());
    }

    @Test void nullInputIsDeadLetteredBeforeReadingState() throws Exception {
        var function = function(); primary.failRead = true;
        function.processElement("null", context(), output);
        assertEquals(1, outputs.size()); assertTrue(outputs.getFirst().deadLetter());
        assertNull(participation.value());
    }

    @Test void legacyOnlyResolverCannotSilentlyAcceptABundleProof() {
        var reducer = new JourneyJobReducer((tenant, reference, now, current) -> fixture.plan);
        assertThrows(com.acme.marketing.platform.error.ConflictException.class,
                () -> reducer.apply(null, fixture.start(), fixture.now.toEpochMilli(), fixture.participation));
    }

    @Test void unchangedLegacyStateRestoresUsingTheRealFlinkSerializerSnapshot() throws Exception {
        var legacy = new JourneyJobInput(fixture.tenant, fixture.enrollment, "subject", fixture.reference, fixture.start().signal());
        var state = new JourneyJobReducer((tenant, reference, now, current) -> fixture.plan).apply(null, legacy, fixture.now.toEpochMilli()).state();
        var serializer = org.apache.flink.api.common.typeinfo.TypeInformation.of(JourneyJobState.class)
                .createSerializer(new org.apache.flink.api.common.serialization.SerializerConfigImpl());
        var data = new org.apache.flink.core.memory.DataOutputSerializer(256); serializer.serialize(state, data);
        var metadata = new org.apache.flink.core.memory.DataOutputSerializer(256);
        org.apache.flink.api.common.typeutils.TypeSerializerSnapshotSerializationUtil.writeSerializerSnapshot(metadata, serializer.snapshotConfiguration());
        org.apache.flink.api.common.typeutils.TypeSerializerSnapshot<JourneyJobState> saved =
                org.apache.flink.api.common.typeutils.TypeSerializerSnapshotSerializationUtil.readSerializerSnapshot(
                        new org.apache.flink.core.memory.DataInputDeserializer(metadata.getCopyOfBuffer()), getClass().getClassLoader());
        assertTrue(serializer.snapshotConfiguration().resolveSchemaCompatibility(saved).isCompatibleAsIs());
        assertEquals(state, saved.restoreSerializer().deserialize(new org.apache.flink.core.memory.DataInputDeserializer(data.getCopyOfBuffer())));
    }

    @SuppressWarnings("unchecked")
    private JourneyRuntimeProcessFunction function() throws Exception {
        var resolver = new JourneyPlanResolver() {
            private static final long serialVersionUID = 1L;
            @Override public JourneyPlan resolve(String tenant, JourneyJobInput.PlanReference reference, Instant now, boolean current) { return fixture.plan; }
            @Override public JourneyPlan resolveBound(String tenant, String enrollment, String subject, JourneyJobInput.PlanReference reference,
                    ReleaseBundleParticipation proof, Instant now, boolean current) {
                assertEquals(fixture.participation, proof); assertEquals(fixture.enrollment, enrollment); assertEquals("subject", subject); return fixture.plan;
            }
        };
        var function = new JourneyRuntimeProcessFunction(resolver); RuntimeContext runtime = mock(RuntimeContext.class);
        when(runtime.getState(any(ValueStateDescriptor.class))).thenAnswer(call -> {
            String name = ((ValueStateDescriptor<?>) call.getArgument(0)).getName();
            if ("journey-enrollment-v2".equals(name)) return primary;
            assertEquals("journey-bundle-participation-v1", name); return participation;
        });
        function.setRuntimeContext(runtime); function.open(null); return function;
    }
    private void start(JourneyRuntimeProcessFunction function, Collector<KafkaJobOutput> collector) throws Exception {
        when(timers.currentProcessingTime()).thenReturn(fixture.now.toEpochMilli());
        function.processElement(JsonCodec.write(fixture.start()), context(), collector);
    }
    @SuppressWarnings("unchecked")
    private JourneyRuntimeProcessFunction.Context context() {
        var context = mock(JourneyRuntimeProcessFunction.Context.class); when(context.timerService()).thenReturn(timers); return context;
    }
    @SuppressWarnings("unchecked")
    private JourneyRuntimeProcessFunction.OnTimerContext timerContext() {
        var context = mock(JourneyRuntimeProcessFunction.OnTimerContext.class); when(context.timerService()).thenReturn(timers); return context;
    }
    private static final class MemoryState<T> implements ValueState<T> {
        private T current; private boolean failWrite; private boolean failRead;
        @Override public T value() { if (failRead) throw new IllegalStateException("state backend read failed"); return current; }
        @Override public void update(T value) { if (failWrite) throw new IllegalStateException("state backend write failed"); current = value; }
        @Override public void clear() { current = null; }
    }
}
