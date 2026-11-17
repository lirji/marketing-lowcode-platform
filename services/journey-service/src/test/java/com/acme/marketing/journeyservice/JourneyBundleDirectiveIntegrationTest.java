package com.acme.marketing.journeyservice;

import static org.junit.jupiter.api.Assertions.*;

import com.acme.marketing.contracts.artifact.ArtifactAttestation;
import com.acme.marketing.contracts.release.*;
import com.acme.marketing.platform.crypto.Digests;
import com.acme.marketing.platform.crypto.Ed25519;
import com.acme.marketing.platform.identity.TenantId;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyPair;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.ObjectMapper;

/** 隔离MySQL验证接收单调性与恢复；RECORDED从不冒充线上已应用。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@org.springframework.test.context.TestExecutionListeners(listeners = JourneyBundleDirectiveIntegrationTest.DatabaseCleanup.class,
        mergeMode = org.springframework.test.context.TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS)
class JourneyBundleDirectiveIntegrationTest {
    private static final KeyPair RELEASE = Ed25519.generateKeyPair();
    private static final KeyPair COMPILER = Ed25519.generateKeyPair();
    private static final String PATH = "/api/v1/journey-runtime/release-bundle-directives";
    private static ReleaseBundleActivationDirective restartDirective;
    private static String restartTenant;
    private static final org.testcontainers.mysql.MySQLContainer MYSQL = mysql();
    private static org.testcontainers.mysql.MySQLContainer mysql() {
        var mysql = new org.testcontainers.mysql.MySQLContainer("mysql:8.4.11")
                .withCommand("--log-bin-trust-function-creators=1", "--performance-schema=OFF", "--innodb-buffer-pool-size=64M", "--max-connections=30")
                .withDatabaseName("bundle_directive_test").withUsername("test").withPassword("test")
                .withTmpFs(Map.of("/var/lib/mysql", "rw"))
                .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withMemory(512L * 1024 * 1024).withMemorySwap(512L * 1024 * 1024))
                .withStartupTimeout(Duration.ofMinutes(5));
        mysql.start(); return mysql;
    }
    /** Spring的afterTestClass按逆序执行；先由DirtiesContext移出并关闭上下文，再释放专属数据库。 */
    public static final class DatabaseCleanup extends org.springframework.test.context.support.AbstractTestExecutionListener {
        @Override public int getOrder() { return org.springframework.core.Ordered.HIGHEST_PRECEDENCE; }
        @Override public void afterTestClass(org.springframework.test.context.TestContext context) { MYSQL.stop(); }
    }
    @DynamicPropertySource static void keys(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", MYSQL::getJdbcUrl); r.add("spring.datasource.username", MYSQL::getUsername);
        r.add("spring.datasource.password", MYSQL::getPassword); r.add("spring.flyway.user", MYSQL::getUsername); r.add("spring.flyway.password", MYSQL::getPassword);
        r.add("marketing.release.trusted-key-id", () -> "release-key"); r.add("marketing.release.public-key-base64", () -> encoded(RELEASE.getPublic().getEncoded()));
        r.add("marketing.compiler.trusted-key-id", () -> "compiler-key"); r.add("marketing.compiler.public-key-base64", () -> encoded(COMPILER.getPublic().getEncoded()));
    }
    @LocalServerPort int port;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired com.acme.marketing.journeyservice.application.JourneyRuntimeReleaseService runtime;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final String tenant = "directive-" + UUID.randomUUID();
    private final Instant now = Instant.now().minusSeconds(1);

    @Test @Order(1) @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void recordsWholeDirectiveAndLeavesLiveGenerationUnchanged() throws Exception {
        activate(installed(1));
        var directive = directive(installed(2), 1, "campaign");
        var first = send(post(PATH, directive)); assertReceipt(first, 1, true);
        assertEquals(mapper.readTree(first.body()), mapper.readTree(send(post(PATH, directive)).body()));
        assertEquals(mapper.readTree(first.body()), mapper.readTree(send(request(PATH + "/campaign/latest").GET()).body()));
        assertEquals(1, count("mk_runtime_bundle_directive")); assertEquals(1, count("mk_runtime_bundle_head"));
        assertEquals(1, active(tenant)); restartDirective = directive; restartTenant = tenant;
    }

    @Test @Order(2)
    void recreatedContextRecoversExactAcceptedDirective() throws Exception {
        assertNotNull(restartDirective);
        var result = send(request(PATH + "/campaign/latest").setHeader("X-Dev-Tenant-Id", restartTenant).GET());
        assertReceipt(result, 1, true);
        var actual = mapper.readValue(mapper.readTree(result.body()).path("directive").toString(), ReleaseBundleActivationDirective.class);
        assertEquals(restartDirective, actual); assertEquals(1, active(restartTenant)); assertTrue(managed(restartTenant));
    }

    @Test @Order(3)
    void duplicateAndConflictingConcurrentSequencesHaveOneDurableWinner() throws Exception {
        var one = directive(installed(1), 1, "campaign");
        var duplicated = concurrent(one, one);
        duplicated.forEach(result -> assertReceipt(result, 1, true));
        assertEquals(mapper.readTree(duplicated.get(0).body()), mapper.readTree(duplicated.get(1).body()));
        var two = directive(installed(2), 2, "campaign");
        var other = ReleaseBundleActivationSigner.sign(RELEASE.getPrivate(), new ReleaseBundleActivationDirective("other-id", two.bundle(), 2,
                two.action(), two.organizationId(), two.shopId(), two.authorizedAt(), two.authorizedBy(), two.signatureKeyId(), ""));
        var raced = concurrent(two, other);
        assertEquals(List.of(200, 409), raced.stream().map(HttpResponse::statusCode).sorted().toList(), raced.toString());
        assertEquals(2, count("mk_runtime_bundle_directive"));
        assertReceipt(send(post(PATH, one)), 2, false);
        assertReceipt(send(request(PATH + "/campaign/latest").GET()), 2, true);
        assertEquals(1, count("mk_journey_runtime_slot")); assertEquals(0, active(tenant)); assertTrue(managed(tenant));
    }

    @Test @Order(4)
    void newerSequenceCanArriveFirstAndOldUnknownSequenceCannotRewindIt() throws Exception {
        var member = installed(1); var three = directive(member, 3, "campaign");
        assertReceipt(send(post(PATH, three)), 3, true);
        assertCode(409, "RELEASE_BUNDLE_ACTIVATION_SEQUENCE_STALE", send(post(PATH, directive(member, 2, "campaign"))));
        var reused = ReleaseBundleActivationSigner.sign(RELEASE.getPrivate(), new ReleaseBundleActivationDirective(three.directiveId(), three.bundle(), 4,
                three.action(), three.organizationId(), three.shopId(), three.authorizedAt(), three.authorizedBy(), three.signatureKeyId(), ""));
        assertCode(409, "RELEASE_BUNDLE_ACTIVATION_SEQUENCE_CONFLICT", send(post(PATH, reused)));
        assertEquals(1, count("mk_runtime_bundle_directive"));
    }

    @Test @Order(5)
    void unrelatedCampaignsDoNotShareAnEmptyRangeLock() throws Exception {
        var member = installed(1);
        var results = concurrent(directive(member, 1, "first"), directive(member, 1, "FIRST"));
        results.forEach(result -> assertReceipt(result, 1, true));
        assertEquals(2, count("mk_runtime_bundle_head")); assertEquals(2, count("mk_runtime_bundle_directive"));
        assertCode(404, "RELEASE_BUNDLE_ACTIVATION_NOT_RECORDED", send(request(PATH + "/First/latest").GET()));
    }

    @Test @Order(6)
    void rejectedProofOrUninstalledOrCorruptedMemberLeavesNoPartialHead() throws Exception {
        var missing = directive(member(1, payload(1), "journey", "main"), 1, "campaign");
        assertEquals(404, send(post(PATH, missing)).statusCode());
        var directive = directive(installed(1), 1, "campaign");
        assertCode(409, "RELEASE_BUNDLE_ACTIVATION_INVALID", send(post(PATH, directive).setHeader("X-Dev-Tenant-Id", "other")));
        var bad = ReleaseBundleActivationSigner.sign(Ed25519.generateKeyPair().getPrivate(), directive);
        assertCode(409, "RELEASE_BUNDLE_ACTIVATION_INVALID", send(post(PATH, bad)));
        var wrongSlot = directive(member(2, payload(2), "journey", "another"), 2, "campaign");
        assertCode(409, "RELEASE_BUNDLE_MEMBER_MISMATCH", send(post(PATH, wrongSlot)));
        jdbc.update("update mk_journey_runtime_generation set artifact_payload=? where tenant_id=?", new byte[]{0}, tenant);
        assertEquals(409, send(post(PATH, directive)).statusCode());
        assertEquals(0, count("mk_runtime_bundle_head")); assertEquals(0, count("mk_runtime_bundle_directive"));
    }

    @Test @Order(7)
    void permissionAndStrictBoundedParsingPrecedeAnyPersistence() throws Exception {
        String huge = " ".repeat(com.acme.marketing.journeyservice.application.JourneyBundleDirectiveService.MAX_REQUEST_BYTES + 1);
        assertEquals(403, send(raw(PATH, huge).setHeader("X-Dev-Permissions", "runtime:warm")).statusCode());
        for (String body : List.of(huge, "null", "{} {}")) assertCode(400, "RELEASE_BUNDLE_ACTIVATION_REQUEST_INVALID", send(raw(PATH, body)));
        var tree = (tools.jackson.databind.node.ObjectNode) mapper.readTree(mapper.writeValueAsString(directive(installed(1), 1, "campaign")));
        tree.put("untrusted", true);
        assertCode(400, "RELEASE_BUNDLE_ACTIVATION_REQUEST_INVALID", send(raw(PATH, tree.toString())));
        assertCode(404, "RELEASE_BUNDLE_ACTIVATION_NOT_RECORDED", send(request(PATH + "/campaign/latest").GET()));
        assertEquals(403, send(request(PATH + "/campaign/latest").setHeader("X-Dev-Permissions", "runtime:warm").GET()).statusCode());
        assertEquals(0, count("mk_runtime_bundle_head"));
    }

    @Test @Order(8)
    void headWriteFailureRollsBackHistoryAndAllowsTheSameDirectiveRetry() throws Exception {
        var directive = directive(installed(1), 1, "campaign");
        String trigger = "bundle_head_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.execute("CREATE TRIGGER " + trigger + " BEFORE UPDATE ON mk_runtime_bundle_head FOR EACH ROW BEGIN IF NEW.tenant_id = '" + tenant + "' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='test head failure'; END IF; END");
        try {
            assertEquals(500, send(post(PATH, directive)).statusCode());
            assertEquals(0, count("mk_runtime_bundle_head")); assertEquals(0, count("mk_runtime_bundle_directive")); assertEquals(0, count("mk_journey_runtime_slot"));
        } finally { jdbc.execute("DROP TRIGGER " + trigger); }
        assertReceipt(send(post(PATH, directive)), 1, true);
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () -> jdbc.update("update mk_runtime_bundle_head set last_sequence=999 where tenant_id=?", tenant));
        jdbc.update("update mk_runtime_bundle_directive set directive_json='{}' where tenant_id=?", tenant);
        assertCode(409, "RELEASE_BUNDLE_ACTIVATION_STORED_INVALID", send(request(PATH + "/campaign/latest").GET()));
        jdbc.update("update mk_runtime_bundle_directive set directive_json=?, recorded_at='invalid-time' where tenant_id=?", mapper.writeValueAsString(directive), tenant);
        assertCode(409, "RELEASE_BUNDLE_ACTIVATION_STORED_INVALID", send(request(PATH + "/campaign/latest").GET()));
    }

    @Test @Order(10)
    void managedSlotRejectsOldHttpReceiptAndNewStandaloneCoordinatorWrites() throws Exception {
        var old = installed(1); activate(old);
        var newer = installed(2);
        assertReceipt(send(post(PATH, directive(newer, 1, "campaign"))), 1, true);
        assertTrue(managed(tenant)); assertEquals(1, active(tenant));
        var oldSingle = single(old, 1);
        assertCode(409, "RELEASE_BUNDLE_SLOT_MANAGED", send(request("/api/v1/journey-runtime/activation")
                .PUT(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(oldSingle)))));
        // 接管后仍能安装后续清单，但任何同slot清单都不能绕过组合独立激活。
        var replacement = installed(3); var newSingle = single(replacement, 2);
        assertCode(409, "RELEASE_BUNDLE_SLOT_MANAGED", send(request("/api/v1/journey-runtime/activation")
                .PUT(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(newSingle)))));
        assertEquals("RELEASE_BUNDLE_SLOT_MANAGED", assertThrows(com.acme.marketing.platform.error.ConflictException.class,
                () -> runtime.activateFromCoordinator(newSingle)).code());

        assertEquals(1, active(tenant));
    }

    @Test @Order(11)
    void standaloneWriterWithOldRepeatableReadSnapshotSeesCommittedSlotFence() throws Exception {
        var old = installed(1); activate(old); var next = installed(2); var newSingle = single(next, 2);
        var snapshotRead = new java.util.concurrent.CountDownLatch(1);
        var continueWrite = new java.util.concurrent.CountDownLatch(1);
        var tx = new org.springframework.transaction.support.TransactionTemplate(transactions);
        tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ); tx.setTimeout(10);
        try (var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var result = executor.submit(() -> tx.execute(status -> {
                assertFalse(managed(tenant)); snapshotRead.countDown();
                try { assertTrue(continueWrite.await(5, java.util.concurrent.TimeUnit.SECONDS)); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
                String code = assertThrows(com.acme.marketing.platform.error.ConflictException.class,
                        () -> runtime.activateFromCoordinator(newSingle)).code();
                status.setRollbackOnly(); return code;
            }));
            try {
                assertTrue(snapshotRead.await(5, java.util.concurrent.TimeUnit.SECONDS));
                assertReceipt(send(post(PATH, directive(next, 1, "campaign"))), 1, true);
            } finally { continueWrite.countDown(); }
            assertEquals("RELEASE_BUNDLE_SLOT_MANAGED", result.get(10, java.util.concurrent.TimeUnit.SECONDS));
        }
        assertTrue(managed(tenant)); assertEquals(1, active(tenant));
    }

    @Test @Order(12)
    void rejectedReceiptDoesNotFenceAnExistingStandaloneSlot() throws Exception {
        var old = installed(1); activate(old); var next = installed(2);
        var directive = directive(next, 1, "campaign");
        String trigger = "bundle_slot_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.execute("CREATE TRIGGER " + trigger + " BEFORE UPDATE ON mk_runtime_bundle_head FOR EACH ROW BEGIN IF NEW.tenant_id = '" + tenant + "' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='test receipt failure'; END IF; END");
        try {
            assertEquals(500, send(post(PATH, directive)).statusCode());
            assertFalse(managed(tenant)); assertEquals(1, active(tenant));
            assertEquals(0, count("mk_runtime_bundle_directive"));
        } finally { jdbc.execute("DROP TRIGGER " + trigger); }
        var newSingle = single(next, 2);
        assertEquals(200, send(request("/api/v1/journey-runtime/activation")
                .PUT(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(newSingle)))).statusCode());
        assertEquals(2, active(tenant));
        assertReceipt(send(post(PATH, directive)), 1, true); assertTrue(managed(tenant));
    }

    private boolean managed(String tenantId) {
        return jdbc.queryForObject("select bundle_managed from mk_journey_runtime_slot where tenant_id=?", Boolean.class, tenantId);
    }
    private ActivationDirective single(ReleaseManifest member, long sequence) {
        return ActivationDirectiveSigner.sign(RELEASE.getPrivate(), new ActivationDirective("activation-" + sequence,
                member.tenantId(), member.manifestId(), "local", "cell-a", "journey", "main", sequence,
                member.generation(), member.generation(), 0, member.signature(), now, member.expiresAt(), "operator", "release-key", ""));
    }

    private ReleaseBundleActivationDirective directive(ReleaseManifest member, long sequence, String campaign) {
        var source = bundle(member).bundle();
        var signed = ReleaseBundleManifestSigner.sign(RELEASE.getPrivate(), new ReleaseBundleManifest(source.bundleId(), source.tenantId(), campaign,
                source.version(), source.environment(), source.cell(), ReleaseBundleManifest.contentHash(tenant, campaign, source.members()), source.members(),
                source.createdAt(), source.expiresAt(), source.signatureKeyId(), ""));
        return ReleaseBundleActivationSigner.sign(RELEASE.getPrivate(), new ReleaseBundleActivationDirective("directive-" + sequence, signed, sequence,
                "ACTIVATE", "org", "", now, "operator", "release-key", ""));
    }
    private List<HttpResponse<String>> concurrent(ReleaseBundleActivationDirective a, ReleaseBundleActivationDirective b) throws Exception {
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var start = new java.util.concurrent.CountDownLatch(1);
            var left = pool.submit(() -> { start.await(); return send(post(PATH, a)); });
            var right = pool.submit(() -> { start.await(); return send(post(PATH, b)); });
            start.countDown(); return List.of(left.get(), right.get());
        }
    }
    private void assertReceipt(HttpResponse<String> result, long latest, boolean current) {
        assertEquals(200, result.statusCode(), result.body()); var body = mapper.readTree(result.body());
        assertEquals("RECORDED", body.path("state").asString()); assertEquals(latest, body.path("latestSequence").asLong());
        assertEquals(current, body.path("current").asBoolean());
    }
    private int count(String table) { return jdbc.queryForObject("select count(*) from " + table + " where tenant_id=?", Integer.class, tenant); }
    @Test @Order(9)
    void installedDefinitionMustStillMatchBeforeAnyDirectiveCanBeRecorded() throws Exception {
        var member = installed(1);
        var changed = new com.acme.marketing.journey.JourneyPlan("definition", 1, "changed",
                Map.of("changed", new com.acme.marketing.journey.JourneyNode("changed", com.acme.marketing.journey.JourneyNode.Type.END, Map.of(), Map.of())), 5, 1, Duration.ofDays(1));
        jdbc.update("update mk_journey_definition set plan_json=? where tenant_id=? and journey_id=? and version_no=?", mapper.writeValueAsString(changed), tenant, "definition", 1);
        assertCode(409, "RELEASE_BUNDLE_MEMBER_MISMATCH", send(post(PATH, directive(member, 1, "campaign"))));
        assertEquals(0, count("mk_runtime_bundle_head")); assertEquals(0, count("mk_runtime_bundle_directive"));
    }

    private ReleaseManifest installed(long generation) throws Exception {
        byte[] payload = payload(generation); var member = member(generation, payload, "journey", "main");
        var response = send(request("/api/v1/journey-runtime/manifest").PUT(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(
                Map.of("releaseKeyId", "release-key", "manifest", member, "artifactBase64", encoded(payload))))));
        assertEquals(200, response.statusCode(), response.body()); return member;
    }
    private void activate(ReleaseManifest member) throws Exception {
        var unsigned = new ActivationDirective("activation-1", member.tenantId(), member.manifestId(), "local", "cell-a", "journey",
                "main", 1, member.generation(), member.generation(), 0, member.signature(), now, member.expiresAt(), "operator", "release-key", "");
        var signed = ActivationDirectiveSigner.sign(RELEASE.getPrivate(), unsigned);
        var response = send(request("/api/v1/journey-runtime/activation").PUT(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(signed))));
        assertEquals(200, response.statusCode(), response.body());
    }
    private long active(String tenantId) {
        return jdbc.queryForObject("select desired_generation from mk_journey_runtime_slot where tenant_id=?", Long.class, tenantId);
    }
    private ReleaseBundlePrepareRequest bundle(ReleaseManifest member) {
        var other = member(member.generation(), new byte[] {4}, "decision", "other");
        var members = List.of(member, other);
        var unsigned = new ReleaseBundleManifest("bundle-" + member.generation(), new TenantId(tenant), "campaign", member.generation(),
                "local", "cell-a", ReleaseBundleManifest.contentHash(tenant, "campaign", members), members, now,
                now.plusSeconds(600), "release-key", "");
        return new ReleaseBundlePrepareRequest(ReleaseBundleManifestSigner.sign(RELEASE.getPrivate(), unsigned), member.manifestId());
    }
    private ReleaseManifest member(long generation, byte[] payload, String runtime, String namespace) {
        String id = runtime + "-" + generation;
        String checksum = "sha256:" + Digests.sha256Hex(payload), source = "sha256:" + "b".repeat(64);
        String signature = ArtifactAttestation.sign(COMPILER.getPrivate(), tenant, id, "definition", generation,
                "JOURNEY_PLAN", "marketing-journey-plan/1", checksum, source);
        var reference = new ArtifactReference(id, "JOURNEY_PLAN", "s3://artifacts/" + id, checksum, source, "compiler-key",
                signature, "marketing-journey-plan/1", "definition", generation);
        return new ReleaseManifestSigner().sign(new ReleaseManifest(id, new TenantId(tenant), "local", "cell-a", runtime, namespace,
                generation, 0, List.of(), List.of(), List.of(reference), Map.of(), 0, now, now.plusSeconds(600), "author", List.of("case"), now, ""), RELEASE.getPrivate());
    }
    private HttpRequest.Builder post(String path, Object body) { return raw(path, mapper.writeValueAsString(body)); }
    private HttpRequest.Builder raw(String path, String body) { return request(path).POST(HttpRequest.BodyPublishers.ofString(body)); }
    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json").header("X-Dev-Tenant-Id", tenant).header("X-Dev-Actor-Id", "runtime-test")
                .header("X-Dev-Organization-Ids", "*").header("X-Dev-Shop-Ids", "*").header("X-Dev-Permissions", "*");
    }
    private HttpResponse<String> send(HttpRequest.Builder request) throws Exception { return http.send(request.build(), HttpResponse.BodyHandlers.ofString()); }
    private void assertCode(int status, String code, HttpResponse<String> response) {
        assertEquals(status, response.statusCode(), response.body()); assertEquals(code, mapper.readTree(response.body()).path("code").asString());
    }
    private static String encoded(byte[] value) { return Base64.getEncoder().encodeToString(value); }
    private byte[] payload(long generation) {
        return mapper.writeValueAsBytes(new com.acme.marketing.journey.JourneyPlan("definition", generation, "end",
                Map.of("end", new com.acme.marketing.journey.JourneyNode("end", com.acme.marketing.journey.JourneyNode.Type.END, Map.of(), Map.of())),
                5, 1, Duration.ofDays(1)));
    }
}
