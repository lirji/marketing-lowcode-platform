package com.acme.marketing.journeyservice;

import static org.junit.jupiter.api.Assertions.*;

import com.acme.marketing.contracts.event.JourneyBundleExecutionAckEvent;
import com.acme.marketing.contracts.release.*;
import com.acme.marketing.journeyservice.application.*;
import com.acme.marketing.journeyservice.application.JourneyExecutionProbeRepository.Key;
import com.acme.marketing.journeyservice.infrastructure.persistence.MybatisJourneyExecutionProbeRepository;
import com.acme.marketing.journeyservice.infrastructure.persistence.mapper.JourneyExecutionProbeMapper;
import com.acme.marketing.platform.crypto.Ed25519;
import com.acme.marketing.platform.error.ConflictException;
import com.acme.marketing.platform.identity.TenantId;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyPair;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** 隔离真实MySQL/HTTP和事务验证，不把人工签名测试ACK当运行集群证据。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@org.springframework.test.context.TestExecutionListeners(listeners = JourneyExecutionProbeIntegrationTest.DatabaseCleanup.class,
        mergeMode = org.springframework.test.context.TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS)
class JourneyExecutionProbeIntegrationTest {
    private static final KeyPair RELEASE = Ed25519.generateKeyPair(), COMPILER = Ed25519.generateKeyPair(), EXECUTION = Ed25519.generateKeyPair();
    private static final String PATH = "/api/v1/journey-runtime/execution-probes";
    private static final String JOB = "1".repeat(32), DIGEST = "sha256:" + "a".repeat(64);
    private static final Instant BASE = Instant.parse("2026-11-18T00:00:00Z");
    private static final AtomicReference<Instant> TIME = new AtomicReference<>(BASE);
    private static final String INPUT_TOPIC = "probe-delivery-test-input", OUTPUT_TOPIC = "probe-delivery-test-output";
    private static final org.testcontainers.kafka.KafkaContainer KAFKA = kafka();
    private static final org.testcontainers.mysql.MySQLContainer MYSQL = mysql();
    private static org.testcontainers.kafka.KafkaContainer kafka() {
        var value = new org.testcontainers.kafka.KafkaContainer("apache/kafka:3.8.0")
                .withEnv("KAFKA_HEAP_OPTS", "-Xms128m -Xmx256m")
                .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withMemory(768L * 1024 * 1024).withMemorySwap(768L * 1024 * 1024));
        value.start();
        try (var admin = org.apache.kafka.clients.admin.Admin.create(Map.of("bootstrap.servers", value.getBootstrapServers()))) {
            admin.createTopics(List.of(new org.apache.kafka.clients.admin.NewTopic(INPUT_TOPIC, 2, (short) 1),
                    new org.apache.kafka.clients.admin.NewTopic(OUTPUT_TOPIC, 2, (short) 1))).all().get(20, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception failure) { value.stop(); throw new IllegalStateException(failure); }
        return value;
    }
    private static org.testcontainers.mysql.MySQLContainer mysql() {
        var value = new org.testcontainers.mysql.MySQLContainer("mysql:8.4.11")
                .withCommand("--performance-schema=OFF", "--innodb-buffer-pool-size=64M", "--max-connections=30")
                .withTmpFs(Map.of("/var/lib/mysql", "rw"))
                .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withMemory(512L * 1024 * 1024).withMemorySwap(512L * 1024 * 1024))
                .withStartupTimeout(Duration.ofMinutes(5)); value.start(); return value;
    }
    public static final class DatabaseCleanup extends org.springframework.test.context.support.AbstractTestExecutionListener {
        @Override public int getOrder() { return org.springframework.core.Ordered.HIGHEST_PRECEDENCE; }
        @Override public void afterTestClass(org.springframework.test.context.TestContext context) { MYSQL.stop(); KAFKA.stop(); }
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        r.add("spring.kafka.producer.key-serializer", () -> "org.apache.kafka.common.serialization.StringSerializer");
        r.add("spring.kafka.producer.value-serializer", () -> "org.apache.kafka.common.serialization.StringSerializer");
        r.add("spring.kafka.consumer.key-deserializer", () -> "org.apache.kafka.common.serialization.StringDeserializer");
        r.add("spring.kafka.consumer.value-deserializer", () -> "org.apache.kafka.common.serialization.StringDeserializer");
        r.add("marketing.kafka.consumers-enabled", () -> true);
        r.add("marketing.journey.output-topic", () -> OUTPUT_TOPIC);
        r.add("marketing.journey.output-consumer-group", () -> "probe-test-collector");
        r.add("marketing.journey.probe-delivery.enabled", () -> true);
        r.add("marketing.journey.probe-delivery.relay-enabled", () -> false);
        r.add("marketing.journey.probe-delivery.input-topic", () -> INPUT_TOPIC);
        r.add("management.otlp.metrics.export.enabled", () -> false);
        r.add("spring.datasource.url", MYSQL::getJdbcUrl); r.add("spring.datasource.username", MYSQL::getUsername);
        r.add("spring.datasource.password", MYSQL::getPassword); r.add("spring.flyway.user", MYSQL::getUsername); r.add("spring.flyway.password", MYSQL::getPassword);
        r.add("marketing.release.trusted-key-id", () -> "release-key"); r.add("marketing.release.public-key-base64", () -> encoded(RELEASE.getPublic().getEncoded()));
        r.add("marketing.compiler.trusted-key-id", () -> "compiler-key"); r.add("marketing.compiler.public-key-base64", () -> encoded(COMPILER.getPublic().getEncoded()));
        r.add("marketing.journey.execution-target.job-id", () -> JOB); r.add("marketing.journey.execution-target.parallelism", () -> 2);
        r.add("marketing.journey.execution-target.build-digest", () -> DIGEST); r.add("marketing.journey.execution-target.participation-key-id", () -> "participation");
        r.add("marketing.journey.execution-ack.trusted-key-id", () -> "execution"); r.add("marketing.journey.execution-ack.public-key-base64", () -> encoded(EXECUTION.getPublic().getEncoded()));
    }
    @TestConfiguration static class FaultConfiguration {
        @Bean @Primary Clock probeTestClock() {
            return new Clock() {
                @Override public ZoneId getZone() { return ZoneOffset.UTC; }
                @Override public Clock withZone(ZoneId zone) { return this; }
                @Override public Instant instant() { return TIME.get(); }
            };
        }
        @Bean @Primary FaultDeliveryRepository faultDeliveryRepository(com.acme.marketing.journeyservice.infrastructure.persistence.mapper.JourneyProbeDeliveryMapper mapper) { return new FaultDeliveryRepository(mapper); }
        @Bean @Primary FaultRepository faultRepository(JourneyExecutionProbeMapper mapper) { return new FaultRepository(mapper); }
    }
    static class FaultRepository extends MybatisJourneyExecutionProbeRepository {
        private final AtomicBoolean failRegister = new AtomicBoolean(), failAck = new AtomicBoolean();
        FaultRepository(JourneyExecutionProbeMapper mapper) { super(mapper); }
        public void resetFaults() { failRegister.set(false); failAck.set(false); }
        public void failNextRegistration() { failRegister.set(true); }
        public void failNextAck() { failAck.set(true); }
        @Override public void ensure(Key key, StoredProbe row) {
            super.ensure(key, row); if (failRegister.getAndSet(false)) throw new IllegalStateException("injected registration rollback");
        }
        @Override public void saveAck(Key key, StoredAck row) {
            super.saveAck(key, row); if (failAck.getAndSet(false)) throw new IllegalStateException("injected ack rollback");
        }
    }
    static class FaultDeliveryRepository extends com.acme.marketing.journeyservice.infrastructure.persistence.MybatisJourneyProbeDeliveryRepository {
        private final AtomicBoolean failFinish = new AtomicBoolean(), failEnsure = new AtomicBoolean();
        FaultDeliveryRepository(com.acme.marketing.journeyservice.infrastructure.persistence.mapper.JourneyProbeDeliveryMapper mapper) { super(mapper); }
        public void failNextFinish() { failFinish.set(true); }
        public void failNextEnsure() { failEnsure.set(true); }
        public void resetFaults() { failFinish.set(false); failEnsure.set(false); }
        @Override public void ensure(Key key, NewDelivery value) {
            super.ensure(key, value); if (failEnsure.getAndSet(false)) throw new IllegalStateException("injected enqueue rollback");
        }
        @Override public int finish(Key key, Lease lease, Completion completion, String now) {
            int count = super.finish(key, lease, completion, now);
            if (failFinish.getAndSet(false)) throw new IllegalStateException("injected delivery commit rollback"); return count;
        }
    }
    @Autowired FaultDeliveryRepository deliveryFaults;
    @Autowired JourneyProbeDeliveryService deliveries;
    @Autowired JourneyProbeDeliveryWorker worker;
    @Autowired JourneyExecutionDeployment deployment;
    @Autowired JourneyProbeDeliverySettings deliverySettings;
    @Autowired com.acme.marketing.journeyservice.infrastructure.KafkaJourneyProbeSender sender;
    @Autowired org.springframework.kafka.core.KafkaTemplate<String, String> kafka;
    @LocalServerPort int port;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired JourneyExecutionProbeService service;
    @Autowired FaultRepository repository;
    @Autowired JourneyProbeDeliveryRepository deliveryRepository;
    @Autowired org.apache.ibatis.session.SqlSessionFactory sessions;
    @Autowired com.acme.marketing.journeyservice.infrastructure.persistence.mapper.JourneyProbeDeliveryMapper deliveryMapper;
    @Autowired BundleDirectiveRepository directives;
    @Autowired JourneyRuntimeReleaseService releases;
    @Autowired Clock clock;
    @Autowired PlatformTransactionManager transactions;
    @Autowired com.acme.marketing.platform.web.TenantContextFilter tenantFilter;
    private final String tenant = "probe-" + UUID.randomUUID();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final Instant now = BASE.minusSeconds(1);
    @BeforeEach void resetClock() { TIME.set(BASE); repository.resetFaults(); deliveryFaults.resetFaults(); }

    @Test void concurrentRegistrationIsImmutableAndOnlyDistinctActualSubtasksMakeReady() throws Exception {
        var probe = installedProbe("probe"); var responses = concurrent(probe, probe);
        responses.forEach(result -> assertState(result, "PENDING", 0)); assertEquals(1, count("mk_journey_execution_probe"));
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            String ack = event(ack(probe, 0, 0));
            var a = pool.submit(() -> service.collect(ack)); var b = pool.submit(() -> service.collect(ack)); a.get(); b.get();
        }
        service.collect(event(ack(probe, 0, 3))); assertState(get(probe), "PENDING", 1);
        service.collect(event(ack(probe, 1, 0))); assertState(get(probe), "READY", 2);
        assertEquals(2, count("mk_journey_execution_probe_ack"));
        assertEquals(0, count("mk_journey_runtime_activation")); assertEquals(0, count("mk_journey_dispatch_outbox"));
        var conflicting = resign(probe, "signatureKeyId", "other-control-key");
        // 即便由受信Control签名，key ID不在信任表时先拒绝，不能覆盖原挑战。
        assertEquals(409, send(post(conflicting)).statusCode()); assertState(get(probe), "READY", 2);
    }

    @Test void sameIdDifferentValidChallengeConflictsAndScopesRemainMandatory() throws Exception {
        var probe = installedProbe("probe"); assertState(send(post(probe)), "PENDING", 0);
        var different = JourneyBundleExecutionProbeSigner.sign(RELEASE.getPrivate(), new JourneyBundleExecutionProbe(probe.probeId(), probe.tenantId(),
                probe.campaignId(), probe.environment(), probe.cell(), probe.namespace(), probe.memberManifestId(), probe.activationSequence(), probe.activationSignature(),
                probe.targetJobId(), probe.targetParallelism(), probe.participationKeyId(), BASE, BASE.plusSeconds(100), probe.signatureKeyId(), ""));
        assertCode(409, "RELEASE_BUNDLE_EXECUTION_PROBE_CONFLICT", send(post(different)));
        assertEquals(400, send(post(probe).setHeader("X-Dev-Organization-Ids", "other")).statusCode());
        assertEquals(400, send(post(probe).setHeader("X-Dev-Shop-Ids", "other")).statusCode());
        assertEquals(404, send(request(PATH + "/campaign/probe").setHeader("X-Dev-Tenant-Id", "another").GET()).statusCode());
        assertEquals(409, send(post(probe).setHeader("X-Dev-Tenant-Id", "another")).statusCode());
        assertEquals(1, count("mk_journey_execution_probe"));
    }

    @Test void registrationAndAckFailureRollbackWholeFactAndCanRetry() throws Exception {
        var probe = installedProbe("probe"); repository.failNextRegistration();
        assertEquals(500, send(post(probe)).statusCode()); assertEquals(0, count("mk_journey_execution_probe"));
        assertState(send(post(probe)), "PENDING", 0); repository.failNextAck();
        String ack = event(ack(probe, 0, 0)); assertThrows(IllegalStateException.class, () -> service.collect(ack));
        assertEquals(0, count("mk_journey_execution_probe_ack")); service.collect(ack); assertState(get(probe), "PENDING", 1);
    }

    @Test void reconstructedCollectorUsesStoredChallengeAndRejectsChangedDeploymentOrTrust() throws Exception {
        var probe = installedProbe("probe"); send(post(probe)); service.collect(event(ack(probe, 0, 0)));
        var target = new JourneyExecutionDeployment("local", "cell-a", "main", JOB, 2, DIGEST, "participation");
        var trust = new PinnedJourneyBundleExecutionVerifier(Map.of("release-key", RELEASE.getPublic()), Map.of("execution", EXECUTION.getPublic()));
        var fresh = new JourneyExecutionProbeService(repository, directives, releases, target, trust, mapper, clock);
        var tx = new TransactionTemplate(transactions); String last = event(ack(probe, 1, 0));
        tx.executeWithoutResult(status -> fresh.collect(last)); assertState(get(probe), "READY", 2);
        assertEquals("READY", asTenant(() -> fresh.get("campaign", "probe")).state());
        var changed = new JourneyExecutionDeployment("local", "cell-a", "main", JOB, 2, "sha256:" + "b".repeat(64), "participation");
        var moved = new JourneyExecutionProbeService(repository, directives, releases, changed, trust, mapper, clock);
        assertThrows(ConflictException.class, () -> tx.executeWithoutResult(status -> moved.collect(last)));
        assertThrows(ConflictException.class, () -> asTenant(() -> moved.get("campaign", "probe")));
        var revoked = new JourneyExecutionProbeService(repository, directives, releases, target,
                new PinnedJourneyBundleExecutionVerifier(Map.of("release-key", RELEASE.getPublic()), Map.of()), mapper, clock);
        assertThrows(ConflictException.class, () -> tx.executeWithoutResult(status -> revoked.collect(last)));
        assertThrows(ConflictException.class, () -> asTenant(() -> revoked.get("campaign", "probe")));
    }

    @Test void forgedUnknownOutOfTargetAndCorruptProofsCannotIncreaseCoverage() throws Exception {
        var probe = installedProbe("probe"); assertThrows(com.acme.marketing.platform.error.NotFoundException.class, () -> service.collect(event(ack(probe, 0, 0))));
        send(post(probe)); var valid = ack(probe, 0, 0);
        var forged = JourneyBundleExecutionAckSigner.sign(RELEASE.getPrivate(), valid);
        assertThrows(ConflictException.class, () -> service.collect(event(forged)));
        var wrongBuild = JourneyBundleExecutionAckSigner.sign(EXECUTION.getPrivate(), new JourneyBundleExecutionAck(probe, valid.executionProtocol(),
                valid.jobId(), valid.parallelism(), valid.subtaskIndex(), valid.attemptNumber(), "sha256:" + "c".repeat(64), BASE, valid.signatureKeyId(), ""));
        assertThrows(ConflictException.class, () -> service.collect(event(wrongBuild)));
        service.collect(event(valid)); assertState(get(probe), "PENDING", 1);
        jdbc.update("UPDATE mk_journey_execution_probe_ack SET ack_json='null' WHERE tenant_id=?", tenant);
        assertEquals(409, get(probe).statusCode());
    }

    @Test void expiredHistoryDoesNotRestoreReadinessAndCurrentCorruptArtifactRejects() throws Exception {
        var probe = installedProbe("probe"); send(post(probe));
        service.collect(event(ack(probe, 0, 0))); service.collect(event(ack(probe, 1, 0))); assertState(get(probe), "READY", 2);
        jdbc.update("UPDATE mk_journey_runtime_generation SET artifact_payload=? WHERE tenant_id=?", new byte[]{0}, tenant);
        assertEquals(409, get(probe).statusCode()); TIME.set(BASE.plusSeconds(700));
        assertState(get(probe), "EXPIRED", 2);
        assertEquals(409, send(post(probe)).statusCode());
        assertThrows(ConflictException.class, () -> service.collect(event(ack(probe, 0, 0))));
    }

    @Test void strictBoundedInputAndOldMessageRoutingDoNotWriteProbeFacts() throws Exception {
        var probe = installedProbe("probe"); String json = mapper.writeValueAsString(probe);
        for (String bad : List.of("null", json + "{}", json.replace("\"targetParallelism\":2", "\"targetParallelism\":2.1"), " ".repeat(JourneyExecutionProbeService.MAX_PROBE_BYTES + 1)))
            assertEquals(400, send(raw(bad)).statusCode());
        assertEquals(403, send(raw(" ".repeat(JourneyExecutionProbeService.MAX_PROBE_BYTES + 1)).setHeader("X-Dev-Permissions", "runtime:read")).statusCode());
        assertFalse(service.isExecutionAck("{\"eventType\":\"JOURNEY_STATE_CHANGED\"}"));
        assertTrue(service.isExecutionAck(event(ack(probe, 0, 0)))); assertEquals(0, count("mk_journey_execution_probe"));
        var projector = org.mockito.Mockito.mock(JourneyOutputProjector.class);
        var consumer = new com.acme.marketing.journeyservice.infrastructure.JourneyOutputConsumer(projector, service);
        String legacy = "{\"eventType\":\"JOURNEY_STATE_CHANGED\"}";
        consumer.consume(new org.apache.kafka.clients.consumer.ConsumerRecord<>("mk.journey.output.v1", 0, 11, "legacy", legacy));
        org.mockito.Mockito.verify(projector).project(legacy, "mk.journey.output.v1", 0, 11);
        send(post(probe));
        consumer.consume(new org.apache.kafka.clients.consumer.ConsumerRecord<>("mk.journey.output.v1", 0, 12, "probe", event(ack(probe, 0, 0))));
        org.mockito.Mockito.verifyNoMoreInteractions(projector); assertState(get(probe), "PENDING", 1);
    }

    @Test void incompleteTargetAndMissingTrustFailClosed() {
        assertThrows(IllegalStateException.class, () -> new JourneyExecutionDeployment("local", "cell-a", "main", JOB, 0, "", ""));
        var empty = new JourneyExecutionDeployment("local", "cell-a", "main", "", 0, "", "");
        assertThrows(ConflictException.class, () -> empty.expected(new TenantId(tenant), "campaign"));
        var config = new com.acme.marketing.journeyservice.infrastructure.JourneyReleaseConfiguration();
        assertThrows(IllegalStateException.class, () -> config.executionProbeVerifier("", "", "", "", "", "", JOB));
        assertThrows(IllegalStateException.class, () -> config.executionProbeVerifier("", "", "", "execution", encoded(EXECUTION.getPublic().getEncoded()), "", JOB));
    }

    @Test void deliveryLeaseSkipsLockedRowsFencesLateWritersAndRecoversCursor() throws Exception {
        var firstProbe = installedProbe("delivery-1"); send(post(firstProbe));
        var secondProbe = installedProbe("delivery-2"); send(post(secondProbe));
        assertEquals(0, count("mk_journey_probe_delivery"));
        var target = new JourneyExecutionDeployment("local", "cell-a", "main", JOB, 2, DIGEST, "participation");
        var slot = new JourneyProbeDeliveryRepository.Slot("local", "cell-a", "main");
        var tx = new TransactionTemplate(transactions); tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
        var queued = new JourneyProbeDeliveryRepository.NewDelivery("probe-test-input", 32, sql(BASE), sql(BASE.plusSeconds(120)), "test", sql(BASE));
        for (var probe : List.of(firstProbe, secondProbe)) tx.executeWithoutResult(status -> deliveryRepository.ensure(target.key(tenant, "campaign", probe.probeId()), queued));
        var parameters = Map.of("slot", slot, "now", sql(BASE));
        var bound = sessions.getConfiguration().getMappedStatement("com.acme.marketing.journeyservice.infrastructure.persistence.mapper.JourneyProbeDeliveryMapper.lockDue").getBoundSql(parameters);
        var meta = sessions.getConfiguration().newMetaObject(parameters);
        Object[] arguments = bound.getParameterMappings().stream().map(parameter -> meta.getValue(parameter.getProperty())).toArray();
        var plan = jdbc.queryForMap("EXPLAIN " + bound.getSql(), arguments);
        assertFalse(String.valueOf(plan.get("Extra")).contains("filesort"), plan.toString());
        assertEquals("idx_probe_delivery_due", plan.get("key"));
        var locked = new AtomicReference<JourneyProbeDeliveryRepository.Delivery>(); var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> tx.execute(status -> {
                var row = deliveryRepository.lockDue(slot, sql(BASE)).orElseThrow(); locked.set(row); entered.countDown();
                try { if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("test lock barrier timed out"); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
                assertEquals(1, deliveryRepository.claim(row.key(), row.leaseEpoch(), "first-owner", sql(BASE.plusSeconds(30)), sql(BASE))); return row;
            }));
            try {
                assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
                var b = pool.submit(() -> tx.execute(status -> {
                    var row = deliveryRepository.lockDue(slot, sql(BASE)).orElseThrow();
                    assertNotEquals(locked.get().probeId(), row.probeId());
                    assertEquals(1, deliveryRepository.claim(row.key(), row.leaseEpoch(), "second-owner", sql(BASE.plusSeconds(30)), sql(BASE))); return row;
                }));
                assertNotNull(b.get(5, java.util.concurrent.TimeUnit.SECONDS));
            } finally { release.countDown(); }
            assertNotNull(a.get(5, java.util.concurrent.TimeUnit.SECONDS));
        }
        var key = locked.get().key(); var old = new JourneyProbeDeliveryRepository.Lease("first-owner", 1);
        var advanced = new JourneyProbeDeliveryRepository.Completion(16, JourneyProbeDeliveryState.QUEUED.code(), 0, sql(BASE.plusSeconds(32)), "");
        assertEquals(0, (int) tx.<Integer>execute(status -> deliveryRepository.finish(key, old, advanced, sql(BASE.plusSeconds(31)))));
        assertEquals(1, (int) tx.<Integer>execute(status -> deliveryRepository.claim(key, 1, "recovered-owner", sql(BASE.plusSeconds(61)), sql(BASE.plusSeconds(31)))));
        assertEquals(0, (int) tx.<Integer>execute(status -> deliveryRepository.finish(key, old, advanced, sql(BASE.plusSeconds(32)))));
        var recovered = new JourneyProbeDeliveryRepository.Lease("recovered-owner", 2);
        assertThrows(IllegalStateException.class, () -> tx.executeWithoutResult(status -> {
            assertEquals(1, deliveryRepository.finish(key, recovered, advanced, sql(BASE.plusSeconds(32))));
            throw new IllegalStateException("injected failure after progress write");
        }));
        assertEquals(0, deliveryRepository.find(key).orElseThrow().nextNonce());
        assertEquals(1, (int) tx.<Integer>execute(status -> deliveryRepository.finish(key, recovered, advanced, sql(BASE.plusSeconds(32)))));
        var fresh = new com.acme.marketing.journeyservice.infrastructure.persistence.MybatisJourneyProbeDeliveryRepository(deliveryMapper);
        assertEquals(16, fresh.find(key).orElseThrow().nextNonce());
        tx.executeWithoutResult(status -> fresh.ensure(key, queued)); assertEquals(16, fresh.find(key).orElseThrow().nextNonce());
        assertEquals(0, (int) tx.<Integer>execute(status -> fresh.finish(key, recovered, advanced, sql(BASE.plusSeconds(32)))));
        assertEquals(1, (int) tx.<Integer>execute(status -> fresh.claim(key, 2, "last-owner", sql(BASE.plusSeconds(63)), sql(BASE.plusSeconds(33)))));
        assertEquals(1, (int) tx.<Integer>execute(status -> fresh.finish(key, new JourneyProbeDeliveryRepository.Lease("last-owner", 3),
                new JourneyProbeDeliveryRepository.Completion(32, JourneyProbeDeliveryState.SENT.code(), 0, sql(BASE.plusSeconds(33)), ""), sql(BASE.plusSeconds(33)))));
        assertEquals(1, (int) tx.<Integer>execute(status -> fresh.expireDue(slot, sql(BASE.plusSeconds(121)), 32, "PROBE_EXPIRED")));
        assertEquals(JourneyProbeDeliveryState.SENT.code(), fresh.find(key).orElseThrow().deliveryState());
        assertTrue(tx.execute(status -> fresh.lockDue(slot, sql(BASE.plusSeconds(121)))).isEmpty());
        assertEquals(0, count("mk_journey_execution_probe_ack"));
    }

    @Test void explicitDispatchIsScopedUniqueAndDoesNotResetOrEnqueueOnRegistration() throws Exception {
        var probe = installedProbe("dispatch"); send(post(probe)); assertEquals(0, count("mk_journey_probe_delivery"));
        assertEquals(404, send(request(deliveryPath(probe)).GET()).statusCode());
        assertEquals(403, send(dispatchRequest(probe).setHeader("X-Dev-Permissions", "campaign:read")).statusCode());
        assertEquals(400, send(dispatchRequest(probe).setHeader("X-Dev-Organization-Ids", "other")).statusCode());
        assertEquals(404, send(dispatchRequest(probe).setHeader("X-Dev-Tenant-Id", "another")).statusCode());
        deliveryFaults.failNextEnsure(); assertEquals(500, send(dispatchRequest(probe)).statusCode()); assertEquals(0, count("mk_journey_probe_delivery"));
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> send(dispatchRequest(probe))); var second = pool.submit(() -> send(dispatchRequest(probe)));
            assertDelivery(first.get(), "QUEUED", 0, 0); assertDelivery(second.get(), "QUEUED", 0, 0);
        }
        assertEquals(1, count("mk_journey_probe_delivery"));
        var disabled = new JourneyProbeDeliveryService(deliveryRepository, service, deployment,
                new JourneyProbeDeliverySettings(false, INPUT_TOPIC, deployment), clock, transactions);
        assertThrows(ConflictException.class, () -> asTenant(() -> disabled.dispatch("campaign", probe.probeId())));
        assertEquals("QUEUED", asTenant(() -> disabled.get("campaign", probe.probeId())).state());
        TIME.set(BASE.plusSeconds(121));
        assertCode(409, ReleaseBundleProtocolError.EXECUTION_DELIVERY_EXPIRED.code(), send(dispatchRequest(probe)));
        assertFalse(worker.runOnce()); assertDelivery(send(request(deliveryPath(probe)).GET()), "EXPIRED", 0, 0);
    }

    @Test void realKafkaDispatchAndActualListenerPersistUniqueCoverageWithoutApplying() throws Exception {
        var probe = installedProbe("kafka"); send(post(probe)); send(dispatchRequest(probe));
        // 首次metadata冷启动可能在100ms预算内失败；失败必须持久重试而不能放宽发送预算。
        runUntilCursor(16); var messages = inputMessages(probe, 16);
        assertEquals(16, messages.stream().map(com.acme.marketing.contracts.event.JourneyBundleExecutionProbeMessage::routingNonce).distinct().count());
        for (var message : messages) assertEquals(probe, message.probe());
        kafka.send(OUTPUT_TOPIC, "same-subtask", event(ack(probe, 0, 0))).get(5, java.util.concurrent.TimeUnit.SECONDS);
        kafka.send(OUTPUT_TOPIC, "same-subtask", event(ack(probe, 0, 3))).get(5, java.util.concurrent.TimeUnit.SECONDS);
        awaitAckCount(1); assertState(get(probe), "PENDING", 1);
        kafka.send(OUTPUT_TOPIC, "second-subtask", event(ack(probe, 1, 0))).get(5, java.util.concurrent.TimeUnit.SECONDS);
        awaitAckCount(2); assertState(get(probe), "READY", 2);
        assertTrue(worker.runOnce()); assertDelivery(send(request(deliveryPath(probe)).GET()), "COVERED", 16, 0);
        assertFalse(worker.runOnce()); assertEquals(0, count("mk_journey_runtime_activation")); assertEquals(0, count("mk_journey_dispatch_outbox"));
    }

    @Test void unknownSendAndCommitRollbackRecoverSameNoncesWithFreshWorker() throws Exception {
        var probe = installedProbe("recover"); send(post(probe)); send(dispatchRequest(probe));
        // broker真实接收后模拟调用方丢失确认，下一进程必须读取原cursor重发。
        var uncertain = new JourneyProbeDeliveryWorker(deliveries, (topic, value, start, end) -> {
            assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            sender.send(topic, value, start, end); return false;
        }, clock, new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
        assertTrue(uncertain.runOnce()); assertDelivery(send(request(deliveryPath(probe)).GET()), "RETRY", 0, 1);
        TIME.set(BASE.plusSeconds(2)); deliveryFaults.failNextFinish(); assertThrows(IllegalStateException.class, worker::runOnce);
        var key = deployment.key(tenant, "campaign", probe.probeId()); assertEquals(0, deliveryRepository.find(key).orElseThrow().nextNonce());
        assertFalse(worker.runOnce()); TIME.set(BASE.plusSeconds(33));
        var reconstructed = new JourneyProbeDeliveryService(deliveryRepository, service, deployment, deliverySettings, clock, transactions);
        var recovered = new JourneyProbeDeliveryWorker(reconstructed, sender, clock, new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
        assertTrue(recovered.runOnce()); assertDelivery(send(request(deliveryPath(probe)).GET()), "QUEUED", 16, 0);
        assertTrue(recovered.runOnce()); assertDelivery(send(request(deliveryPath(probe)).GET()), "SENT", 32, 0);
        assertFalse(recovered.runOnce()); assertDelivery(send(dispatchRequest(probe)), "SENT", 32, 0);
        var messages = inputMessages(probe, 48);
        assertTrue(messages.size() >= 48); assertEquals(32, messages.stream().map(com.acme.marketing.contracts.event.JourneyBundleExecutionProbeMessage::routingNonce).distinct().count());
        assertState(get(probe), "PENDING", 0); // 发送确认不能冒充真实子任务覆盖。
    }

    @Test void isolatedBrokerPauseBoundsWaitAndRecoversWithoutSkippingBatch() throws Exception {
        var probe = installedProbe("broker-pause"); send(post(probe)); send(dispatchRequest(probe)); runUntilCursor(16);
        long started = System.nanoTime();
        KAFKA.getDockerClient().pauseContainerCmd(KAFKA.getContainerId()).exec();
        try {
            assertTrue(worker.runOnce());
            assertTrue(Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofSeconds(5)) < 0, "paused broker exceeded bounded batch wait");
            assertDelivery(send(request(deliveryPath(probe)).GET()), "RETRY", 16, 1);
        } finally { KAFKA.getDockerClient().unpauseContainerCmd(KAFKA.getContainerId()).exec(); }
        TIME.set(TIME.get().plusSeconds(2)); assertTrue(worker.runOnce());
        assertDelivery(send(request(deliveryPath(probe)).GET()), "SENT", 32, 0);
        var messages = inputMessages(probe, 32);
        // 迟到成功可能使后一批重复，唯一nonce仍必须覆盖完整原范围。
        assertEquals(32, messages.stream().map(com.acme.marketing.contracts.event.JourneyBundleExecutionProbeMessage::routingNonce).distinct().count());
        assertState(get(probe), "PENDING", 0);
    }

    @Test void retriesStopAtBudgetAndCurrentEvidenceFailureNeverSends() throws Exception {
        var probe = installedProbe("dead"); send(post(probe)); send(dispatchRequest(probe));
        var failing = new JourneyProbeDeliveryWorker(deliveries, (topic, value, start, end) -> false, clock, new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
        for (int failure = 1; failure <= 5; failure++) {
            assertTrue(failing.runOnce()); assertDelivery(send(request(deliveryPath(probe)).GET()), failure == 5 ? "DEAD" : "RETRY", 0, failure);
            TIME.set(BASE.plusSeconds(failure * 20L));
        }
        assertFalse(failing.runOnce()); assertDelivery(send(dispatchRequest(probe)), "DEAD", 0, 5);
        var invalid = installedProbe("changed"); send(post(invalid)); send(dispatchRequest(invalid));
        var changed = new JourneyProbeDeliveryService(deliveryRepository, service, deployment,
                new JourneyProbeDeliverySettings(true, "different-topic", deployment), clock, transactions);
        var neverSend = new JourneyProbeDeliveryWorker(changed, (topic, value, start, end) -> { fail("invalid stored destination must not send"); return true; }, clock,
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
        assertTrue(neverSend.runOnce()); assertDelivery(send(request(deliveryPath(invalid)).GET()), "DEAD", 0, 0);
        assertEquals(0, count("mk_journey_execution_probe_ack"));
    }

    private void runUntilCursor(int expected) throws Exception {
        for (int i = 0; i < 5; i++) {
            assertTrue(worker.runOnce());
            Integer cursor = jdbc.queryForObject("SELECT next_nonce FROM mk_journey_probe_delivery WHERE tenant_id=?", Integer.class, tenant);
            if (cursor == expected) return;
            TIME.set(TIME.get().plusSeconds(2L << i));
        }
        fail("Kafka delivery did not advance within the configured budget");
    }
    private List<com.acme.marketing.contracts.event.JourneyBundleExecutionProbeMessage> inputMessages(JourneyBundleExecutionProbe probe, int expected) {
        var properties = new HashMap<String, Object>(); properties.put("bootstrap.servers", KAFKA.getBootstrapServers());
        properties.put("group.id", UUID.randomUUID().toString()); properties.put("auto.offset.reset", "earliest"); properties.put("enable.auto.commit", false);
        properties.put("key.deserializer", org.apache.kafka.common.serialization.StringDeserializer.class);
        properties.put("value.deserializer", org.apache.kafka.common.serialization.StringDeserializer.class);
        var found = new ArrayList<com.acme.marketing.contracts.event.JourneyBundleExecutionProbeMessage>();
        try (var consumer = new org.apache.kafka.clients.consumer.KafkaConsumer<String, String>(properties)) {
            consumer.subscribe(List.of(INPUT_TOPIC)); long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
            while (found.size() < expected && System.nanoTime() < deadline) {
                for (var record : consumer.poll(Duration.ofMillis(250))) {
                    var event = mapper.readValue(record.value(), com.acme.marketing.contracts.event.JourneyBundleExecutionProbeMessage.class);
                    if (event.probe().equals(probe)) { assertEquals(event.partitionKey(), record.key()); found.add(event); }
                }
            }
        }
        assertTrue(found.size() >= expected, "missing real Kafka records"); return found;
    }
    private void awaitAckCount(int expected) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (count("mk_journey_execution_probe_ack") != expected && System.nanoTime() < deadline) Thread.sleep(25);
        assertEquals(expected, count("mk_journey_execution_probe_ack"));
    }
    private String deliveryPath(JourneyBundleExecutionProbe probe) { return PATH + "/" + probe.campaignId() + "/" + probe.probeId() + "/dispatch"; }
    private HttpRequest.Builder dispatchRequest(JourneyBundleExecutionProbe probe) {
        return request(PATH + "/" + probe.campaignId() + "/" + probe.probeId() + ":dispatch").POST(HttpRequest.BodyPublishers.noBody());
    }
    private void assertDelivery(HttpResponse<String> response, String state, int nonce, int failures) {
        assertEquals(200, response.statusCode(), response.body()); var value = mapper.readTree(response.body());
        assertEquals(state, value.path("state").asString()); assertEquals(nonce, value.path("nextNonce").asInt());
        assertEquals(32, value.path("totalRoutes").asInt()); assertEquals(failures, value.path("failureCount").asInt());
        assertFalse(value.has("leaseOwner")); assertFalse(value.has("destinationTopic"));
    }

    private static String sql(Instant value) { return com.acme.marketing.platform.time.SqlTime.format(value); }

    private <T> T asTenant(java.util.function.Supplier<T> action) throws Exception {
        var request = new org.springframework.mock.web.MockHttpServletRequest("GET", "/api/v1/journey-runtime/execution-probes/campaign/probe");
        request.addHeader("X-Dev-Tenant-Id", tenant); request.addHeader("X-Dev-Actor-Id", "reconstructed-reader");
        request.addHeader("X-Dev-Organization-Ids", "*"); request.addHeader("X-Dev-Shop-Ids", "*"); request.addHeader("X-Dev-Permissions", "runtime:warm");
        var result = new AtomicReference<T>();
        tenantFilter.doFilter(request, new org.springframework.mock.web.MockHttpServletResponse(),
                (req, res) -> result.set(new TransactionTemplate(transactions).execute(status -> action.get())));
        return result.get();
    }

    private JourneyBundleExecutionProbe resign(JourneyBundleExecutionProbe probe, String field, String value) {
        var tree = (tools.jackson.databind.node.ObjectNode) mapper.valueToTree(probe); tree.put(field, value);
        return JourneyBundleExecutionProbeSigner.sign(RELEASE.getPrivate(), mapper.treeToValue(tree, JourneyBundleExecutionProbe.class));
    }
    private JourneyBundleExecutionProbe installedProbe(String id) throws Exception {
        return new JourneyProbeHttpFixture(port, tenant, mapper, RELEASE, COMPILER, http).install(id, JOB, now, BASE);
    }
    private JourneyBundleExecutionAck ack(JourneyBundleExecutionProbe probe, int subtask, int attempt) {
        return JourneyBundleExecutionAckSigner.sign(EXECUTION.getPrivate(), new JourneyBundleExecutionAck(probe, JourneyBundleExecutionAck.EXECUTION_PROTOCOL,
                JOB, 2, subtask, attempt, DIGEST, BASE, "execution", ""));
    }
    private String event(JourneyBundleExecutionAck ack) { return mapper.writeValueAsString(new JourneyBundleExecutionAckEvent(JourneyBundleExecutionAckEvent.EVENT_TYPE, ack)); }
    private List<HttpResponse<String>> concurrent(JourneyBundleExecutionProbe a, JourneyBundleExecutionProbe b) throws Exception {
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var start = new java.util.concurrent.CountDownLatch(1);
            var left = pool.submit(() -> { start.await(); return send(post(a)); }); var right = pool.submit(() -> { start.await(); return send(post(b)); });
            start.countDown(); return List.of(left.get(), right.get());
        }
    }
    private HttpResponse<String> get(JourneyBundleExecutionProbe probe) throws Exception { return send(request(PATH + "/" + probe.campaignId() + "/" + probe.probeId()).GET()); }
    private HttpRequest.Builder post(Object value) { return raw(mapper.writeValueAsString(value)); }
    private HttpRequest.Builder raw(String json) { return request(PATH).POST(HttpRequest.BodyPublishers.ofString(json)); }
    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json").header("X-Dev-Tenant-Id", tenant).header("X-Dev-Actor-Id", "probe-test")
                .header("X-Dev-Organization-Ids", "*").header("X-Dev-Shop-Ids", "*").header("X-Dev-Permissions", "*");
    }
    private HttpResponse<String> send(HttpRequest.Builder request) throws Exception { return http.send(request.build(), HttpResponse.BodyHandlers.ofString()); }
    private void assertState(HttpResponse<String> result, String state, int count) {
        assertEquals(200, result.statusCode(), result.body()); var body = mapper.readTree(result.body());
        assertEquals(state, body.path("state").asString()); assertEquals(count, body.path("acknowledgements").size());
    }
    private void assertCode(int status, String code, HttpResponse<String> response) {
        assertEquals(status, response.statusCode(), response.body()); assertEquals(code, mapper.readTree(response.body()).path("code").asString());
    }
    private int count(String table) { return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE tenant_id=?", Integer.class, tenant); }
    private static String encoded(byte[] value) { return Base64.getEncoder().encodeToString(value); }
}
