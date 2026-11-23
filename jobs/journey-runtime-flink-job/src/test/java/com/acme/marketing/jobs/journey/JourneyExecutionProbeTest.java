package com.acme.marketing.jobs.journey;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.acme.marketing.contracts.event.JourneyBundleExecutionAckEvent;
import com.acme.marketing.contracts.event.JourneyBundleExecutionProbeMessage;
import com.acme.marketing.contracts.release.JourneyBundleExecutionAckSigner;
import com.acme.marketing.contracts.release.ReleaseBundleProtocolError;
import com.acme.marketing.jobs.support.JsonCodec;
import com.acme.marketing.jobs.support.KafkaJobOutput;
import com.acme.marketing.platform.crypto.Ed25519;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import org.apache.flink.api.common.JobID;
import org.apache.flink.api.common.JobInfo;
import org.apache.flink.api.common.TaskInfo;
import org.apache.flink.api.common.functions.RuntimeContext;
import org.apache.flink.api.common.state.ValueState;
import org.apache.flink.streaming.api.TimerService;
import org.apache.flink.util.Collector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 实际处理函数的状态/输出边界测试；Context替身不冒充运行集群或Kafka提交验收。 */
class JourneyExecutionProbeTest {
    private final BundleJourneyFixture fixture = new BundleJourneyFixture("tenant", Instant.parse("2026-11-18T00:00:00Z"), "campaign", 2);
    private final java.security.KeyPair keys = Ed25519.generateKeyPair();
    private final String digest = "sha256:" + "a".repeat(64);
    private final JourneyPlanResolver plans = mock(JourneyPlanResolver.class);
    private final ValueState<?> state = mock(ValueState.class);
    private final TimerService timers = mock(TimerService.class);
    private final List<KafkaJobOutput> results = new ArrayList<>();
    private final Collector<KafkaJobOutput> output = new Collector<>() {
        @Override public void collect(KafkaJobOutput value) { results.add(value); }
        @Override public void close() { }
    };

    @Test void ackUsesActualContextAndRepeatedProbeNeverReadsOrChangesJourneyState() throws Exception {
        var function = function(handler()); var context = context();
        function.processElement(input(0), context, output);
        function.processElement(input(1), context, output);
        function.processElement(input(0), context, output);
        assertEquals(3, results.size()); assertTrue(results.stream().noneMatch(KafkaJobOutput::deadLetter));
        for (var result : results) {
            var wire = JsonCodec.read(result.payload(), com.fasterxml.jackson.databind.JsonNode.class).path("ack");
            assertEquals(fixture.now.toString(), wire.path("acknowledgedAt").textValue());
            assertEquals(fixture.probe().issuedAt().toString(), wire.path("probe").path("issuedAt").textValue());
            assertEquals(fixture.probe().expiresAt().toString(), wire.path("probe").path("expiresAt").textValue());
            var ack = JsonCodec.read(result.payload(), JourneyBundleExecutionAckEvent.class).ack();
            assertTrue(JourneyBundleExecutionAckSigner.verify(keys.getPublic(), ack));
            assertEquals("1".repeat(32), ack.jobId()); assertEquals(2, ack.parallelism());
            assertEquals(1, ack.subtaskIndex()); assertEquals(7, ack.attemptNumber()); assertEquals(digest, ack.buildDigest());
            assertEquals(fixture.probe(), ack.probe());
        }
        verify(plans, times(3)).verifyExecutionProbe(fixture.probe(), "1".repeat(32), 2, digest, fixture.now);
        verifyNoInteractions(state); verify(timers, never()).registerProcessingTimeTimer(anyLong());
        verify(timers, never()).deleteProcessingTimeTimer(anyLong());
        assertNotEquals(results.get(0).key(), results.get(1).key());
        assertEquals(results.get(0).key(), new JourneyRuntimeFlinkJob.EnrollmentKeySelector().getKey(input(0)));
    }

