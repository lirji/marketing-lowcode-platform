package com.acme.marketing.control;

import static org.junit.jupiter.api.Assertions.*;

import com.acme.marketing.contracts.release.*;
import com.acme.marketing.control.application.ControlRepository;
import com.acme.marketing.control.application.BundleReleaseOperations;
import com.acme.marketing.control.application.BundleReleaseRuntime;
import com.acme.marketing.control.application.ReleaseBundleError;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.acme.marketing.control.application.ReleaseBundleReadinessService;
import com.acme.marketing.control.application.ReleaseBundleService;
import com.acme.marketing.platform.crypto.Ed25519;
import com.acme.marketing.platform.crypto.SigningKeyRing;
import com.acme.marketing.platform.identity.TenantId;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyPair;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.ObjectMapper;

/** 组合证据是真实HTTP/数据库事务测试；运行签名使用隔离测试密钥，不冒充外部联调。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"marketing.release-policy.minimum-ready-replicas=2", "marketing.release-policy.minimum-ready-capacity=1500",
                "marketing.release-policy.max-ready-capacity-per-runtime=1000", "marketing.release-policy.allowed-runtime-build-digest=test-build"})
@Import(ReleaseBundleReadinessIntegrationTest.TimeConfiguration.class)
class ReleaseBundleReadinessIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-11-17T10:00:00Z");
    private static final KeyPair ACK = Ed25519.generateKeyPair();
    private static final MySQLContainer MYSQL = mysql();
    private static MySQLContainer mysql() {
        var mysql = new MySQLContainer("mysql:8.4.11")
                .withCommand("--log-bin-trust-function-creators=1", "--performance-schema=OFF", "--innodb-buffer-pool-size=64M", "--max-connections=30")
                .withDatabaseName("bundle_ready_test").withUsername("test").withPassword("test")
                .withTmpFs(Map.of("/var/lib/mysql", "rw"))
                .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withMemory(512L * 1024 * 1024).withMemorySwap(512L * 1024 * 1024))
                .withStartupTimeout(Duration.ofMinutes(5));
        mysql.start(); return mysql;
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", MYSQL::getJdbcUrl); r.add("spring.datasource.username", MYSQL::getUsername);
        r.add("spring.datasource.password", MYSQL::getPassword); r.add("spring.flyway.user", MYSQL::getUsername);
        r.add("spring.flyway.password", MYSQL::getPassword);
        r.add("marketing.runtime-ack.trusted-key-id", () -> "ack-key");
        r.add("marketing.runtime-ack.public-key-base64", () -> Base64.getEncoder().encodeToString(ACK.getPublic().getEncoded()));
    }
    @AfterAll static void stop() { MYSQL.stop(); }
    @TestConfiguration static class TimeConfiguration {
        @Bean @Primary MutableClock bundleTestClock() { return new MutableClock(); }
    }
    static final class MutableClock extends Clock {
        volatile Instant now = NOW;
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired ControlRepository repository;
    @Autowired org.mybatis.spring.SqlSessionTemplate archiveSession;
    @Autowired org.springframework.transaction.PlatformTransactionManager archiveTransactions;
    @Autowired ObjectMapper mapper;
    @Autowired SigningKeyRing releaseKeys;
    @Autowired MutableClock clock;
    @MockitoBean BundleReleaseRuntime bundleRuntime;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final String tenant = "ready-" + UUID.randomUUID();
    @BeforeEach void resetClock() { clock.now = NOW; }

    @Test void eachMemberNeedsItsOwnReplicaAndCapacityQuorumWithoutActivating() throws Exception {
        var bundle = bundle(); var a = bundle.members().getFirst(); var b = bundle.members().getLast();
        assertFalse(view(get(bundle)).runtimeReady());
        var first = ack(bundle, a, "a-1", NOW, 1000);
        assertFalse(view(post(bundle, first)).runtimeReady());
        var halfway = view(post(bundle, ack(bundle, a, "a-2", NOW, 1000)));
        assertTrue(halfway.members().stream().filter(m -> m.manifestId().equals(a.manifestId())).findFirst().orElseThrow().runtimeReady());
        assertFalse(halfway.runtimeReady());
        assertFalse(view(post(bundle, ack(bundle, b, "b-1", NOW, 1000))).runtimeReady());
        assertTrue(view(post(bundle, ack(bundle, b, "b-2", NOW, 500))).runtimeReady());
        assertTrue(view(post(bundle, first)).runtimeReady());
        assertEquals(4, count("mk_release_bundle_ack")); assertEquals(4, auditCount());
        assertEquals(0, count("mk_activation_directive")); assertEquals(0, count("mk_outbox"));
        assertEquals("PREPARING", mapper.readTree(send(request(base(bundle)).GET()).body()).path("state").asString());
    }

    @Test void concurrentDuplicateAndIndependentIdentitiesDoNotOvercount() throws Exception {
        var bundle = bundle(); var member = bundle.members().getFirst(); var first = ack(bundle, member, "runtime-1", NOW, 1000);
        race(() -> post(bundle, first), () -> post(bundle, first));
        assertEquals(1, count("mk_release_bundle_ack")); assertEquals(1, auditCount());
        race(() -> post(bundle, ack(bundle, member, "runtime-2", NOW, 500)), () -> post(bundle, ack(bundle, member, "runtime-3", NOW, 500)));
        var readiness = view(get(bundle)).members().stream().filter(item -> item.manifestId().equals(member.manifestId())).findFirst().orElseThrow();
        assertEquals(3, readiness.readyReplicas()); assertEquals(2000, readiness.readyCapacity());
        assertEquals(3, auditCount());
    }

    @Test void concurrentOutOfOrderUpdatesAlwaysKeepTheNewestAndEqualTimeConflicts() throws Exception {
        var bundle = bundle(); var member = bundle.members().getFirst();
        for (int i = 0; i < 5; i++) {
            String runtime = "race-" + i;
            var old = ack(bundle, member, runtime, NOW, 500); var newer = ack(bundle, member, runtime, NOW.plusSeconds(1), 1000);
            race(() -> post(bundle, old), () -> post(bundle, newer));
            assertEquals(newer.signature(), jdbc.queryForObject("select signature_value from mk_release_bundle_ack where tenant_id=? and runtime_id=?", String.class, tenant, runtime));
            assertEquals(200, post(bundle, old).statusCode());
            assertCode(409, "RELEASE_BUNDLE_ACK_CONFLICT", post(bundle, ack(bundle, member, runtime, NOW.plusSeconds(1), 900)));
        }
        assertEquals(5, count("mk_release_bundle_ack"));
    }

    @Test void unrelatedBundlesCanRecordFirstEvidenceConcurrently() throws Exception {
        var left = bundle(); var right = bundle();
        race(() -> post(left, ack(left, left.members().getFirst(), "left", NOW, 1000)),
                () -> post(right, ack(right, right.members().getFirst(), "right", NOW, 1000)));
        assertEquals(2, count("mk_release_bundle_ack")); assertEquals(2, auditCount());
    }

    @Test void evidenceExpiresAndRefreshesWithoutChangingPreparationHistory() throws Exception {
        var bundle = bundle();
        for (var member : bundle.members()) for (int i = 0; i < 2; i++) assertEquals(200, post(bundle, ack(bundle, member, "runtime-" + i, NOW, 1000)).statusCode());
        assertTrue(view(get(bundle)).runtimeReady());
        clock.now = NOW.plusSeconds(301);
        var expired = view(get(bundle)); assertFalse(expired.runtimeReady());
        assertEquals(4, expired.members().stream().mapToInt(ReleaseBundleReadinessService.MemberReadiness::ignoredEvidence).sum());
        for (var member : bundle.members()) for (int i = 0; i < 2; i++) post(bundle, ack(bundle, member, "runtime-" + i, clock.instant(), 1000));
        assertTrue(view(get(bundle)).runtimeReady());
        clock.now = NOW.plusSeconds(3600);
        assertFalse(view(get(bundle)).runtimeReady());
        assertCode(409, "RELEASE_BUNDLE_ACK_INVALID", post(bundle, ack(bundle, bundle.members().getFirst(), "runtime-0", clock.instant(), 1000)));
        assertEquals(0, count("mk_activation_directive"));
    }

    @Test void rejectsLegacyProofMismatchBadSignatureTimeBuildAndCapacity() throws Exception {
        var bundle = bundle(); var member = bundle.members().getFirst(); var valid = ack(bundle, member, "runtime", NOW, 1000);
        assertEquals(400, send(raw(base(bundle) + "/acks", mapper.writeValueAsString(valid.runtimeAck()))).statusCode());
        for (var invalid : List.of(ack(bundle, member, "runtime", NOW.minusSeconds(301), 1000),
                ack(bundle, member, "runtime", NOW.plusSeconds(61), 1000), ack(bundle, member, "runtime", NOW, 1001),
                new ReleaseBundleAck(valid.bundleId(), valid.campaignId(), valid.version(), valid.contentHash(), valid.memberSignature(), valid.runtimeAck(), "forged"),
                sign(new ReleaseBundleAck("different-bundle", valid.campaignId(), valid.version(), valid.contentHash(), valid.memberSignature(), valid.runtimeAck(), "")),
                sign(new ReleaseBundleAck(valid.bundleId(), valid.campaignId(), valid.version(), valid.contentHash(), "wrong-member", valid.runtimeAck(), "")))) {
            assertCode(409, "RELEASE_BUNDLE_ACK_INVALID", post(bundle, invalid));
        }
        RuntimeAck old = valid.runtimeAck();
        for (RuntimeAck changed : List.of(
                new RuntimeAck(old.manifestId(), old.generation(), old.runtimeId(), "other-cell", old.status(), old.buildDigest(), old.supportedAbis(), old.warmedArtifactIds(), old.capacity(), NOW, "ack-key", ""),
                new RuntimeAck(old.manifestId(), old.generation(), old.runtimeId(), old.cell(), old.status(), "unapproved-build", old.supportedAbis(), old.warmedArtifactIds(), old.capacity(), NOW, "ack-key", ""),
                new RuntimeAck(old.manifestId(), old.generation(), old.runtimeId(), old.cell(), old.status(), old.buildDigest(), Set.of(), Set.of("wrong-artifact"), old.capacity(), NOW, "ack-key", ""))) {
            var inner = RuntimeAckSigner.sign(ACK.getPrivate(), tenant, changed);
            var invalid = sign(new ReleaseBundleAck(valid.bundleId(), valid.campaignId(), valid.version(), valid.contentHash(), valid.memberSignature(), inner, ""));
            assertCode(409, "RELEASE_BUNDLE_ACK_INVALID", post(bundle, invalid));
        }
        assertEquals(0, count("mk_release_bundle_ack"));
    }

    @Test void rechecksPermissionsScopeAndInputBoundsBeforeWrites() throws Exception {
        var bundle = bundle(); var ack = ack(bundle, bundle.members().getFirst(), "runtime", NOW, 1000);
        String body = mapper.writeValueAsString(ack), endpoint = base(bundle) + "/acks";
        assertEquals(403, send(raw(endpoint, body).setHeader("X-Dev-Permissions", "release:read")).statusCode());
        assertEquals(400, send(raw(endpoint, body).setHeader("X-Dev-Shop-Ids", "shop-b")).statusCode());
        assertEquals(404, send(raw(endpoint, body).setHeader("X-Dev-Tenant-Id", "other")).statusCode());
        assertEquals(403, send(request(base(bundle) + "/readiness").GET().setHeader("X-Dev-Permissions", "runtime:ack")).statusCode());
        assertEquals(400, send(request(base(bundle) + "/readiness").GET().setHeader("X-Dev-Organization-Ids", "org-b")).statusCode());
        assertCode(400, "RELEASE_BUNDLE_REQUEST_TOO_LARGE", send(raw(endpoint, " ".repeat(ReleaseBundleReadinessService.MAX_ACK_BYTES + 1))));
        assertEquals(403, send(raw(endpoint, " ".repeat(ReleaseBundleReadinessService.MAX_ACK_BYTES + 1)).setHeader("X-Dev-Permissions", "release:read")).statusCode());
        assertEquals(400, send(raw(endpoint, "null")).statusCode());
        assertEquals(400, send(raw(endpoint, body.substring(0, body.length() - 1) + ",\"unexpected\":true}")).statusCode());
        assertEquals(0, count("mk_release_bundle_ack"));
    }

    @Test void auditFailureRollsBackEvidenceReplacementAndCanRetry() throws Exception {
        var bundle = bundle(); var member = bundle.members().getFirst(); var first = ack(bundle, member, "runtime", NOW, 1000);
        assertEquals(200, post(bundle, first).statusCode());
        var next = ack(bundle, member, "runtime", NOW.plusSeconds(1), 500);
        String trigger = "ready_audit_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.execute("CREATE TRIGGER " + trigger + " BEFORE INSERT ON mk_audit FOR EACH ROW BEGIN IF NEW.tenant_id = '" + tenant + "' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='test audit failure'; END IF; END");
        try {
            assertEquals(500, post(bundle, next).statusCode());
            assertEquals(first.signature(), jdbc.queryForObject("select signature_value from mk_release_bundle_ack where tenant_id=?", String.class, tenant));
            assertEquals(1, auditCount());
        } finally { jdbc.execute("DROP TRIGGER " + trigger); }
        assertEquals(200, post(bundle, next).statusCode()); assertEquals(2, auditCount());
    }

    @Test void ignoresCorruptedEvidenceAndBoundsRuntimeIdentities() throws Exception {
        var bundle = bundle(); var member = bundle.members().getFirst();
        for (int i = 0; i < 64; i++) assertEquals(200, post(bundle, ack(bundle, member, "runtime-" + i, NOW, 1000)).statusCode());
        assertEquals(200, post(bundle, ack(bundle, member, "runtime-0", NOW, 1000)).statusCode());
        assertCode(409, "RELEASE_BUNDLE_ACK_LIMIT", post(bundle, ack(bundle, member, "runtime-over-limit", NOW, 1000)));
        jdbc.update("update mk_release_bundle_ack set ack_json=? where tenant_id=?", "{broken", tenant);
        var readiness = view(get(bundle)); assertFalse(readiness.runtimeReady());
        assertEquals(64, readiness.members().stream().mapToInt(ReleaseBundleReadinessService.MemberReadiness::ignoredEvidence).sum());
        assertEquals(0, readiness.members().stream().mapToInt(ReleaseBundleReadinessService.MemberReadiness::readyReplicas).sum());
    }

    @Test void databaseRejectsForeignBundleMembersAndReadRejectsCorruptHeader() throws Exception {
        var left = bundle(); var right = bundle(); var member = left.members().getFirst();
        var ack = ack(left, member, "runtime", NOW, 1000);
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () -> repository.saveReleaseBundleAck(
                new ControlRepository.ReleaseBundleAckRow(tenant, right.bundleId(), member.manifestId(), "runtime",
                        mapper.writeValueAsString(ack), com.acme.marketing.platform.time.SqlTime.format(NOW), ack.signature())));
        assertEquals(0, count("mk_release_bundle_ack"));
        jdbc.update("update mk_release_bundle set environment_name=? where tenant_id=? and bundle_id=?", "corrupt", tenant, left.bundleId());
        assertCode(409, "RELEASE_BUNDLE_CONTENT_INVALID", get(left));
    }

    /** 模拟机器响应丢失，真实数据库/HTTP 验证发布投影只从精确运行回执恢复。 */
    @Test void lostApplyResponseIsRecoveredWithoutResendingAndRollbackKeepsSequence() throws Exception {
        var bundle = bundle();
        String endpoint = "/api/v1/campaigns/" + bundle.campaignId() + "/release-bundles/operations";
        var applied = new java.util.concurrent.ConcurrentHashMap<String, BundleReleaseRuntime.Applied>();
        when(bundleRuntime.prepare(any())).thenReturn(List.of());
        when(bundleRuntime.findApplied(any(), nullable(Instant.class))).thenAnswer(call -> {
            ReleaseBundleActivationDirective value = call.getArgument(0);
            return java.util.Optional.ofNullable(applied.get(value.directiveId()));
        });
        when(bundleRuntime.apply(any(), anyLong(), any(), any())).thenAnswer(call -> {
            ((Runnable)call.getArgument(3)).run();
            ReleaseBundleActivationDirective d = call.getArgument(0);
            var receipt = new BundleReleaseRuntime.Applied("APPLIED", d, clock.instant(), d.activationSequence());
            applied.put(d.directiveId(), receipt);
            if (d.activationSequence() == 1) throw ReleaseBundleError.RUNTIME_UNAVAILABLE.conflict();
            return receipt;
        });
        var request = new BundleReleaseOperations.Request(1, "ACTIVATE", 0);
        assertEquals(403, send(raw(endpoint, mapper.writeValueAsString(request)).header("Idempotency-Key", "publish-v1")
                .setHeader("X-Dev-Permissions", "release:write")).statusCode());
        var pending = command(endpoint, "publish-v1", request);
        assertEquals("PENDING", pending.state()); assertEquals(ReleaseBundleError.OPERATION_NOT_READY.code(), pending.reasonCode());
        verify(bundleRuntime, never()).apply(any(), anyLong(), any(), any());
        String historyPath = endpoint.replace("/operations", "/operation-audits");
        var beforeReceipt = mapper.readTree(send(request(historyPath).GET()).body()).path("items");
        assertEquals(1, beforeReceipt.size());
        assertEquals("ACCEPTED", beforeReceipt.get(0).path("stageCode").asString());
        assertEquals(pending.businessNo(), beforeReceipt.get(0).path("businessNo").asString());
        assertEquals("PENDING", beforeReceipt.get(0).path("afterState").asString());
        assertEquals(403, send(request(historyPath).setHeader("X-Dev-Permissions", "release:activate").GET()).statusCode());
        assertEquals(400, send(request(historyPath).setHeader("X-Dev-Shop-Ids", "shop-other").GET()).statusCode());
        warm(bundle);
        var uncertain = command(endpoint, "publish-v1", request);
        assertEquals("PENDING", uncertain.state()); assertEquals(pending.operationId(), uncertain.operationId());
        assertEquals(1, applied.size());
        var confirmed = send(request(endpoint + "/" + pending.operationId()).GET());
        assertEquals("APPLIED", mapper.readTree(confirmed.body()).path("state").asString());
        assertEquals("APPLIED", command(endpoint, "publish-v1", request).state());
        String archiveEndpoint = System.getenv("MKT_AUDIT_ARCHIVE_TEST_ENDPOINT");
        boolean testArchive = archiveEndpoint != null && archiveEndpoint.matches("http://127[.]0[.]0[.]1:[0-9]+");
        String initialBatch = null;
        if (testArchive) {
            try (var fixture = releaseArchiveFixture(archiveEndpoint, bundle.campaignId())) {
                initialBatch = fixture.workflow().verify(bundle.campaignId() + "@1");
                assertNotNull(initialBatch);
            }
        }
        verify(bundleRuntime, times(1)).apply(any(), anyLong(), any(), any());
        assertCode(409, ReleaseBundleError.OPERATION_KEY_CONFLICT.code(), send(raw(endpoint, mapper.writeValueAsString(
                new BundleReleaseOperations.Request(2, "ACTIVATE", 1))).header("Idempotency-Key", "publish-v1")));
        var second = nextBundle(bundle.campaignId(), 1);
        warm(second);
        var switched = command(endpoint, "publish-v2", new BundleReleaseOperations.Request(2, "ACTIVATE", 1));
        assertEquals("APPLIED", switched.state()); assertEquals(2, switched.activationSequence());
        var summary = mapper.readTree(send(request(endpoint + "?environment=local&cell=cell-a&version=1").GET()).body());
        assertTrue(summary.path("rollbackEligible").asBoolean());
        var rolled = command(endpoint, "rollback-v1", new BundleReleaseOperations.Request(1, "ROLLBACK", 2));
        assertEquals("APPLIED", rolled.state()); assertEquals(3, rolled.activationSequence());
        assertEquals(403, send(raw(endpoint + "/" + rolled.operationId() + ":retry", "")
                .setHeader("X-Dev-Permissions", "release:activate")).statusCode());
        assertEquals(3, count("mk_bundle_release_operation"));
        var receiptEvents = mapper.readTree(send(request(historyPath).GET()).body()).path("items");
        assertEquals("release.rollback", receiptEvents.get(0).path("actionCode").asString());
        assertEquals("CONFIRMED", receiptEvents.get(0).path("stageCode").asString());
        assertEquals(3L, receiptEvents.get(0).path("activationSequence").asLong());
        assertFalse(receiptEvents.get(0).path("observedAt").asString().isEmpty());
        assertEquals(6, jdbc.queryForObject("""
                SELECT COUNT(*) FROM mk_release_operation_audit e
                JOIN mk_audit a ON a.tenant_id=e.tenant_id AND a.audit_id=e.chain_audit_id
                WHERE e.tenant_id=?
                """, Integer.class, tenant));
        assertEquals(2, jdbc.queryForObject("""
                SELECT COUNT(*) FROM mk_release_operation_audit e
                JOIN mk_release_operation_audit origin ON origin.event_id=e.original_event_id AND origin.tenant_id=e.tenant_id
                WHERE e.tenant_id=? AND e.resource_id=? AND e.stage_code='REPLAYED'
                """, Integer.class, tenant, pending.operationId()));
        assertEquals(2, jdbc.queryForObject("""
                SELECT COUNT(*) FROM mk_release_operation_audit WHERE tenant_id=?
                AND stage_code='DENIED' AND campaign_id IS NULL AND action_code IN ('release.publish','release.retry')
                """, Integer.class, tenant));
        if (testArchive) {
            verifyReleaseArchive(archiveEndpoint, bundle.campaignId(), pending.businessNo(), rolled.businessNo(), initialBatch);
        }
    }

    private ReleaseArchiveFixture releaseArchiveFixture(String endpoint, String campaign) {
        var scope = new TenantId(tenant);
        var kind = com.acme.marketing.platform.web.archive.AuditArchiveWorkflow.Source.RELEASE;
        var source = archiveSession.getMapper(com.acme.marketing.control.infrastructure.persistence.mapper.ReleaseAuditArchiveMapper.class);
        var manifest = archiveSession.getMapper(com.acme.marketing.platform.web.archive.persistence.AuditArchiveManifestMapper.class);
        var store = new com.acme.marketing.platform.web.archive.S3AuditArchiveStorage(URI.create(endpoint), "us-east-1",
                "mkt-release-audit-verification", kind.prefix(scope),
                System.getenv("MKT_RELEASE_ARCHIVE_WRITE_KEY"), System.getenv("MKT_RELEASE_ARCHIVE_WRITE_SECRET"),
                System.getenv("MKT_RELEASE_ARCHIVE_RESTORE_KEY"), System.getenv("MKT_RELEASE_ARCHIVE_RESTORE_SECRET"));
        var archive = new com.acme.marketing.platform.web.archive.AuditArchiveWorkflow(kind, scope, Duration.ofDays(1),
                java.time.Clock.offset(java.time.Clock.systemUTC(), Duration.ofDays(3)), manifest,
                (owner, resource, cutoff, limit) -> source.eligible(owner, campaign, 1, cutoff, limit), store, archiveTransactions);
        return new ReleaseArchiveFixture(store, archive);
    }

    private void verifyReleaseArchive(String endpoint, String campaign, String publishNo, String rollbackNo, String initialBatch) {
        try (var fixture = releaseArchiveFixture(endpoint, campaign)) {
            var archive = fixture.workflow();
            String id = archive.verify(campaign + "@1");
            assertNotNull(id);
            assertNotEquals(initialBatch, id);
            var old = archive.verifiedSnapshot(initialBatch);
            assertEquals(List.of(publishNo), old.events().stream().map(row -> row.fact())
                    .filter(fact -> "CONFIRMED".equals(fact.path("stage").asString()))
                    .map(fact -> fact.path("businessNo").asString()).toList());
            assertEquals(id, archive.verify(campaign + "@1"));
            String ticket = "isolated-release-archive";
            jdbc.update("""
                    INSERT INTO mk_operation_audit_archive_grant(tenant_id,ticket_id,batch_id,actor_id,approved_by,expires_at)
                    VALUES(?,?,?,'release-recovery','archive-reviewer',UTC_TIMESTAMP(3)+INTERVAL 1 HOUR)
                    """, tenant, ticket, id);
            var restored = archive.restore(tenant, ticket, "release-recovery", resource -> assertEquals(campaign + "@1", resource));
            assertEquals(restored, archive.restored(tenant, ticket, "release-recovery", ignored -> {}));
            var terminals = restored.events().stream().map(row -> row.fact())
                    .filter(fact -> "CONFIRMED".equals(fact.path("stage").asString())).toList();
            assertEquals(2, terminals.size());
            assertEquals(java.util.Set.of(publishNo, rollbackNo), terminals.stream().map(value -> value.path("businessNo").asString())
                    .collect(java.util.stream.Collectors.toSet()));
            assertTrue(terminals.stream().allMatch(fact -> "1".equals(fact.path("versionNo").asString())
                    && !fact.path("observedAt").asString().isEmpty() && !fact.path("chainAuditId").asString().isEmpty()));
            assertTrue(restored.events().stream().anyMatch(row -> "ATTEMPT".equals(row.fact().path("stage").asString())));
            assertEquals(3, count("mk_bundle_release_operation"));
            assertThrows(com.acme.marketing.platform.error.NotFoundException.class,
                    () -> archive.restored(tenant, ticket, "archive-reviewer", ignored -> {}));
        }
    }

    private record ReleaseArchiveFixture(com.acme.marketing.platform.web.archive.S3AuditArchiveStorage store,
            com.acme.marketing.platform.web.archive.AuditArchiveWorkflow workflow) implements AutoCloseable {
        @Override public void close() { store.close(); }
    }

    /** 到期查询不会重新发送；未签发前的确定性拒绝释放位置给后续有效命令。 */
    @Test void activeOwnershipExpirationAndConflictingRequestsUseDurableOperations() throws Exception {
        var bundle = bundle();
        String endpoint = "/api/v1/campaigns/" + bundle.campaignId() + "/release-bundles/operations";
        var request = new BundleReleaseOperations.Request(1, "ACTIVATE", 0);
        when(bundleRuntime.prepare(any())).thenReturn(List.of());
        var op = command(endpoint, "create-one", request);
        assertCode(409, ReleaseBundleError.OPERATION_CONFLICT.code(), send(raw(endpoint, mapper.writeValueAsString(request))
                .header("Idempotency-Key", "other-key")));
        clock.now = NOW.plusSeconds(3600);
        assertEquals("EXPIRED", mapper.readTree(send(request(endpoint + "/" + op.operationId()).GET()).body()).path("state").asString());
        assertEquals(403, send(request(endpoint + "/" + op.operationId()).setHeader("X-Dev-Permissions", "release:activate").GET()).statusCode());
        verify(bundleRuntime, never()).apply(any(), anyLong(), any(), any());
    }

    /** 两个控制请求同时占用同一位置时只有一份未决意图，重放赢家也不增发序号。 */
    @Test void competingCommandsReserveOnePositionAndPermanentSequence() throws Exception {
        var bundle = bundle();
        String endpoint = "/api/v1/campaigns/" + bundle.campaignId() + "/release-bundles/operations";
        String body = mapper.writeValueAsString(new BundleReleaseOperations.Request(1, "ACTIVATE", 0));
        when(bundleRuntime.prepare(any())).thenReturn(List.of());
        try (var pool = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            var left = pool.submit(() -> { start.await(); return send(raw(endpoint, body).header("Idempotency-Key", "publish-left")); });
            var right = pool.submit(() -> { start.await(); return send(raw(endpoint, body).header("Idempotency-Key", "publish-right")); });
            start.countDown();
            var x = left.get(); var y = right.get();
            assertEquals(Set.of(200,409), Set.of(x.statusCode(), y.statusCode()));
            var winner = x.statusCode() == 200 ? "publish-left" : "publish-right";
            race(() -> send(raw(endpoint,body).header("Idempotency-Key",winner)),
                    () -> send(raw(endpoint,body).header("Idempotency-Key",winner)));
        }
        assertEquals(1, count("mk_bundle_release_operation"));
        assertEquals(1L, jdbc.queryForObject("select max(activation_sequence) from mk_bundle_release_operation where tenant_id=?", Long.class, tenant));
    }

    /** 外部响应未知时经过服务端时钟确认才能终结，截止后禁止再次发送应用。 */
    @Test void expiredSignedOperationWaitsForRemoteClockAndNeverAppliesAgain() throws Exception {
        var bundle = bundle();
        warm(bundle);
        var endpoint = "/api/v1/campaigns/" + bundle.campaignId() + "/release-bundles/operations";
        when(bundleRuntime.prepare(any())).thenReturn(List.of());
        when(bundleRuntime.apply(any(), anyLong(), any(), any())).thenThrow(ReleaseBundleError.RUNTIME_UNAVAILABLE.conflict());
        var initial = command(endpoint, "clock-difference", new BundleReleaseOperations.Request(1, "ACTIVATE", 0));
        assertEquals("PENDING", initial.state());
        var deadline = NOW.plus(Duration.ofMinutes(10));
        clock.now = deadline;
        when(bundleRuntime.findApplied(any(), eq(deadline))).thenThrow(ReleaseBundleError.RUNTIME_UNAVAILABLE.conflict())
                .thenReturn(java.util.Optional.empty());
        String exact = endpoint + "/" + initial.operationId();
        var waiting = send(raw(exact + ":retry", ""));
        assertEquals("PENDING", mapper.readTree(waiting.body()).path("state").asString());
        var finalView = send(request(exact).GET());
        assertEquals("EXPIRED", mapper.readTree(finalView.body()).path("state").asString());
        verify(bundleRuntime,times(1)).apply(any(), anyLong(), any(), any());
    }

    /** 在签名前撤回审批，不能将旧 READY 用作现行审批，也不能阻塞有效后续发布。 */
    @Test void withdrawnApprovalRejectsBeforeSigningAndFreesPosition() throws Exception {
        var bundle = bundle();
        warm(bundle);
        String endpoint = "/api/v1/campaigns/" + bundle.campaignId() + "/release-bundles/operations";
        when(bundleRuntime.prepare(any())).thenReturn(List.of());
        jdbc.update("update mk_approval_case set status='REJECTED' where tenant_id=? and case_id=?",
                tenant, bundle.members().getFirst().approvalCaseIds().getFirst());
        var rejected = command(endpoint, "approval-lost", new BundleReleaseOperations.Request(1, "ACTIVATE", 0));
        assertEquals("REJECTED", rejected.state());
        assertEquals(ReleaseBundleError.MEMBER_UNAVAILABLE.code(), rejected.reasonCode());
        var next = nextBundle(bundle.campaignId(), 1);
        var second = command(endpoint, "try-next-version", new BundleReleaseOperations.Request(next.version(), "ACTIVATE", 0));
        assertEquals(2, second.activationSequence());
        verify(bundleRuntime,never()).apply(any(), anyLong(), any(), any());
    }

    private BundleReleaseOperations.View command(String path, String key, BundleReleaseOperations.Request request) throws Exception {
        var response = send(raw(path, mapper.writeValueAsString(request)).header("Idempotency-Key", key));
        assertEquals(200, response.statusCode(), response.body());
        return mapper.readValue(response.body(), BundleReleaseOperations.View.class);
    }
    private void warm(ReleaseBundleService.BundleView bundle) throws Exception {
        for (var member : bundle.members()) {
            assertEquals(200, post(bundle, ack(bundle, member, "one", NOW, 1000)).statusCode());
            assertEquals(200, post(bundle, ack(bundle, member, "two", NOW, 1000)).statusCode());
        }
    }

    private ReleaseBundleService.BundleView bundle() throws Exception {
        String campaign = UUID.randomUUID().toString();
        repository.saveCampaign(new ControlRepository.CampaignWrite(tenant, campaign, "组合就绪", "隔离验收", "DRAFT", "org-a", "shop-a", NOW.toString(), NOW.toString(), "STANDARD"));
        return nextBundle(campaign, 0);
    }
    private ReleaseBundleService.BundleView nextBundle(String campaign, long previous) throws Exception {
        var members = List.of(member(campaign, "a" + previous), member(campaign, "b" + previous));
        var response = send(raw("/api/v1/campaigns/" + campaign + "/release-bundles", mapper.writeValueAsString(Map.of("expectedVersion", previous, "manifestIds", members)))
                .header("Idempotency-Key", "prepare-" + campaign + "-" + previous));
        assertEquals(201, response.statusCode(), response.body());
        return mapper.readValue(response.body(), ReleaseBundleService.BundleView.class);
    }
    private String member(String campaign, String namespace) {
        String id = UUID.randomUUID().toString(), definition = "def-" + id, approval = "case-" + id;
        String digest = "sha256:" + "b".repeat(64);
        repository.saveDefinition(new ControlRepository.DefinitionWrite(tenant, definition, campaign, 1, "JOURNEY_DAG", "{}", digest, "APPROVED", "author", NOW.toString(), NOW.toString()));
        repository.saveApprovalCase(new ControlRepository.ApprovalCaseWrite(tenant, approval, definition, 1, "author", "LEGAL", "APPROVED", NOW.toString(), NOW.toString()));
        var artifact = new ArtifactReference("artifact-" + id, "JOURNEY_PLAN", "memory:test", "sha256:" + "a".repeat(64), digest, "key", "fixture", "marketing-journey-plan/1", definition, 1);
        var signed = new ReleaseManifestSigner().sign(new ReleaseManifest(id, new TenantId(tenant), "local", "cell-a", "journey", namespace + "-" + campaign,
                1, 0, List.of(), List.of(), List.of(artifact), Map.of(), 0, NOW.minusSeconds(10), NOW.plusSeconds(3600), "author", List.of(approval), NOW.minusSeconds(10), ""), releaseKeys.activePrivateKey());
        repository.saveManifest(new ControlRepository.ManifestWrite(tenant, id, "local", "cell-a", "journey", signed.namespace(), 1, "STAGED", mapper.writeValueAsString(signed), NOW.toString(), null));
        repository.tryCreateReleaseSlot(new ControlRepository.ReleaseSlotWrite(tenant, "local", "cell-a", "journey", signed.namespace(), 0, 0, 1, 0, NOW.toString()));
        return id;
    }
    private ReleaseBundleAck ack(ReleaseBundleService.BundleView bundle, ReleaseManifest member, String runtime, Instant at, long capacity) {
        var inner = RuntimeAckSigner.sign(ACK.getPrivate(), tenant, new RuntimeAck(member.manifestId(), member.generation(), runtime, member.cell(), RuntimeAck.Status.READY, "test-build",
                Set.of(member.artifacts().getFirst().abi()), Set.of(member.artifacts().getFirst().artifactId()), capacity, at, "ack-key", ""));
        return sign(new ReleaseBundleAck(bundle.bundleId(), bundle.campaignId(), bundle.version(), bundle.contentHash(), member.signature(), inner, ""));
    }
    private ReleaseBundleAck sign(ReleaseBundleAck ack) { return ReleaseBundleAckSigner.sign(ACK.getPrivate(), tenant, ack); }
    private ReleaseBundleReadinessService.BundleReadinessView view(HttpResponse<String> response) {
        assertEquals(200, response.statusCode(), response.body());
        return mapper.readValue(response.body(), ReleaseBundleReadinessService.BundleReadinessView.class);
    }
    private String base(ReleaseBundleService.BundleView bundle) { return "/api/v1/campaigns/" + bundle.campaignId() + "/release-bundles/versions/" + bundle.version(); }
    private HttpResponse<String> post(ReleaseBundleService.BundleView bundle, ReleaseBundleAck ack) throws Exception { return send(raw(base(bundle) + "/acks", mapper.writeValueAsString(ack))); }
    private HttpResponse<String> get(ReleaseBundleService.BundleView bundle) throws Exception { return send(request(base(bundle) + "/readiness").GET()); }
    private HttpRequest.Builder raw(String path, String body) { return request(path).POST(HttpRequest.BodyPublishers.ofString(body)); }
    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json").header("X-Dev-Tenant-Id", tenant).header("X-Dev-Actor-Id", "test-runtime")
                .header("X-Dev-Organization-Ids", "org-a").header("X-Dev-Shop-Ids", "shop-a").header("X-Dev-Permissions", "*");
    }
    private HttpResponse<String> send(HttpRequest.Builder request) throws Exception { return http.send(request.build(), HttpResponse.BodyHandlers.ofString()); }
    private void assertCode(int status, String code, HttpResponse<String> response) {
        assertEquals(status, response.statusCode(), response.body()); assertEquals(code, mapper.readTree(response.body()).path("code").asString());
    }
    private int count(String table) { return jdbc.queryForObject("select count(*) from " + table + " where tenant_id=?", Integer.class, tenant); }
    private int auditCount() { return jdbc.queryForObject("select count(*) from mk_audit where tenant_id=? and action_name='RELEASE_BUNDLE_ACKNOWLEDGED'", Integer.class, tenant); }
    private void race(java.util.concurrent.Callable<HttpResponse<String>> a, java.util.concurrent.Callable<HttpResponse<String>> b) throws Exception {
        try (var pool = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            var left = pool.submit(() -> { start.await(); return a.call(); }); var right = pool.submit(() -> { start.await(); return b.call(); });
            start.countDown(); var x = left.get(); var y = right.get();
            assertEquals(200, x.statusCode(), x.body()); assertEquals(200, y.statusCode(), y.body());
        }
    }
}
