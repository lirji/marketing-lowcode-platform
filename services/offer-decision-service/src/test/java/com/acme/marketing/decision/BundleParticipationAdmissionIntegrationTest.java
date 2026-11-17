package com.acme.marketing.decision;

import static org.junit.jupiter.api.Assertions.*;

import com.acme.marketing.contracts.artifact.ArtifactAttestation;
import com.acme.marketing.contracts.release.*;
import com.acme.marketing.decision.application.*;
import com.acme.marketing.platform.crypto.Ed25519;
import com.acme.marketing.platform.error.ConflictException;
import com.acme.marketing.platform.identity.TenantId;
import com.acme.marketing.platform.identity.TenantScope;
import static com.acme.marketing.platform.time.SqlTime.format;
import java.net.URI;
import java.net.http.*;
import java.security.KeyPair;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
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
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** 只在独占MySQL造APPLIED夹具验证准入不变量；不冒充真实发布或生产切换验收。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@org.springframework.test.context.TestExecutionListeners(listeners = BundleParticipationAdmissionIntegrationTest.DatabaseCleanup.class,
        mergeMode = org.springframework.test.context.TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS)
class BundleParticipationAdmissionIntegrationTest {
    private static final KeyPair RELEASE = Ed25519.generateKeyPair(), PARTICIPATION = Ed25519.generateKeyPair();
    private static final Instant NOW = Instant.now();
    private static final MutableClock TIME = new MutableClock();
    private static final org.testcontainers.mysql.MySQLContainer MYSQL = mysql();
    private static org.testcontainers.mysql.MySQLContainer mysql() {
        var value = new org.testcontainers.mysql.MySQLContainer("mysql:8.4.11")
                .withCommand("--performance-schema=OFF", "--innodb-buffer-pool-size=64M", "--max-connections=30")
                .withTmpFs(Map.of("/var/lib/mysql", "rw"))
                .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withMemory(512L * 1024 * 1024).withMemorySwap(512L * 1024 * 1024))
                .withStartupTimeout(Duration.ofMinutes(5));
        value.start(); return value;
    }
    /** Spring先关闭调度任务与连接池，最后释放本测试数据库。 */
    public static final class DatabaseCleanup extends org.springframework.test.context.support.AbstractTestExecutionListener {
        @Override public int getOrder() { return org.springframework.core.Ordered.HIGHEST_PRECEDENCE; }
        @Override public void afterTestClass(org.springframework.test.context.TestContext context) { MYSQL.stop(); }
    }
    @TestConfiguration static class TestConfig {
        @Bean @Primary Clock testClock() { return TIME; }
        @Bean @Primary BarrierRepository barrierRepository(@Qualifier("mybatisBundleAdmissionRepository") BundleAdmissionRepository delegate) {
            return new BarrierRepository(delegate);
        }
    }
    private static final class MutableClock extends Clock {
        private volatile Instant now = NOW;
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
    /** 只加同步栅栏/提交前故障，全部查询与写入仍委托真实Mapper和同一数据库事务。 */
    static final class BarrierRepository implements BundleAdmissionRepository {
        private final BundleAdmissionRepository delegate;
        private final AtomicReference<Gate> gate = new AtomicReference<>();
        BarrierRepository(BundleAdmissionRepository delegate) { this.delegate = delegate; }
        @Override public Optional<AppliedAuthorization> lockApplied(Key key) { return delegate.lockApplied(key); }
        @Override public Optional<AppliedAuthorization> applied(Key key, long sequence) { return delegate.applied(key, sequence); }
        @Override public Optional<StoredParticipation> participation(Key key, String id) { return delegate.participation(key, id); }
        @Override public boolean insert(Key key, String id, StoredParticipation value) {
            var selected = gate.get();
            if (selected != null && selected.id.equals(id)) selected.beforeInsert();
            boolean result = delegate.insert(key, id, value);
            if (selected != null && selected.id.equals(id) && selected.failAfterInsert) throw new IllegalStateException("injected post-insert failure");
            return result;
        }
    }
    private static final class Gate {
        private final String id;
        private final CyclicBarrier race;
        private final CountDownLatch reached = new CountDownLatch(1), release;
        private final boolean failAfterInsert;
        Gate(String id, int racers, boolean hold, boolean fail) {
            this.id = id; race = racers > 0 ? new CyclicBarrier(racers) : null;
            release = new CountDownLatch(hold ? 1 : 0); failAfterInsert = fail;
        }
        void beforeInsert() {
            reached.countDown();
            try {
                if (race != null) race.await(5, TimeUnit.SECONDS);
                if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test gate timed out");
            } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
            catch (BrokenBarrierException | TimeoutException failed) { throw new IllegalStateException(failed); }
        }
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", MYSQL::getJdbcUrl); r.add("spring.datasource.username", MYSQL::getUsername);
        r.add("spring.datasource.password", MYSQL::getPassword); r.add("spring.flyway.user", MYSQL::getUsername); r.add("spring.flyway.password", MYSQL::getPassword);
        r.add("marketing.release.trusted-key-id", () -> "release"); r.add("marketing.release.public-key-base64", () -> encoded(RELEASE.getPublic().getEncoded()));
        r.add("marketing.participation.trusted-key-id", () -> "participation"); r.add("marketing.participation.public-key-base64", () -> encoded(PARTICIPATION.getPublic().getEncoded()));
        r.add("marketing.participation.signing-key-id", () -> "participation"); r.add("marketing.participation.private-key-base64", () -> encoded(PARTICIPATION.getPrivate().getEncoded()));
        r.add("marketing.participation.signing-public-key-base64", () -> encoded(PARTICIPATION.getPublic().getEncoded()));
    }
    @LocalServerPort int port;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired BarrierRepository repository;
    @Autowired PinnedReleaseBundleActivationVerifier activationVerifier;
    @Autowired PlatformTransactionManager transactions;
    @BeforeEach void reset() { TIME.now = NOW; repository.gate.set(null); }
    @AfterEach void releaseGate() { var gate = repository.gate.getAndSet(null); if (gate != null) gate.release.countDown(); }
    private final String tenant = "admission-" + UUID.randomUUID();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    @Test void emptyAndRecordedOnlyNeverBecomeAppliedAdmissions() throws Exception {
        assertCode(409, "RELEASE_BUNDLE_ADMISSION_NOT_APPLIED", send(input("one", "subject")));
        var source = directive(1);
        jdbc.update("INSERT INTO mk_runtime_bundle_directive VALUES (?,'local','cell-a','main','campaign',1,?,?,?,?)",
                tenant, source.directiveId(), mapper.writeValueAsString(source), "test", format(NOW));
        jdbc.update("INSERT INTO mk_runtime_bundle_head VALUES (?,'local','cell-a','main','campaign',1,?)", tenant, format(NOW));
        assertCode(409, "RELEASE_BUNDLE_ADMISSION_NOT_APPLIED", send(input("one", "subject")));
        assertEquals(0, count());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM mk_bundle_applied_authorization WHERE tenant_id=?", Integer.class, tenant));
    }

    @Test void concurrentSameSubjectHasOnePersistedProofAndDifferentSubjectHasOneWinner() throws Exception {
        applied(1, true);
        repository.gate.set(new Gate("same", 2, false, false));
        var same = race(input("same", "subject"), input("same", "subject"));
        same.forEach(result -> assertEquals(200, result.statusCode(), result.body()));
        assertEquals(mapper.readTree(same.get(0).body()), mapper.readTree(same.get(1).body())); assertEquals(1, count());
        repository.gate.set(new Gate("different", 2, false, false));
        var different = race(input("different", "subject-a"), input("different", "subject-b"));
        assertEquals(List.of(200, 409), different.stream().map(HttpResponse::statusCode).sorted().toList());
        assertCode(409, "RELEASE_BUNDLE_PARTICIPATION_SUBJECT_CONFLICT", different.stream().filter(r -> r.statusCode() == 409).findFirst().orElseThrow());
        assertEquals(2, count());
    }

    @Test void differentParticipantsSharePointerWhileApplicationSwitchWaitsForCurrentAdmission() throws Exception {
        applied(1, true); applied(2, false);
        var gate = new Gate("held", 0, true, false); repository.gate.set(gate);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var held = pool.submit(() -> send(input("held", "subject")));
            assertTrue(gate.reached.await(5, TimeUnit.SECONDS));
            assertEquals(1, proof(send(input("parallel", "subject"))).activationSequence());
            var writerStarted = new CountDownLatch(1);
            var switching = pool.submit(() -> { writerStarted.countDown(); jdbc.update("UPDATE mk_bundle_admission_pointer SET applied_sequence=2 WHERE tenant_id=?", tenant); });
            assertTrue(writerStarted.await(5, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> switching.get(200, TimeUnit.MILLISECONDS));
            gate.release.countDown();
            assertEquals(1, proof(held.get(5, TimeUnit.SECONDS)).activationSequence()); switching.get(5, TimeUnit.SECONDS);
        } finally { gate.release.countDown(); }
        assertEquals(2, proof(send(input("new", "subject"))).activationSequence());
        assertEquals(1, proof(send(input("held", "subject"))).activationSequence());
    }

    @Test void failureAfterInsertRollsBackAndRetryCreatesOneCompleteFact() throws Exception {
        applied(1, true); repository.gate.set(new Gate("failing", 0, false, true));
        assertEquals(500, send(input("failing", "subject")).statusCode()); assertEquals(0, count());
        repository.gate.set(null);
        var first = proof(send(input("failing", "subject"))); assertEquals(first, proof(send(input("failing", "subject")))); assertEquals(1, count());
    }

    @Test void oldProofSurvivesExpiryAndMissingPrivateKeyButCurrentTrustAndScopeAreRequired() throws Exception {
        applied(1, true); var request = input("restore", "subject"); var first = proof(send(request));
        TIME.now = NOW.plusSeconds(601);
        assertEquals(first, proof(send(request)));
        assertCode(409, "RELEASE_BUNDLE_ACTIVATION_INVALID", send(input("new-after-expiry", "subject")));
        var unavailable = new BundleParticipationSigningService("", "", "");
        assertThrows(ConflictException.class, unavailable::keyId);
        var restoredProcess = service(unavailable, true);
        assertEquals(first, inTransaction(restoredProcess, request));
        assertEquals("RELEASE_BUNDLE_PARTICIPATION_INVALID", assertThrows(ConflictException.class,
                () -> inTransaction(service(unavailable, false), request)).code());
        assertCode(403, "PERMISSION_DENIED", send(builder(request).setHeader("X-Dev-Permissions", "decision:evaluate")));
        assertEquals(400, send(builder(request).setHeader("X-Dev-Organization-Ids", "other")).statusCode());
        assertEquals(400, send(builder(input("restore", "different-subject")).setHeader("X-Dev-Organization-Ids", "other")).statusCode());
        assertEquals(400, send(builder(request).setHeader("X-Dev-Shop-Ids", "other")).statusCode());
        TIME.now = NOW;
        assertEquals("RELEASE_BUNDLE_PARTICIPATION_SIGNING_UNAVAILABLE", assertThrows(ConflictException.class,
                () -> inTransaction(restoredProcess, input("missing-key", "subject"))).code());
        assertThrows(IllegalStateException.class, () -> new BundleParticipationSigningService("partial", "", ""));
        var otherKey = Ed25519.generateKeyPair();
        var untrustedSigner = new BundleParticipationSigningService("participation", encoded(otherKey.getPrivate().getEncoded()), encoded(otherKey.getPublic().getEncoded()));
        assertEquals("RELEASE_BUNDLE_PARTICIPATION_SIGNING_UNAVAILABLE", assertThrows(ConflictException.class,
                () -> inTransaction(service(untrustedSigner, true), input("untrusted-signer", "subject"))).code());
        assertEquals(1, count());
    }

    @Test void strictInputTenantIsolationAndBinaryBusinessKeysAreEnforced() throws Exception {
        applied(1, true);
        for (String raw : List.of("null", "{}", "{\"campaignId\":\"campaign\",\"participationId\":\"a\",\"subjectToken\":\"subject\",\"activationSequence\":1}", " ".repeat(8193)))
            assertCode(400, "RELEASE_BUNDLE_ADMISSION_REQUEST_INVALID", send(raw(raw)));
        var ids = List.of("Case", "case", "case ", "中文参与"); Set<String> bindings = new HashSet<>();
        for (String id : ids) bindings.add(proof(send(input(id, "subject"))).bindingId());
        assertEquals(ids.size(), bindings.size()); assertEquals(ids.size(), count());
        assertCode(409, "RELEASE_BUNDLE_ADMISSION_NOT_APPLIED", send(builder(input("Case", "subject")).setHeader("X-Dev-Tenant-Id", "other")));
        assertCode(403, "PERMISSION_DENIED", send(raw("invalid").setHeader("X-Dev-Permissions", "decision:evaluate")));
    }

    @Test void storedProofAndAppliedTimeCorruptionCannotBeUsedToIssueOrReplay() throws Exception {
        applied(1, true); var request = input("existing", "subject"); proof(send(request));
        jdbc.update("UPDATE mk_bundle_participation SET participation_json='null' WHERE tenant_id=?", tenant);
        assertCode(409, "RELEASE_BUNDLE_PARTICIPATION_STORED_INVALID", send(request));
        jdbc.update("UPDATE mk_bundle_applied_authorization SET applied_at=? WHERE tenant_id=?", format(NOW.plusSeconds(1)), tenant);
        assertCode(409, "RELEASE_BUNDLE_ACTIVATION_STORED_INVALID", send(input("future-applied", "subject")));
        assertEquals(1, count());
    }

    private BundleParticipationAdmissionService service(BundleParticipationSigningService signing, boolean trust) {
        return new BundleParticipationAdmissionService(repository, signing, activationVerifier, mapper, TIME,
                trust ? "participation" : "", trust ? encoded(PARTICIPATION.getPublic().getEncoded()) : "", "", "local", "cell-a");
    }
    private ReleaseBundleParticipation inTransaction(BundleParticipationAdmissionService service, ReleaseBundleAdmissionRequest request) {
        var tx = new TransactionTemplate(transactions); tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        return tx.execute(ignored -> service.admit(new TenantScope(new TenantId(tenant), Set.of("org"), Set.of("shop"), "operator", Set.of("bundle:admit")), request));
    }
    private void applied(long sequence, boolean pointer) {
        var source = directive(sequence);
        jdbc.update("INSERT INTO mk_bundle_applied_authorization VALUES (?,'local','cell-a','campaign',?,?,?,?)",
                tenant, sequence, source.directiveId(), mapper.writeValueAsString(source), format(NOW.minusMillis(500)));
        if (pointer) jdbc.update("INSERT INTO mk_bundle_admission_pointer VALUES (?,'local','cell-a','campaign',?,?)", tenant, sequence, format(NOW.minusMillis(500)));
    }
    private ReleaseBundleActivationDirective directive(long sequence) {
        var members = List.of(member(sequence, "decision", "main"), member(sequence, "journey", "main"));
        var bundle = ReleaseBundleManifestSigner.sign(RELEASE.getPrivate(), new ReleaseBundleManifest("bundle-" + sequence, new TenantId(tenant), "campaign", sequence,
                "local", "cell-a", ReleaseBundleManifest.contentHash(tenant, "campaign", members), members, NOW.minusSeconds(2), NOW.plusSeconds(600), "release", ""));
        return ReleaseBundleActivationSigner.sign(RELEASE.getPrivate(), new ReleaseBundleActivationDirective("directive-" + sequence, bundle, sequence, "ACTIVATE", "org", "shop",
                NOW.minusSeconds(1), "operator", "release", ""));
    }
    private ReleaseManifest member(long generation, String runtime, String namespace) {
        String id = runtime + '-' + generation, checksum = "sha256:" + "a".repeat(64), source = "sha256:" + "b".repeat(64);
        var artifact = new ArtifactReference(id, "OFFER_POLICY", "s3://artifacts/" + id, checksum, source, "compiler",
                ArtifactAttestation.sign(RELEASE.getPrivate(), tenant, id, "definition", generation, "OFFER_POLICY", "marketing-offer-policy/1", checksum, source), "marketing-offer-policy/1", "definition", generation);
        return new ReleaseManifestSigner().sign(new ReleaseManifest(id, new TenantId(tenant), "local", "cell-a", runtime, namespace, generation, 0,
                List.of(), List.of(), List.of(artifact), Map.of(), 0, NOW.minusSeconds(3), NOW.plusSeconds(600), "author", List.of("case"), NOW.minusSeconds(3), ""), RELEASE.getPrivate());
    }
    private List<HttpResponse<String>> race(ReleaseBundleAdmissionRequest a, ReleaseBundleAdmissionRequest b) throws Exception {
        try (var pool = Executors.newFixedThreadPool(2)) { var one = pool.submit(() -> send(a)); var two = pool.submit(() -> send(b)); return List.of(one.get(10, TimeUnit.SECONDS), two.get(10, TimeUnit.SECONDS)); }
    }
    private ReleaseBundleAdmissionRequest input(String participation, String subject) { return new ReleaseBundleAdmissionRequest("campaign", participation, subject); }
    private int count() { return jdbc.queryForObject("SELECT count(*) FROM mk_bundle_participation WHERE tenant_id=?", Integer.class, tenant); }
    private HttpRequest.Builder raw(String content) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/decisions/bundle-participations")).timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json").header("X-Dev-Tenant-Id", tenant).header("X-Dev-Actor-Id", "test")
                .header("X-Dev-Organization-Ids", "org").header("X-Dev-Shop-Ids", "shop").header("X-Dev-Permissions", "bundle:admit")
                .POST(HttpRequest.BodyPublishers.ofString(content));
    }
    private HttpRequest.Builder builder(ReleaseBundleAdmissionRequest request) { return raw(mapper.writeValueAsString(request)); }
    private HttpResponse<String> send(ReleaseBundleAdmissionRequest request) throws Exception { return send(builder(request)); }
    private HttpResponse<String> send(HttpRequest.Builder request) throws Exception { return http.send(request.build(), HttpResponse.BodyHandlers.ofString()); }
    private ReleaseBundleParticipation proof(HttpResponse<String> result) { assertEquals(200, result.statusCode(), result.body()); return mapper.readValue(result.body(), ReleaseBundleParticipation.class); }
    private void assertCode(int status, String code, HttpResponse<String> result) { assertEquals(status, result.statusCode(), result.body()); assertEquals(code, mapper.readTree(result.body()).path("code").asString()); }
    private static String encoded(byte[] value) { return Base64.getEncoder().encodeToString(value); }
}
