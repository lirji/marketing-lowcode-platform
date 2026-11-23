package com.acme.marketing.decision;

import static org.junit.jupiter.api.Assertions.*;

import com.acme.marketing.contracts.artifact.ArtifactAttestation;
import com.acme.marketing.contracts.release.*;
import com.acme.marketing.decision.application.*;
import com.acme.marketing.decision.application.DecisionApplicationService.BundleContext;
import com.acme.marketing.decision.application.DecisionApplicationService.DecisionRequest;
import com.acme.marketing.decision.model.*;
import com.acme.marketing.decision.runtime.*;
import com.acme.marketing.platform.crypto.Digests;
import com.acme.marketing.platform.crypto.Ed25519;
import com.acme.marketing.platform.error.ConflictException;
import com.acme.marketing.platform.identity.TenantId;
import com.acme.marketing.platform.identity.TenantScope;
import java.net.URI;
import java.net.http.*;
import java.security.KeyPair;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

/** 真实隔离数据库/Redis及HTTP验证固定组合求值；没有生产准入签发或整体切换。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@org.springframework.test.context.TestExecutionListeners(listeners = DecisionBundleEvaluationIntegrationTest.DatabaseCleanup.class,
        mergeMode = org.springframework.test.context.TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS)
class DecisionBundleEvaluationIntegrationTest {
    private static final KeyPair RELEASE = Ed25519.generateKeyPair(), COMPILER = Ed25519.generateKeyPair(), PARTICIPATION = Ed25519.generateKeyPair();
    private static final KeyPair PIPELINE = Ed25519.generateKeyPair(), REPLICA = Ed25519.generateKeyPair(), FLINK = Ed25519.generateKeyPair();
    private static final String IMAGE = "sha256:" + "a".repeat(64), JAR = "sha256:" + "b".repeat(64), JOB = "c".repeat(32);
    private static final String APPLICATION = "/api/v1/decisions/runtime/release-bundle-applications/campaign/";
    private static final Instant NOW = Instant.now();
    private static final MutableClock TIME = new MutableClock();
    private static final org.testcontainers.mysql.MySQLContainer MYSQL = mysql();
    private static final org.testcontainers.containers.GenericContainer<?> REDIS = new org.testcontainers.containers.GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379).withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withMemory(128L * 1024 * 1024));
    private static LettuceConnectionFactory connections;
    private static StringRedisTemplate redis;
    private static org.testcontainers.mysql.MySQLContainer mysql() {
        var value = new org.testcontainers.mysql.MySQLContainer("mysql:8.4.11")
                .withCommand("--performance-schema=OFF", "--innodb-buffer-pool-size=64M", "--max-connections=30")
                .withTmpFs(Map.of("/var/lib/mysql", "rw"))
                .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withMemory(512L * 1024 * 1024).withMemorySwap(512L * 1024 * 1024))
                .withStartupTimeout(Duration.ofMinutes(5));
        value.start(); return value;
    }
    /** 先关闭Spring后台任务和连接池，再释放本测试独占数据库。 */
    public static final class DatabaseCleanup extends org.springframework.test.context.support.AbstractTestExecutionListener {
        @Override public int getOrder() { return org.springframework.core.Ordered.HIGHEST_PRECEDENCE; }
        @Override public void afterTestClass(org.springframework.test.context.TestContext context) { MYSQL.stop(); }
    }
    @TestConfiguration static class TimeConfig { @Bean @Primary Clock testClock() { return TIME; } }
    private static final class MutableClock extends Clock {
        private volatile Instant now = NOW;
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", MYSQL::getJdbcUrl); r.add("spring.datasource.username", MYSQL::getUsername);
        r.add("spring.datasource.password", MYSQL::getPassword); r.add("spring.flyway.user", MYSQL::getUsername); r.add("spring.flyway.password", MYSQL::getPassword);
        r.add("marketing.release.trusted-key-id", () -> "release"); r.add("marketing.release.public-key-base64", () -> encoded(RELEASE.getPublic().getEncoded()));
        r.add("marketing.deployment-inventory.tenant", () -> "deploy");
        r.add("marketing.deployment-inventory.actor", () -> "pipeline");
        r.add("marketing.deployment-inventory.trusted-public-keys", () -> "pipeline=" + encoded(PIPELINE.getPublic().getEncoded()));
        r.add("marketing.execution.instance-id", () -> "pod-one"); r.add("marketing.execution.image-digest", () -> IMAGE);
        r.add("marketing.execution.key-id", () -> "pod-key");
        r.add("marketing.execution.private-key", () -> encoded(REPLICA.getPrivate().getEncoded()));
        r.add("marketing.execution.trusted-public-keys", () -> "pod-key=" + encoded(REPLICA.getPublic().getEncoded()));
        r.add("marketing.execution.journey-public-keys", () -> "flink=" + encoded(FLINK.getPublic().getEncoded()));
        String target = "{\"jobId\":\"" + JOB + "\",\"parallelism\":2,\"buildDigest\":\"" + JAR + "\"}";
        r.add("marketing.execution.journey-targets", () -> "{\"other\":" + target + ",\"mail\":" + target + "}");
        r.add("marketing.participation.signing-key-id", () -> "participation");
        r.add("marketing.participation.private-key-base64", () -> encoded(PARTICIPATION.getPrivate().getEncoded()));
        r.add("marketing.participation.signing-public-key-base64", () -> encoded(PARTICIPATION.getPublic().getEncoded()));
        r.add("marketing.compiler.trusted-key-id", () -> "compiler"); r.add("marketing.compiler.public-key-base64", () -> encoded(COMPILER.getPublic().getEncoded()));
        r.add("marketing.participation.trusted-key-id", () -> "participation"); r.add("marketing.participation.public-key-base64", () -> encoded(PARTICIPATION.getPublic().getEncoded()));
    }
    @BeforeAll static void startRedis() {
        REDIS.start(); connections = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        connections.afterPropertiesSet(); connections.start(); redis = new StringRedisTemplate(connections);
    }
    @AfterAll static void stopRedis() { connections.destroy(); REDIS.stop(); }
    @BeforeEach void resetClock() { TIME.now = NOW; }
    @LocalServerPort int port;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired RuntimeManifestRegistry runtime;
    @Autowired DecisionApplicationService decisions;
    @Autowired AudienceMembershipProjection audiences;
    @Autowired DecisionKillSwitchRegistry kills;
    @Autowired DecisionCommandRepository commands;
    @Autowired BundleDirectiveRepository directives;
    @Autowired PlatformTransactionManager transactions;
    @Autowired PinnedReleaseBundleActivationVerifier activationVerifier;
    private final String tenant = "evaluation-" + UUID.randomUUID();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    @Test void exactParticipationSurvivesNewerReceiptAndConcurrentReplayWithoutLegacyActivation() throws Exception {
        var first = installed(1); var input = decision(proof(first, "binding", "participation", "subject"));
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var one = pool.submit(() -> send(input)); var two = pool.submit(() -> send(input));
            var left = one.get(); var right = two.get(); assertEquals(200, left.statusCode(), left.body());
            assertEquals(mapper.readTree(left.body()), mapper.readTree(right.body())); assertEquals(1, mapper.readTree(left.body()).path("generation").asLong());
        }
        installed(2);
        assertEquals(1, mapper.readTree(send(copy(input, UUID.randomUUID().toString(), input.bundleContext(), "subject", "org", "shop")).body()).path("generation").asLong());
        assertEquals(0, jdbc.queryForObject("SELECT desired_generation FROM mk_runtime_slot WHERE tenant_id=?", Long.class, tenant));
        var legacy = new DecisionRequest("legacy", "legacy-key", "org", "order", "subject", "CHECKOUT", TIME.instant(), cart("shop"), Set.of());
        assertFalse(mapper.writeValueAsString(legacy).contains("bundleContext"));
        assertEquals(legacy, mapper.readValue(mapper.writeValueAsString(legacy), DecisionRequest.class));
        assertCode(404, "ACTIVE_MANIFEST_NOT_FOUND", send(builder("/api/v1/decisions:evaluate").POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(legacy)))));
        assertCode(400, "RELEASE_BUNDLE_PARTICIPATION_INVALID", send(legacy));
        assertCode(400, "RELEASE_BUNDLE_PARTICIPATION_INVALID", send(builder("/api/v1/decisions:evaluate").POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(input)))));
    }

    @Test void identityScopeSignaturesAndMissingTrustRejectBeforeReturningAQuote() throws Exception {
        var source = installed(1); var proof = proof(source, "binding", "participation", "subject"); var input = decision(proof);
        for (var changed : List.of(copy(input, "changed-subject", input.bundleContext(), "other", "org", "shop"),
                copy(input, "changed-org", input.bundleContext(), "subject", "other", "shop"),
                copy(input, "changed-shop", input.bundleContext(), "subject", "org", "other")))
            assertCode(409, "RELEASE_BUNDLE_PARTICIPATION_INVALID", send(changed));
        var bad = ReleaseBundleParticipationSigner.sign(RELEASE.getPrivate(), proof);
        assertCode(409, "RELEASE_BUNDLE_PARTICIPATION_INVALID", send(decision(bad)));
        assertCode(409, "RELEASE_BUNDLE_PARTICIPATION_INVALID", send(request(input).setHeader("X-Dev-Tenant-Id", "other")));
        assertCode(403, "PERMISSION_DENIED", send(request(input).setHeader("X-Dev-Permissions", "trace:read")));
        var missingTrust = new DecisionBundlePlanResolver(directives, runtime, mapper, activationVerifier, "", "", "", "local", "cell-a", "main");
        assertThrows(ConflictException.class, () -> missingTrust.resolve(scope(), input, TIME.instant()));
        assertThrows(IllegalArgumentException.class, () -> new BundleContext("other-campaign", "participation", proof));
        assertThrows(IllegalArgumentException.class, () -> new BundleContext("campaign", "other-participation", proof));
    }

    @Test void jdbcAndRedisBindIdempotencyToTheFullProofAndRecheckCurrentKillOnReplay() throws Exception {
        var source = installed(1);
        for (String mode : List.of("JDBC", "REDIS")) {
            var executor = executor(mode); var input = decision(proof(source, "binding-" + mode, "participation-" + mode, "subject"));
            var first = executor.execute(scope(), input); assertEquals(first, executor.execute(scope(), input));
            var changed = decision(proof(source, "different-" + mode, "participation-" + mode, "subject"));
            var conflict = copy(input, input.idempotencyKey(), changed.bundleContext(), "subject", "org", "shop");
            assertEquals("IDEMPOTENCY_PAYLOAD_CONFLICT", assertThrows(ConflictException.class, () -> executor.execute(scope(), conflict)).code());
            applyKill(true, mode.equals("JDBC") ? 1 : 3);
            assertEquals("MARKETING_KILL_SWITCH_ENABLED", assertThrows(ConflictException.class, () -> executor.execute(scope(), input)).code());
            applyKill(false, mode.equals("JDBC") ? 2 : 4);
            assertEquals(first, executor.execute(scope(), input));
            var revoked = new DecisionBundlePlanResolver(directives, runtime, mapper, activationVerifier, "", "", "", "local", "cell-a", "main");
            var newProcess = new DecisionApplicationService(runtime, audiences,
                    new com.acme.marketing.platform.crypto.SigningKeyRing("unused", Ed25519.generateKeyPair()), TIME,
                    new com.acme.marketing.platform.isolation.TenantBulkhead(1, Duration.ofMillis(1)), kills::requireEnabled, revoked);
            assertEquals("RELEASE_BUNDLE_PARTICIPATION_INVALID", assertThrows(ConflictException.class,
                    () -> executor(mode, newProcess).execute(scope(), input)).code());
            assertCode(403, "PERMISSION_DENIED", send(request(input).setHeader("X-Dev-Permissions", "trace:read")));
        }
    }

    @Test void historicalManifestExpiryDoesNotExtendCurrentPolicyValidity() throws Exception {
        var source = installed(1); var proof = proof(source, "binding", "participation", "subject");
        TIME.now = NOW.plusSeconds(601);
        var response = send(decision(proof)); assertEquals(200, response.statusCode(), response.body());
        assertFalse(mapper.readTree(response.body()).path("offerToken").asString().isEmpty());
        TIME.now = NOW.plusSeconds(3601);
        var expiredPolicy = send(decision(proof)); assertEquals(200, expiredPolicy.statusCode(), expiredPolicy.body());
        assertEquals("", mapper.readTree(expiredPolicy.body()).path("offerToken").asString());
        assertEquals("OUTSIDE_VALIDITY", mapper.readTree(expiredPolicy.body()).path("candidates").get(0).path("outcome").asString());
    }

    @Test void corruptedOrMissingPersistentSourceAndArtifactNeverFallBackToHotGeneration() throws Exception {
        var source = installed(1); var input = decision(proof(source, "binding", "participation", "subject"));
        assertEquals(200, send(input).statusCode());
        jdbc.update("UPDATE mk_runtime_generation SET artifact_payload=? WHERE tenant_id=?", new byte[]{0}, tenant);
        assertCode(409, "POLICY_ARTIFACT_CHECKSUM_MISMATCH", send(input));
        jdbc.update("UPDATE mk_runtime_bundle_directive SET directive_json='null' WHERE tenant_id=?", tenant);
        assertCode(409, "RELEASE_BUNDLE_ACTIVATION_STORED_INVALID", send(input));
        jdbc.update("DELETE FROM mk_runtime_bundle_head WHERE tenant_id=?", tenant);
        jdbc.update("DELETE FROM mk_runtime_bundle_directive WHERE tenant_id=?", tenant);
        assertCode(409, "RELEASE_BUNDLE_ACTIVATION_NOT_RECORDED", send(input));
    }

    @Test void batchUsesTheSameBoundPathAndLegacyFingerprintOmitsTheOptionalField() throws Exception {
        var source = installed(1); var input = decision(proof(source, "binding", "participation", "subject"));
        var result = send(builder("/api/v1/decisions/bundle:batch-evaluate").POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(List.of(input)))));
        assertEquals(200, result.statusCode(), result.body()); assertEquals(1, mapper.readTree(result.body()).get(0).path("generation").asLong());
        var legacy = new DecisionRequest(input.requestId(), input.idempotencyKey(), "org", "order", "subject", "CHECKOUT", input.occurredAt(), cart("shop"), Set.of());
        var legacyShape = new LinkedHashMap<String, Object>();
        legacyShape.put("requestId", legacy.requestId()); legacyShape.put("idempotencyKey", legacy.idempotencyKey()); legacyShape.put("organizationId", legacy.organizationId());
        legacyShape.put("orderId", legacy.orderId()); legacyShape.put("subjectToken", legacy.subjectToken()); legacyShape.put("channel", legacy.channel());
        legacyShape.put("occurredAt", legacy.occurredAt()); legacyShape.put("cart", legacy.cart()); legacyShape.put("audienceSnapshotIds", legacy.audienceSnapshotIds());
        assertEquals(mapper.writeValueAsString(legacyShape), mapper.writeValueAsString(legacy));
    }

    @Test void inventoryChangesAndConcurrentWholeBundleApplicationPreserveBindingsAndRollback() throws Exception {
        var first = installed(1);
        assertCode(403, "PERMISSION_DENIED", send(builder(APPLICATION + "1/apply").setHeader("X-Dev-Permissions", "runtime:activate")
                .POST(HttpRequest.BodyPublishers.ofString("{}"))));
        var one = new DecisionDeploymentInventory.Replica("pod-one", IMAGE, "pod-key");
        var two = new DecisionDeploymentInventory.Replica("pod-two", IMAGE, "other-key");
        long deployment = nextDeployment();
        registerInventory(deployment, List.of(one, two));
        assertCode(404, "RELEASE_BUNDLE_ADMISSION_NOT_APPLIED", send(builder(APPLICATION + "1").GET()));
        assertCode(409, "RELEASE_BUNDLE_EXECUTION_COVERAGE_INCOMPLETE",
                send(builder(APPLICATION + "1?confirmAbsentAfter=" + NOW.plusSeconds(10)).GET()));
        assertCode(404, "RELEASE_BUNDLE_ADMISSION_NOT_APPLIED", send(builder(APPLICATION + "1?confirmAbsentAfter=" + NOW).GET()));
        assertEquals(200, post(APPLICATION + "1/attest", null).statusCode());
        var proofs = journeyProofs(first);
        assertCode(409, "RELEASE_BUNDLE_EXECUTION_COVERAGE_INCOMPLETE", apply(1, 0, proofs));
        registerInventory(deployment + 1, List.of(one));
        assertCode(409, "RELEASE_BUNDLE_EXECUTION_COVERAGE_INCOMPLETE", apply(1, 0, proofs));
        var receipt = post(APPLICATION + "1/attest", null);
        assertEquals(200, receipt.statusCode(), receipt.body());
        assertEquals(deployment + 1, mapper.readValue(receipt.body(), DecisionExecutionReceipt.class).deploymentSequence());
        TIME.now = NOW.plusSeconds(1);
        assertEquals(mapper.readTree(receipt.body()), mapper.readTree(post(APPLICATION + "1/attest", null).body()));
        String storedAck = jdbc.queryForObject("SELECT receipt_json FROM mk_decision_execution_receipt WHERE tenant_id=? AND deployment_sequence=?",
                String.class, tenant, deployment + 1);
        var tampered = (tools.jackson.databind.node.ObjectNode) mapper.readTree(storedAck);
        tampered.put("participationKeyId", "wrong-role");
        jdbc.update("UPDATE mk_decision_execution_receipt SET receipt_json=? WHERE tenant_id=? AND deployment_sequence=?",
                mapper.writeValueAsString(tampered), tenant, deployment + 1);
        assertCode(409, "RELEASE_BUNDLE_EXECUTION_ACK_INVALID", apply(1, 0, proofs));
        assertCode(409, "RELEASE_BUNDLE_EXECUTION_ACK_INVALID", post(APPLICATION + "1/attest", null));
        jdbc.update("UPDATE mk_decision_execution_receipt SET receipt_json=? WHERE tenant_id=? AND deployment_sequence=?",
                storedAck, tenant, deployment + 1);
        var sample = proofs.getFirst();
        var oldProtocol = JourneyBundleExecutionAckSigner.sign(FLINK.getPrivate(), new JourneyBundleExecutionAck(sample.probe(),
                JourneyBundleExecutionAck.EXECUTION_PROTOCOL, JOB, 2, 0, 0, JAR, NOW, "flink", ""));
        assertCode(409, "RELEASE_BUNDLE_EXECUTION_ACK_INVALID", apply(1, 0, List.of(oldProtocol, proofs.getLast())));
        assertCode(409, "RELEASE_BUNDLE_EXECUTION_COVERAGE_INCOMPLETE", apply(1, 0, List.of(proofs.getFirst(), proofs.getFirst())));
        assertCode(409, "RELEASE_BUNDLE_EXECUTION_ACK_INVALID", apply(1, 0, List.of(
                JourneyBundleExecutionAckSigner.sign(PIPELINE.getPrivate(), proofs.getFirst()), proofs.getLast())));
        assertCode(409, "RELEASE_BUNDLE_APPLICATION_DEADLINE", post(APPLICATION + "1/apply",
                new BundleApplicationService.ApplyRequest(0L, proofs, NOW)));
        // 在真实历史写入之后使首次指针写入失败；门禁标记、签名证据与指针必须一起回滚。
        jdbc.execute("ALTER TABLE mk_bundle_admission_pointer ADD CONSTRAINT application_test_fault CHECK (tenant_id <> '" + tenant + "')");
        try {
            assertEquals(500, apply(1, 0, proofs).statusCode());
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM mk_bundle_applied_authorization WHERE tenant_id=?", Integer.class, tenant));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM mk_runtime_slot WHERE tenant_id=? AND bundle_applied=TRUE", Integer.class, tenant));
        } finally {
            jdbc.execute("ALTER TABLE mk_bundle_admission_pointer DROP CHECK application_test_fault");
        }
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> apply(1, 0, proofs)); var b = pool.submit(() -> apply(1, 0, proofs));
            for (var future : List.of(a, b)) {
                var result = future.get(); assertEquals(200, result.statusCode(), result.body());
                assertEquals("APPLIED", mapper.readTree(result.body()).path("state").asString());
            }
        }
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM mk_bundle_applied_authorization WHERE tenant_id=?", Integer.class, tenant));
        String evidence = jdbc.queryForObject("SELECT execution_evidence FROM mk_bundle_applied_authorization WHERE tenant_id=?", String.class, tenant);
        assertEquals(1, mapper.readTree(evidence).path("decision").size());
        assertEquals(2, mapper.readTree(evidence).path("journey").size());
        var old = admit("first"); assertEquals(first.bundle().bundleId(), old.bundleId());
        var legacy = new DecisionRequest("legacy", "legacy-key", "org", "order", "subject", "CHECKOUT", NOW, cart("shop"), Set.of());
        assertCode(409, "RELEASE_BUNDLE_SLOT_MANAGED", post("/api/v1/decisions:evaluate", legacy));
        var second = installed(2);
        assertEquals(200, post(APPLICATION + "2/attest", null).statusCode());
        assertCode(409, "RELEASE_BUNDLE_ACTIVATION_SEQUENCE_CONFLICT", apply(2, 0, journeyProofs(second)));
        var applied = apply(2, 1, journeyProofs(second)); assertEquals(200, applied.statusCode(), applied.body());
        var newer = admit("second");
        assertEquals(2, newer.bundleVersion()); assertEquals(old, admit("first"));
        assertEquals(1, mapper.readTree(send(decision(old)).body()).path("generation").asLong());
        var rollback = ReleaseBundleActivationSigner.sign(RELEASE.getPrivate(), new ReleaseBundleActivationDirective("back", first.bundle(),
                3, "ROLLBACK", "org", "shop", NOW, "operator", "release", ""));
        assertEquals(200, post("/api/v1/decisions/runtime/release-bundle-directives", rollback).statusCode());
        assertEquals(200, post(APPLICATION + "3/attest", null).statusCode());
        var restored = apply(3, 2, journeyProofs(rollback)); assertEquals(200, restored.statusCode(), restored.body());
        assertEquals(1, admit("after-rollback").bundleVersion()); assertEquals(newer, admit("second"));
        assertEquals(3, mapper.readTree(send(builder(APPLICATION + "1").GET()).body()).path("currentSequence").asLong());
        TIME.now = NOW.plusSeconds(601);
        assertEquals(200, apply(3, 2, List.of()).statusCode());
    }

    @Test void journeyOnlyBundleRequiresAdmissionReplicaAndEveryNamespaceAndRejectsUnappliedRollback() throws Exception {
        var peers = List.of(member(1, new byte[]{1}, "journey", "mail"), member(2, new byte[]{2}, "journey", "other"));
        var bundle = ReleaseBundleManifestSigner.sign(RELEASE.getPrivate(), new ReleaseBundleManifest("journeys", new TenantId(tenant),
                "campaign", 1, "local", "cell-a", ReleaseBundleManifest.contentHash(tenant, "campaign", peers), peers, NOW.minusSeconds(2),
                NOW.plusSeconds(600), "release", ""));
        var url = "/api/v1/decisions/runtime/release-bundle-directives";
        registerInventory(nextDeployment(), List.of(new DecisionDeploymentInventory.Replica("pod-one", IMAGE, "pod-key")));
        var rollback = ReleaseBundleActivationSigner.sign(RELEASE.getPrivate(), new ReleaseBundleActivationDirective("unapplied", bundle, 1,
                "ROLLBACK", "org", "shop", NOW, "operator", "release", ""));
        assertEquals(200, post(url, rollback).statusCode());
        assertCode(409, "RELEASE_BUNDLE_ROLLBACK_NOT_APPLIED", apply(1, 0, List.of()));
        var source = ReleaseBundleActivationSigner.sign(RELEASE.getPrivate(), new ReleaseBundleActivationDirective("apply-journeys", bundle, 2,
                "ACTIVATE", "org", "shop", NOW, "operator", "release", ""));
        assertEquals(200, post(url, source).statusCode());
        var acks = peers.stream().flatMap(peer -> journeyProofs(source, peer).stream()).toList();
        assertCode(409, "RELEASE_BUNDLE_EXECUTION_COVERAGE_INCOMPLETE", apply(2, 0, acks));
        var ack = post(APPLICATION + "2/attest", null); assertEquals(200, ack.statusCode(), ack.body());
        assertCode(409, "RELEASE_BUNDLE_EXECUTION_COVERAGE_INCOMPLETE", apply(2, 0, acks.subList(0, 2)));
        var result = apply(2, 0, acks); assertEquals(200, result.statusCode(), result.body());
        assertEquals(source.bundle().bundleId(), admit("new-journey").bundleId());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM mk_runtime_slot WHERE tenant_id=? AND bundle_applied=TRUE",
                Integer.class, tenant));
    }

    private long nextDeployment() throws Exception {
        var current = send(builder("/api/v1/decisions/runtime/deployment-inventory").setHeader("X-Dev-Tenant-Id", "deploy")
                .setHeader("X-Dev-Actor-Id", "pipeline").GET());
        assertEquals(200, current.statusCode(), current.body()); return mapper.readTree(current.body()).path("sequence").asLong() + 1;
    }

    private HttpResponse<String> post(String path, Object body) throws Exception {
        return send(builder(path).POST(body == null ? HttpRequest.BodyPublishers.noBody() :
                HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))));
    }
    private HttpResponse<String> apply(long sequence, long expected, List<JourneyBundleExecutionAck> acks) throws Exception {
        return post(APPLICATION + sequence + "/apply", new BundleApplicationService.ApplyRequest(expected, acks, null));
    }
    private ReleaseBundleParticipation admit(String id) throws Exception {
        var response = post("/api/v1/decisions/bundle-participations", new ReleaseBundleAdmissionRequest("campaign", id, "subject"));
        assertEquals(200, response.statusCode(), response.body()); return mapper.readValue(response.body(), ReleaseBundleParticipation.class);
    }
    private void registerInventory(long sequence, List<DecisionDeploymentInventory.Replica> replicas) throws Exception {
        var inv = new DecisionDeploymentInventory("local", "cell-a", "main", sequence, NOW.minusSeconds(1), NOW.plusSeconds(240), replicas,
                "pipeline", "").sign(PIPELINE.getPrivate());
        var response = send(builder("/api/v1/decisions/runtime/deployment-inventory").setHeader("X-Dev-Tenant-Id", "deploy")
                .setHeader("X-Dev-Actor-Id", "pipeline").PUT(HttpRequest.BodyPublishers.ofString(
                        com.acme.marketing.decision.deployment.DecisionInventoryJson.write(inv))));
        assertEquals(200, response.statusCode(), response.body());
    }
    private List<JourneyBundleExecutionAck> journeyProofs(ReleaseBundleActivationDirective source) {
        return journeyProofs(source, source.bundle().members().stream().filter(m -> m.runtime().equals("journey")).findFirst().orElseThrow());
    }
    private List<JourneyBundleExecutionAck> journeyProofs(ReleaseBundleActivationDirective source, ReleaseManifest peer) {
        var probe = JourneyBundleExecutionProbeSigner.sign(RELEASE.getPrivate(), new JourneyBundleExecutionProbe("probe-" + source.activationSequence(),
                new TenantId(tenant), "campaign", "local", "cell-a", peer.namespace(), peer.manifestId(), source.activationSequence(), source.signature(),
                JOB, 2, "participation", NOW, NOW.plusSeconds(120), "release", ""));
        return java.util.stream.IntStream.range(0, 2).mapToObj(index -> JourneyBundleExecutionAckSigner.sign(FLINK.getPrivate(),
                new JourneyBundleExecutionAck(probe, JourneyBundleExecutionAck.BOUND_START_PROTOCOL, JOB, 2, index, 0, JAR, NOW, "flink", ""))).toList();
    }

    private ReleaseBundleActivationDirective installed(long generation) throws Exception {
        var candidate = new OfferCandidate("offer-" + generation, "benefit", PriceStage.PLATFORM,
                new PromotionFormula(PromotionKind.FIXED_OFF, 5000, 2000, 0, 2000, 1), Set.of(), "discount", HitPolicy.BEST, 1, 100, Set.of(),
                List.of(new FundingRule("PLATFORM", "platform", 10000)), "OFFER_MATCHED");
        var policy = new OfferPolicy(candidate, NOW.minusSeconds(10), NOW.plusSeconds(3600), Set.of("CHECKOUT"), Set.of(), Set.of(), Set.of("shop"), "");
        byte[] payload = mapper.writeValueAsBytes(new OfferPolicyArtifact(List.of(policy)));
        var member = member(generation, payload, "decision", "main"); runtime.install(tenant, "release", member, payload);
        var peer = member(generation, payload, "journey", "other"); var members = List.of(member, peer);
        var bundle = ReleaseBundleManifestSigner.sign(RELEASE.getPrivate(), new ReleaseBundleManifest("bundle-" + generation, new TenantId(tenant), "campaign", generation,
                "local", "cell-a", ReleaseBundleManifest.contentHash(tenant, "campaign", members), members, NOW.minusSeconds(2), NOW.plusSeconds(600), "release", ""));
        var directive = ReleaseBundleActivationSigner.sign(RELEASE.getPrivate(), new ReleaseBundleActivationDirective("directive-" + generation, bundle, generation,
                "ACTIVATE", "org", "shop", NOW.minusSeconds(1), "operator", "release", ""));
        var result = send(builder("/api/v1/decisions/runtime/release-bundle-directives").POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(directive))));
        assertEquals(200, result.statusCode(), result.body()); return directive;
    }
    private ReleaseManifest member(long generation, byte[] payload, String runtimeName, String namespace) {
        String id = runtimeName + '-' + generation, checksum = "sha256:" + Digests.sha256Hex(payload), source = "sha256:" + "b".repeat(64);
        String signature = ArtifactAttestation.sign(COMPILER.getPrivate(), tenant, id, "definition", generation, "OFFER_POLICY", "marketing-offer-policy/1", checksum, source);
        var artifact = new ArtifactReference(id, "OFFER_POLICY", "s3://artifacts/" + id, checksum, source, "compiler", signature, "marketing-offer-policy/1", "definition", generation);
        return new ReleaseManifestSigner().sign(new ReleaseManifest(id, new TenantId(tenant), "local", "cell-a", runtimeName, namespace, generation, 0,
                List.of(), List.of(), List.of(artifact), Map.of(), 0, NOW.minusSeconds(3), NOW.plusSeconds(600), "author", List.of("case"), NOW.minusSeconds(3), ""), RELEASE.getPrivate());
    }
    private ReleaseBundleParticipation proof(ReleaseBundleActivationDirective source, String binding, String participation, String subject) {
        var bundle = source.bundle();
        return ReleaseBundleParticipationSigner.sign(PARTICIPATION.getPrivate(), new ReleaseBundleParticipation(binding, bundle.tenantId(), bundle.campaignId(),
                bundle.environment(), bundle.cell(), participation, ReleaseBundleParticipation.subjectHash(bundle.tenantId(), subject), source.directiveId(), source.activationSequence(),
                source.signature(), bundle.bundleId(), bundle.version(), bundle.contentHash(), NOW, "participation", ""));
    }
    private DecisionRequest decision(ReleaseBundleParticipation proof) {
        return new DecisionRequest("request-" + UUID.randomUUID(), "idem-" + UUID.randomUUID(), "org", "order", "subject", "CHECKOUT", TIME.instant(), cart("shop"), Set.of(),
                new BundleContext(proof.campaignId(), proof.participationId(), proof));
    }
    private DecisionRequest copy(DecisionRequest input, String key, BundleContext context, String subject, String organization, String shop) {
        return new DecisionRequest(input.requestId(), key, organization, input.orderId(), subject, input.channel(), TIME.instant(), cart(shop), Set.of(), context);
    }
    private Cart cart(String shop) { return new Cart("CNY", List.of(new CartLine("line", "sku", shop, "", "", 1, new Money("CNY", 10000), Money.zero("CNY"), false))); }
    private TenantScope scope() { return new TenantScope(new TenantId(tenant), Set.of("org"), Set.of("shop"), "operator", Set.of("decision:evaluate")); }
    private DecisionRequestExecutor executor(String mode) { return executor(mode, decisions); }
    private DecisionRequestExecutor executor(String mode, DecisionApplicationService service) {
        var factory = new StaticListableBeanFactory(Map.of("redis", redis));
        return new DecisionRequestExecutor(commands, mapper, TIME, service, transactions, factory.getBeanProvider(StringRedisTemplate.class), mode);
    }
    private void applyKill(boolean enabled, long sequence) {
        kills.apply(KillSwitchDirectiveSigner.sign(RELEASE.getPrivate(), new KillSwitchDirective("kill-" + sequence, new TenantId(tenant), "main", sequence,
                enabled, "test", TIME.instant(), "operator", "release", "")));
    }
    private HttpRequest.Builder builder(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(20)).header("Content-Type", "application/json")
                .header("X-Dev-Tenant-Id", tenant).header("X-Dev-Actor-Id", "test").header("X-Dev-Organization-Ids", "*").header("X-Dev-Shop-Ids", "*").header("X-Dev-Permissions", "*");
    }
    private HttpRequest.Builder request(DecisionRequest input) { return builder("/api/v1/decisions/bundle:evaluate").POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(input))); }
    private HttpResponse<String> send(DecisionRequest input) throws Exception { return send(request(input)); }
    private HttpResponse<String> send(HttpRequest.Builder request) throws Exception { return http.send(request.build(), HttpResponse.BodyHandlers.ofString()); }
    private void assertCode(int status, String code, HttpResponse<String> response) {
        assertEquals(status, response.statusCode(), response.body()); assertEquals(code, mapper.readTree(response.body()).path("code").asString());
    }
    private static String encoded(byte[] value) { return Base64.getEncoder().encodeToString(value); }
}
