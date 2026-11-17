package com.acme.marketing.journeyservice;

import static org.junit.jupiter.api.Assertions.*;

import com.acme.marketing.contracts.artifact.ArtifactAttestation;
import com.acme.marketing.contracts.event.JourneyBundleExecutionAckEvent;
import com.acme.marketing.contracts.release.*;
import com.acme.marketing.journeyservice.application.*;
import com.acme.marketing.journeyservice.infrastructure.persistence.MybatisJourneyExecutionProbeRepository;
import com.acme.marketing.journeyservice.infrastructure.persistence.mapper.JourneyExecutionProbeMapper;
import com.acme.marketing.platform.crypto.Digests;
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
    private static final org.testcontainers.mysql.MySQLContainer MYSQL = mysql();
    private static org.testcontainers.mysql.MySQLContainer mysql() {
        var value = new org.testcontainers.mysql.MySQLContainer("mysql:8.4.11")
                .withCommand("--performance-schema=OFF", "--innodb-buffer-pool-size=64M", "--max-connections=30")
                .withTmpFs(Map.of("/var/lib/mysql", "rw"))
                .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withMemory(512L * 1024 * 1024).withMemorySwap(512L * 1024 * 1024))
                .withStartupTimeout(Duration.ofMinutes(5)); value.start(); return value;
    }
    public static final class DatabaseCleanup extends org.springframework.test.context.support.AbstractTestExecutionListener {
        @Override public int getOrder() { return org.springframework.core.Ordered.HIGHEST_PRECEDENCE; }
        @Override public void afterTestClass(org.springframework.test.context.TestContext context) { MYSQL.stop(); }
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
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
    @LocalServerPort int port;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired JourneyExecutionProbeService service;
    @Autowired FaultRepository repository;
    @Autowired BundleDirectiveRepository directives;
    @Autowired JourneyRuntimeReleaseService releases;
    @Autowired Clock clock;
    @Autowired PlatformTransactionManager transactions;
    @Autowired com.acme.marketing.platform.web.TenantContextFilter tenantFilter;
    private final String tenant = "probe-" + UUID.randomUUID();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final Instant now = BASE.minusSeconds(1);
    @BeforeEach void resetClock() { TIME.set(BASE); repository.resetFaults(); }

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
        byte[] payload = mapper.writeValueAsBytes(new com.acme.marketing.journey.JourneyPlan("definition", 1, "end",
                Map.of("end", new com.acme.marketing.journey.JourneyNode("end", com.acme.marketing.journey.JourneyNode.Type.END, Map.of(), Map.of())), 5, 1, Duration.ofDays(1)));
        var member = member(payload, "journey", "main");
        var installed = send(request("/api/v1/journey-runtime/manifest").PUT(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(
                Map.of("releaseKeyId", "release-key", "manifest", member, "artifactBase64", encoded(payload))))));
        assertEquals(200, installed.statusCode(), installed.body());
        var members = List.of(member, member(new byte[]{4}, "decision", "other"));
        var bundle = ReleaseBundleManifestSigner.sign(RELEASE.getPrivate(), new ReleaseBundleManifest("bundle", new TenantId(tenant), "campaign", 1,
                "local", "cell-a", ReleaseBundleManifest.contentHash(tenant, "campaign", members), members, now, now.plusSeconds(600), "release-key", ""));
        var directive = ReleaseBundleActivationSigner.sign(RELEASE.getPrivate(), new ReleaseBundleActivationDirective("directive", bundle, 1, "ACTIVATE", "org", "shop", now, "operator", "release-key", ""));
        var received = send(request("/api/v1/journey-runtime/release-bundle-directives").POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(directive))));
        assertEquals(200, received.statusCode(), received.body());
        return JourneyBundleExecutionProbeSigner.sign(RELEASE.getPrivate(), new JourneyBundleExecutionProbe(id, new TenantId(tenant), "campaign", "local", "cell-a", "main",
                member.manifestId(), 1, directive.signature(), JOB, 2, "participation", BASE, BASE.plusSeconds(120), "release-key", ""));
    }
    private ReleaseManifest member(byte[] payload, String runtime, String namespace) {
        String id = runtime + "-1", checksum = "sha256:" + Digests.sha256Hex(payload), source = "sha256:" + "b".repeat(64);
        String signature = ArtifactAttestation.sign(COMPILER.getPrivate(), tenant, id, "definition", 1, "JOURNEY_PLAN", "marketing-journey-plan/1", checksum, source);
        var artifact = new ArtifactReference(id, "JOURNEY_PLAN", "s3://artifacts/" + id, checksum, source, "compiler-key", signature, "marketing-journey-plan/1", "definition", 1);
        return new ReleaseManifestSigner().sign(new ReleaseManifest(id, new TenantId(tenant), "local", "cell-a", runtime, namespace, 1, 0, List.of(), List.of(), List.of(artifact),
                Map.of(), 0, now, now.plusSeconds(600), "author", List.of("case"), now, ""), RELEASE.getPrivate());
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