    @Test void unavailableOrRejectedProbeProducesOnlySafeFailureAndLaterRetryCanSucceed() throws Exception {
        var function = function(handler());
        doThrow(new JourneyInfrastructureException(new IllegalStateException("private database details"))).doNothing()
                .when(plans).verifyExecutionProbe(any(), anyString(), anyInt(), anyString(), any());
        function.processElement(input(0), context(), output);
        assertTrue(results.getFirst().deadLetter()); assertFalse(results.getFirst().payload().contains("private database"));
        assertTrue(results.getFirst().payload().contains(ReleaseBundleProtocolError.EXECUTION_PROBE_UNAVAILABLE.message()));
        function.processElement(input(0), context(), output); assertFalse(results.getLast().deadLetter());
        function(JourneyExecutionProbeHandler.disabled()).processElement(input(0), context(), output);
        assertTrue(results.getLast().deadLetter()); verifyNoInteractions(state);
    }

    @Test @SuppressWarnings("unchecked")
    void failedOutputAlwaysPropagatesWithoutSecondCollectOrStateMutation() throws Exception {
        var function = function(handler()); Collector<KafkaJobOutput> failed = mock(Collector.class);
        doThrow(new IllegalStateException("sink unavailable")).when(failed).collect(any());
        assertThrows(IllegalStateException.class, () -> function.processElement(input(0), context(), failed));
        verify(failed, times(1)).collect(any()); verifyNoInteractions(state);
        clearInvocations(failed);
        doThrow(ReleaseBundleProtocolError.EXECUTION_PROBE_INVALID.conflict()).when(plans).verifyExecutionProbe(any(), anyString(), anyInt(), anyString(), any());
        assertThrows(IllegalStateException.class, () -> function.processElement(input(0), context(), failed));
        verify(failed, times(1)).collect(any()); verifyNoInteractions(state);
    }

    @Test void parserKeepsLegacyWireAndRejectsUnknownTypesFieldsAndNonceBounds() throws Exception {
        String legacy = JsonCodec.write(fixture.start());
        assertEquals(fixture.start(), JsonCodec.read(legacy, JourneyRuntimeMessage.class).signal());
        assertEquals(fixture.start().partitionKey(), new JourneyRuntimeFlinkJob.EnrollmentKeySelector().getKey(legacy));
        var function = function(handler());
        for (String bad : List.of("null", input(0).replace("JOURNEY_BUNDLE_EXECUTION_PROBE", "UNKNOWN"),
                input(0).replace("\"routingNonce\":0", "\"routingNonce\":2048"),
                input(0).replace(",\"routingNonce\":0", ""),
                input(0).replace("\"routingNonce\":0", "\"routingNonce\":0.1"),
                input(0).replace("\"routingNonce\":0", "\"routingNonce\":-1"),
                input(0).replace("\"routingNonce\":0", "\"routingNonce\":0,\"unknown\":true"))) {
            function.processElement(bad, context(), output); assertTrue(results.getLast().deadLetter());
        }
        verifyNoInteractions(state, plans);
    }

    @Test void keyRolesAndIncompleteConfigurationFailClosed() {
        String priv = encoded(keys.getPrivate().getEncoded()), pub = encoded(keys.getPublic().getEncoded());
        assertThrows(IllegalStateException.class, () -> new JourneyExecutionProbeHandler("execution", "", pub, digest));
        assertThrows(IllegalStateException.class, () -> new JourneyExecutionProbeHandler("", priv, pub, digest));
        assertThrows(IllegalStateException.class, () -> new JourneyExecutionProbeHandler("execution", priv, encoded(Ed25519.generateKeyPair().getPublic().getEncoded()), digest));
        assertThrows(IllegalStateException.class, () -> new JourneyExecutionProbeHandler("execution", priv, pub, "self-reported"));
        assertThrows(IllegalStateException.class, () -> JourneyExecutionProbeHandler.disabled().acknowledge(plans, fixture.probe(), fixture.now));
    }

