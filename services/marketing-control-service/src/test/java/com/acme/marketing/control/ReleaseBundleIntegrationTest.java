package com.acme.marketing.control;

import static org.junit.jupiter.api.Assertions.*;

import com.acme.marketing.contracts.release.ArtifactReference;
import com.acme.marketing.contracts.release.ReleaseManifest;
import com.acme.marketing.contracts.release.ReleaseBundleManifest;
import com.acme.marketing.contracts.release.ReleaseBundleManifestSigner;
import com.acme.marketing.control.application.ReleaseBundleService;
import com.acme.marketing.contracts.release.ReleaseManifestSigner;
import com.acme.marketing.control.application.ControlRepository;
import com.acme.marketing.platform.crypto.SigningKeyRing;
import com.acme.marketing.platform.identity.TenantId;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 独占真实MySQL验证组合事务与发布竞争；测试不连接运行中的营销库。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ReleaseBundleIntegrationTest {
    private static final MySQLContainer MYSQL = mysql();
    private static MySQLContainer mysql() {
        var instance = new MySQLContainer("mysql:8.4.11")
                .withCommand("--log-bin-trust-function-creators=1", "--performance-schema=OFF",
                        "--innodb-buffer-pool-size=64M", "--max-connections=30")
                .withDatabaseName("bundle_test").withUsername("test").withPassword("test")
                .withTmpFs(Map.of("/var/lib/mysql", "rw"))
                .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withMemory(512L * 1024 * 1024)
                        .withMemorySwap(512L * 1024 * 1024))
                .withStartupTimeout(Duration.ofMinutes(5));
        instance.start(); return instance;
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", MYSQL::getJdbcUrl); r.add("spring.datasource.username", MYSQL::getUsername);
        r.add("spring.datasource.password", MYSQL::getPassword); r.add("spring.flyway.user", MYSQL::getUsername);
        r.add("spring.flyway.password", MYSQL::getPassword);
    }
    @AfterAll static void stop() { MYSQL.stop(); }
    @LocalServerPort int port;
    @Autowired ControlRepository repository;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired SigningKeyRing keys;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final String tenant = "bundle-" + UUID.randomUUID();
    private final Instant now = Instant.now();

    @Test void freezesSignedMembersAndImmutableHistoryWithoutPublishing() throws Exception {
        String campaign = campaign();
        assertCode(404, "RELEASE_BUNDLE_NOT_FOUND", send(request(path(campaign) + "/latest").GET()));
        var firstMembers = members(campaign);
        var first = save(campaign, 0, firstMembers, "first-bundle-key");
        assertEquals(201, first.statusCode(), first.body());
        var stored = mapper.readTree(first.body());
        assertEquals("PREPARING", stored.path("state").asString());
        assertEquals(1, stored.path("version").asLong());
        assertEquals(64, stored.path("contentHash").asString().length());
        assertEquals(firstMembers.stream().sorted().toList(), stored.path("members").valueStream()
                .map(value -> value.path("manifestId").asString()).toList());
        var second = save(campaign, 1, members(campaign), "second-bundle-key");
        assertEquals(201, second.statusCode(), second.body());
        assertEquals(2, mapper.readTree(second.body()).path("version").asLong());
        assertEquals(stored, mapper.readTree(send(request(path(campaign) + "/versions/1").GET()).body()));
        assertEquals(mapper.readTree(second.body()), mapper.readTree(send(request(path(campaign) + "/latest").GET()).body()));
        assertEquals(2, count("mk_release_bundle")); assertEquals(4, memberCount());
        assertEquals(0, count("mk_outbox")); assertEquals(0, count("mk_activation_directive"));
        assertEquals(2, jdbc.queryForObject("select count(*) from mk_audit where tenant_id=? and action_name='RELEASE_BUNDLE_PREPARED'", Integer.class, tenant));
    }

    @Test void exportsWholeSignedContentWithoutChangingPreparationOrActivation() throws Exception {
        String campaign = campaign();
        var original = save(campaign, 0, members(campaign), "proof-bundle-key");
        assertEquals(201, original.statusCode(), original.body());
        var response = send(request(path(campaign) + "/versions/1/manifest").GET());
        assertEquals(200, response.statusCode(), response.body());
        var proof = mapper.readValue(response.body(), ReleaseBundleManifest.class);
        assertTrue(ReleaseBundleManifestSigner.verify(keys.publicKeys().get(proof.signatureKeyId()), proof));
        assertEquals(tenant, proof.tenantId().value()); assertEquals(campaign, proof.campaignId());
        assertEquals(mapper.readTree(original.body()).path("contentHash").asString(), proof.contentHash());
        assertEquals(proof, mapper.readValue(send(request(path(campaign) + "/versions/1/manifest").GET()).body(), ReleaseBundleManifest.class));
        assertEquals(mapper.readTree(original.body()), mapper.readTree(send(request(path(campaign) + "/versions/1").GET()).body()));
        assertEquals(1, count("mk_audit")); assertEquals(0, count("mk_activation_directive")); assertEquals(0, count("mk_outbox"));
        // 模拟持久内容损坏：成员保留旧签名但字段变化，控制面不能用新外层签名背书。
        var corrupted = (tools.jackson.databind.node.ObjectNode) mapper.readTree(original.body());
        ((tools.jackson.databind.node.ObjectNode) corrupted.path("members").get(0)).put("createdBy", "corrupted-author");
        jdbc.update("update mk_release_bundle set content_json=? where tenant_id=? and bundle_id=?",
                mapper.writeValueAsString(corrupted), tenant, proof.bundleId());
        assertCode(409, "RELEASE_BUNDLE_MEMBER_UNAVAILABLE", send(request(path(campaign) + "/versions/1/manifest").GET()));
    }

    @Test void expiredHistoryRemainsReadableButCannotIssuePreparationProof() throws Exception {
        String campaign = campaign();
        var ids = List.of(member(campaign, "old-a", "local", now.minusSeconds(10)),
                member(campaign, "old-b", "local", now.minusSeconds(5)));
        var members = ids.stream().map(id -> mapper.readValue(repository.findManifest(tenant, id, false).orElseThrow().manifestJson(), ReleaseManifest.class)).toList();
        var view = new ReleaseBundleService.BundleView(UUID.randomUUID().toString(), campaign, 1, "local", "cell-a",
                ReleaseBundleService.State.PREPARING, ReleaseBundleManifest.contentHash(tenant, campaign, members), members, "historical-author", now.minusSeconds(60));
        repository.saveReleaseBundle(new ControlRepository.ReleaseBundleRow(tenant, view.bundleId(), campaign, 1,
                "local", "cell-a", "PREPARING", mapper.writeValueAsString(view), view.contentHash(), "historical-proof-key", "historical-author", view.createdAt().toString()));
        assertEquals(200, send(request(path(campaign) + "/versions/1").GET()).statusCode());
        assertCode(409, "RELEASE_BUNDLE_MEMBER_UNAVAILABLE", send(request(path(campaign) + "/versions/1/manifest").GET()));
        assertEquals(0, count("mk_audit")); assertEquals(0, count("mk_activation_directive"));
    }

    @Test void concurrentSameKeyAndReorderedMembersReplayOneResult() throws Exception {
        String campaign = campaign(); var members = members(campaign);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            var left = pool.submit(() -> { start.await(); return save(campaign, 0, members, "same-bundle-key"); });
            var right = pool.submit(() -> { start.await(); return save(campaign, 0, members.reversed(), "same-bundle-key"); });
            start.countDown(); var a = left.get(); var b = right.get();
            assertEquals(201, a.statusCode(), a.body()); assertEquals(201, b.statusCode(), b.body());
            assertEquals(mapper.readTree(a.body()), mapper.readTree(b.body()));
        }
        assertEquals(1, count("mk_release_bundle")); assertEquals(2, memberCount());
        assertCode(409, "IDEMPOTENCY_PAYLOAD_CONFLICT", save(campaign, 1, members, "same-bundle-key"));
    }

    @Test void serializesCompetingVersionsAndRejectsMemberReuse() throws Exception {
        String campaign = campaign(); var aMembers = members(campaign); var bMembers = members(campaign);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            var left = pool.submit(() -> { start.await(); return save(campaign, 0, aMembers, "version-race-a"); });
            var right = pool.submit(() -> { start.await(); return save(campaign, 0, bMembers, "version-race-b"); });
            start.countDown(); var a = left.get(); var b = right.get();
            assertEquals(List.of(201, 409), List.of(a.statusCode(), b.statusCode()).stream().sorted().toList(), a.body() + b.body());
            assertCode(409, "RELEASE_BUNDLE_VERSION_CONFLICT", a.statusCode() == 409 ? a : b);
            assertCode(409, "RELEASE_BUNDLE_MEMBER_ALREADY_BOUND", save(campaign, 1, a.statusCode() == 201 ? aMembers : bMembers, "reuse-members-key"));
        }
        assertEquals(1, count("mk_release_bundle"));
    }

    @Test void unrelatedCampaignsCanPrepareConcurrentlyWithoutMembershipGapLocks() throws Exception {
        String first = campaign(), second = campaign();
        var aMembers = members(first); var bMembers = members(second);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            var a = pool.submit(() -> { start.await(); return save(first, 0, aMembers, "independent-first-key"); });
            var b = pool.submit(() -> { start.await(); return save(second, 0, bMembers, "independent-second-key"); });
            start.countDown(); var left = a.get(); var right = b.get();
            assertEquals(201, left.statusCode(), left.body()); assertEquals(201, right.statusCode(), right.body());
        }
        assertEquals(2, count("mk_release_bundle")); assertEquals(4, memberCount());
    }

    @Test void checksCurrentPermissionsScopeAndActorBeforeReplay() throws Exception {
        String campaign = campaign(); var members = members(campaign);
        assertEquals(201, save(campaign, 0, members, "scope-bundle-key").statusCode());
        String body = mapper.writeValueAsString(Map.of("expectedVersion", 0, "manifestIds", members));
        assertEquals(403, send(write(path(campaign), body, "scope-bundle-key").setHeader("X-Dev-Permissions", "release:read")).statusCode());
        assertEquals(400, send(write(path(campaign), body, "scope-bundle-key").setHeader("X-Dev-Organization-Ids", "org-b")).statusCode());
        assertEquals(400, send(write(path(campaign), body, "scope-bundle-key").setHeader("X-Dev-Shop-Ids", "shop-b")).statusCode());
        assertCode(409, "IDEMPOTENCY_PAYLOAD_CONFLICT", send(write(path(campaign), body, "scope-bundle-key").setHeader("X-Dev-Actor-Id", "another-author")));
        for (String suffix : List.of("/latest", "/versions/1", "/versions/1/manifest")) {
            assertEquals(403, send(request(path(campaign) + suffix).setHeader("X-Dev-Permissions", "release:write").GET()).statusCode());
            assertEquals(400, send(request(path(campaign) + suffix).setHeader("X-Dev-Shop-Ids", "shop-b").GET()).statusCode());
            assertEquals(404, send(request(path(campaign) + suffix).setHeader("X-Dev-Tenant-Id", "foreign-tenant").GET()).statusCode());
        }
        assertEquals(1, count("mk_release_bundle"));
    }

    @Test void refusesWrongCampaignDuplicateSlotScopeAndUnavailableMembers() throws Exception {
        String campaign = campaign(); var good = member(campaign, "ns-good", "test", now.plusSeconds(3600));
        assertCode(409, "RELEASE_BUNDLE_CAMPAIGN_MISMATCH", save(campaign, 0, List.of(good, member(campaign(), "ns-other", "test", now.plusSeconds(3600))), "wrong-campaign-key"));
        assertCode(409, "RELEASE_BUNDLE_DUPLICATE_SLOT", save(campaign, 0, List.of(good, member(campaign, "ns-good", "test", now.plusSeconds(3600))), "duplicate-slot-key"));
        assertCode(409, "RELEASE_BUNDLE_SCOPE_MISMATCH", save(campaign, 0, List.of(good, member(campaign, "ns-other", "prod", now.plusSeconds(3600))), "wrong-environment-key"));
        assertCode(409, "RELEASE_BUNDLE_MEMBER_UNAVAILABLE", save(campaign, 0, List.of(good, member(campaign, "ns-expired", "test", now.minusSeconds(1))), "expired-member-key"));
        var active = member(campaign, "ns-active", "test", now.plusSeconds(3600));
        repository.updateManifestState(tenant, active, "ACTIVE");
        assertCode(409, "RELEASE_BUNDLE_MEMBER_UNAVAILABLE", save(campaign, 0, List.of(good, active), "active-member-key"));
        assertEquals(0, count("mk_release_bundle")); assertEquals(0, memberCount());
    }

    @Test void rejectsAlteredSignedContentAndMalformedOrOversizedBodies() throws Exception {
        String campaign = campaign(); var members = members(campaign);
        var row = repository.findManifest(tenant, members.getFirst(), false).orElseThrow();
        var tree = (tools.jackson.databind.node.ObjectNode) mapper.readTree(row.manifestJson());
        tree.put("namespace", "tampered");
        jdbc.update("update mk_release_manifest set manifest_json=? where tenant_id=? and manifest_id=?", tree.toString(), tenant, members.getFirst());
        assertCode(409, "RELEASE_BUNDLE_MEMBER_UNAVAILABLE", save(campaign, 0, members, "altered-content-key"));
        for (String body : List.of("{}", "{\"expectedVersion\":\"0\",\"manifestIds\":[]}", "{\"expectedVersion\":0,\"manifestIds\":[1,2]}", "{\"expectedVersion\":0,\"manifestIds\":[\"same\",\"same\"]}", "{\"expectedVersion\":0,\"manifestIds\":[],\"tenantId\":\"x\"}")) {
            assertCode(400, "RELEASE_BUNDLE_INPUT_INVALID", send(write(path(campaign), body, "invalid-request-key")));
        }
        assertCode(400, "RELEASE_BUNDLE_REQUEST_TOO_LARGE", send(write(path(campaign), " ".repeat(16 * 1024 + 1), "oversized-request-key")));
        assertEquals(403, send(write(path(campaign), " ".repeat(16 * 1024 + 1), "oversized-request-key").setHeader("X-Dev-Permissions", "release:read")).statusCode());
        assertEquals(0, count("mk_release_bundle"));
    }

    @Test void auditFailureRollsBackBundleMembersAndCommandReceipt() throws Exception {
        String campaign = campaign(); var members = members(campaign);
        String trigger = "bundle_audit_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.execute("CREATE TRIGGER " + trigger + " BEFORE INSERT ON mk_audit FOR EACH ROW BEGIN IF NEW.tenant_id = '" + tenant + "' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='test audit failure'; END IF; END");
        try {
            assertEquals(500, save(campaign, 0, members, "audit-rollback-key").statusCode());
            assertEquals(0, count("mk_release_bundle")); assertEquals(0, memberCount());
            assertEquals(0, count("mk_control_command"));
            assertEquals(0L, jdbc.queryForObject("select release_bundle_version from mk_campaign where tenant_id=? and campaign_id=?", Long.class, tenant, campaign));
        } finally { jdbc.execute("DROP TRIGGER " + trigger); }
        assertEquals(201, save(campaign, 0, members, "audit-rollback-key").statusCode());
    }

    @Test void standaloneActivationAndRollbackRejectMembersBeforeOldReceiptReplay() throws Exception {
        String campaign = campaign(); var members = members(campaign);
        assertEquals(201, save(campaign, 0, members, "protect-members-key").statusCode());
        for (String member : members) {
            assertCode(409, "RELEASE_BUNDLE_REQUIRED", send(activation(member, "standalone-activate-key")));
            assertCode(409, "RELEASE_BUNDLE_REQUIRED", send(write("/api/v1/releases/" + member + ":rollback", "{\"targetGeneration\":1}", "standalone-rollback-key")));
        }
        var id = members.getFirst();
        repository.tryBeginCommand(new ControlRepository.CommandWrite(tenant, "release.activate", "historic-receipt-key",
                com.acme.marketing.platform.crypto.Digests.sha256Hex(mapper.writeValueAsString(id)), "PROCESSING", null,
                now.toString(), now.plusSeconds(3600).toString()));
        repository.completeCommand(tenant, "release.activate", "historic-receipt-key", "{}");
        assertCode(409, "RELEASE_BUNDLE_REQUIRED", send(activation(id, "historic-receipt-key")));
        assertEquals(0, count("mk_outbox"));
    }

    @Test void concurrentRegistrationAndActivationNeverBothSucceed() throws Exception {
        String campaign = campaign(); var members = members(campaign); var id = members.getFirst();
        var manifest = mapper.readValue(repository.findManifest(tenant, id, false).orElseThrow().manifestJson(), ReleaseManifest.class);
        repository.saveRuntimeAck(new ControlRepository.RuntimeAckWrite(tenant, id, "runtime-test", "READY",
                "runtime-digest", "[]", "[]", 1, now.toString(), "test-key", "test-signature"));
        repository.tryCreateReleaseSlot(new ControlRepository.ReleaseSlotWrite(tenant, "test", "cell-a", "journey",
                manifest.namespace(), 0, 0, 1, 0, now.toString()));
        try (var pool = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            var preparing = pool.submit(() -> { start.await(); return save(campaign, 0, members, "compete-prepare-key"); });
            var activating = pool.submit(() -> { start.await(); return send(activation(id, "compete-activate-key")); });
            start.countDown(); var a = preparing.get(); var b = activating.get();
            if (a.statusCode() == 201) {
                assertCode(409, "RELEASE_BUNDLE_REQUIRED", b); assertEquals(0, count("mk_outbox"));
            } else {
                assertCode(409, "RELEASE_BUNDLE_MEMBER_UNAVAILABLE", a);
                assertEquals(200, b.statusCode(), b.body()); assertEquals(1, count("mk_outbox"));
            }
        }
    }

    private String campaign() {
        String id = UUID.randomUUID().toString();
        repository.saveCampaign(new ControlRepository.CampaignWrite(tenant, id, "组合测试", "隔离验收", "DRAFT",
                "org-a", "shop-a", now.toString(), now.toString(), "STANDARD"));
        return id;
    }
    private List<String> members(String campaign) {
        return List.of(member(campaign, "a-" + UUID.randomUUID(), "test", now.plusSeconds(3600)),
                member(campaign, "b-" + UUID.randomUUID(), "test", now.plusSeconds(3600)));
    }
    private String member(String campaign, String namespace, String environment, Instant expiry) {
        String id = UUID.randomUUID().toString(), definition = "def-" + id, approval = "case-" + id;
        long generation = jdbc.queryForObject("select coalesce(max(generation_no),0)+1 from mk_release_manifest where tenant_id=? and environment_name=? and cell_id=? and runtime_name=? and namespace_name=?", Long.class, tenant, environment, "cell-a", "journey", namespace);
        String digest = "sha256:" + "b".repeat(64);
        repository.saveDefinition(new ControlRepository.DefinitionWrite(tenant, definition, campaign, 1, "JOURNEY_DAG",
                "{}", digest, "APPROVED", "author", now.toString(), now.toString()));
        repository.saveApprovalCase(new ControlRepository.ApprovalCaseWrite(tenant, approval, definition, 1,
                "author", "LEGAL", "APPROVED", now.toString(), now.toString()));
        var artifact = new ArtifactReference("artifact-" + id, "JOURNEY_PLAN", "memory:test", "sha256:" + "a".repeat(64),
                digest, "key", "fixture", "marketing-journey-plan/1", definition, 1);
        var unsigned = new ReleaseManifest(id, new TenantId(tenant), environment, "cell-a", "journey", namespace,
                generation, 0, List.of(), List.of(), List.of(artifact), Map.of(), 0, now.minusSeconds(120), expiry,
                "author", List.of(approval), now.minusSeconds(120), "");
        var signed = new ReleaseManifestSigner().sign(unsigned, keys.activePrivateKey());
        repository.saveManifest(new ControlRepository.ManifestWrite(tenant, id, environment, "cell-a", "journey",
                namespace, generation, "STAGED", mapper.writeValueAsString(signed), now.toString()));
        return id;
    }
    private HttpResponse<String> save(String campaign, long version, List<String> members, String key) throws Exception {
        return send(write(path(campaign), mapper.writeValueAsString(Map.of("expectedVersion", version, "manifestIds", members)), key));
    }
    private String path(String campaign) { return "/api/v1/campaigns/" + campaign + "/release-bundles"; }
    private HttpRequest.Builder activation(String id, String key) {
        return request("/api/v1/releases/" + id + ":activate").header("Idempotency-Key", key).POST(HttpRequest.BodyPublishers.noBody());
    }
    private HttpRequest.Builder write(String path, String body, String key) {
        return request(path).header("Content-Type", "application/json").header("Idempotency-Key", key).POST(HttpRequest.BodyPublishers.ofString(body));
    }
    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(20))
                .header("X-Dev-Tenant-Id", tenant).header("X-Dev-Actor-Id", "bundle-author")
                .header("X-Dev-Organization-Ids", "org-a").header("X-Dev-Shop-Ids", "shop-a").header("X-Dev-Permissions", "*");
    }
    private HttpResponse<String> send(HttpRequest.Builder request) throws Exception { return http.send(request.build(), HttpResponse.BodyHandlers.ofString()); }
    private int memberCount() { return jdbc.queryForObject("select count(*) from mk_release_manifest where tenant_id=? and bundle_id is not null", Integer.class, tenant); }
    private int count(String table) { return jdbc.queryForObject("select count(*) from " + table + " where tenant_id=?", Integer.class, tenant); }
    private void assertCode(int status, String code, HttpResponse<String> response) throws Exception {
        assertEquals(status, response.statusCode(), response.body());
        assertEquals(code, mapper.readTree(response.body()).path("code").asString(), response.body());
    }
}