    @Test void jobGraphSerializesWithoutLiveResourcesAndRestoresTransientRuntimeIdentity() throws Exception {
        var resolver = new JdbcJourneyPlanResolver("jdbc:mysql://127.0.0.1:1/test", "user", "",
                "release", BundleJourneyFixture.encoded(BundleJourneyFixture.RELEASE), "", "compiler", BundleJourneyFixture.encoded(BundleJourneyFixture.COMPILER), "", "local", "cell", "main",
                "participation", BundleJourneyFixture.encoded(BundleJourneyFixture.PARTICIPATION), "");
        var bytes = new ByteArrayOutputStream();
        try (var stream = new ObjectOutputStream(bytes)) { stream.writeObject(new JourneyRuntimeProcessFunction(resolver, handler())); }
        JourneyRuntimeProcessFunction restored;
        try (var stream = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) { restored = (JourneyRuntimeProcessFunction) stream.readObject(); }
        restored.setRuntimeContext(runtime()); restored.open(null); verifyNoInteractions(state);
        // 没有数据库操作；真正恢复历史状态仍由已有serializer兼容测试及部署门禁约束。
    }

    @Test void artifactDigestRequiresRealExecutableJarAndHashesItsActualBytes(@TempDir Path directory) throws Exception {
        Path archive = directory.resolve("job.jar"); var manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, JourneyRuntimeFlinkJob.class.getName());
        try (var jar = new JarOutputStream(Files.newOutputStream(archive), manifest)) { jar.flush(); }
        assertEquals("sha256:" + com.acme.marketing.platform.crypto.Digests.sha256Hex(Files.readAllBytes(archive)), JourneySubmittedArtifact.digest(archive));
        assertThrows(IllegalStateException.class, () -> JourneySubmittedArtifact.digest(directory));
        assertThrows(IllegalStateException.class, JourneySubmittedArtifact::currentDigest);
        Path empty = directory.resolve("empty.jar"); Files.writeString(empty, "not a jar");
        assertThrows(IllegalStateException.class, () -> JourneySubmittedArtifact.digest(empty));
    }

    private JourneyExecutionProbeHandler handler() {
        return new JourneyExecutionProbeHandler("execution", encoded(keys.getPrivate().getEncoded()), encoded(keys.getPublic().getEncoded()), digest);
    }
    private String input(int nonce) { return JsonCodec.write(new JourneyBundleExecutionProbeMessage(JourneyBundleExecutionProbeMessage.EVENT_TYPE, fixture.probe(), nonce)); }
    private static String encoded(byte[] bytes) { return Base64.getEncoder().encodeToString(bytes); }
    private JourneyRuntimeProcessFunction function(JourneyExecutionProbeHandler handler) {
        var function = new JourneyRuntimeProcessFunction(plans, handler); function.setRuntimeContext(runtime()); function.open(null); return function;
    }
    @SuppressWarnings("unchecked")
    private RuntimeContext runtime() {
        RuntimeContext runtime = mock(RuntimeContext.class); JobInfo job = mock(JobInfo.class); TaskInfo task = mock(TaskInfo.class);
        when(runtime.getState(any(org.apache.flink.api.common.state.ValueStateDescriptor.class))).thenAnswer(invocation -> state);
        when(runtime.getJobInfo()).thenReturn(job); when(runtime.getTaskInfo()).thenReturn(task);
        when(job.getJobId()).thenReturn(JobID.fromHexString("1".repeat(32))); when(task.getNumberOfParallelSubtasks()).thenReturn(2);
        when(task.getIndexOfThisSubtask()).thenReturn(1); when(task.getAttemptNumber()).thenReturn(7); return runtime;
    }
    @SuppressWarnings("unchecked")
    private JourneyRuntimeProcessFunction.Context context() {
        JourneyRuntimeProcessFunction.Context context = mock(JourneyRuntimeProcessFunction.Context.class);
        when(context.timerService()).thenReturn(timers); when(timers.currentProcessingTime()).thenReturn(fixture.now.toEpochMilli()); return context;
    }
}
