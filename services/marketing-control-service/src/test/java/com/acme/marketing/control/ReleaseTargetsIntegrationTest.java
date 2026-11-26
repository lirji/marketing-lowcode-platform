package com.acme.marketing.control;

import static org.junit.jupiter.api.Assertions.*;

import com.acme.marketing.contracts.release.ArtifactReference;
import com.acme.marketing.contracts.release.ReleaseManifest;
import com.acme.marketing.control.application.ControlRepository;
import com.acme.marketing.platform.identity.TenantId;
import com.acme.marketing.testsupport.MySqlIntegrationTest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 只在隔离数据库设置历史发布状态，验证候选与真实回滚事务；不运行外部发布Relay。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ReleaseTargetsIntegrationTest extends MySqlIntegrationTest {
    @LocalServerPort int port;
    @Autowired ControlRepository repository;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    private final HttpClient http = HttpClient.newHttpClient();
    private final String tenant = "release-" + UUID.randomUUID();
    private final Instant now = Instant.now();

    @Test void returnsOnlyRetainedActiveUnexpiredAndAccessibleTargets() throws Exception {
        String source = seed();
        var response = send(request(source + "/rollback-candidates").GET());
        assertEquals(200, response.statusCode(), response.body());
        JsonNode targets = mapper.readTree(response.body());
        assertEquals(1, targets.size()); assertEquals(90, targets.get(0).path("generation").asLong());
        assertEquals("def-90", targets.get(0).path("artifacts").get(0).path("definitionId").asString());
        assertEquals(0, outboxCount());
        assertEquals(0, jdbc.queryForObject("select count(*) from mk_control_command where tenant_id=?", Integer.class, tenant));
        assertEquals(403, send(request(source + "/rollback-candidates").setHeader("X-Dev-Permissions", "release:rollback").GET()).statusCode());
        assertEquals(400, send(request(source + "/rollback-candidates").setHeader("X-Dev-Organization-Ids", "org-b").GET()).statusCode());
        assertEquals(404, send(request(source + "/rollback-candidates").setHeader("X-Dev-Tenant-Id", "other").GET()).statusCode());
    }

    @Test void rollbackUsesSelectedNonAdjacentGenerationAndReplaysOnce() throws Exception {
        String source = seed();
        var first = send(rollback(source, 90, "rollback-exact-key"));
        assertEquals(200, first.statusCode(), first.body());
        assertEquals(90, mapper.readTree(first.body()).path("manifest").path("generation").asLong());
        assertEquals(90, jdbc.queryForObject("select desired_generation from mk_release_slot where tenant_id=?", Long.class, tenant));
        assertEquals(1, outboxCount());
        var replay = send(rollback(source, 90, "rollback-exact-key"));
        assertEquals(200, replay.statusCode(), replay.body());
        assertEquals(mapper.readTree(first.body()), mapper.readTree(replay.body()));
        assertEquals(1, outboxCount());
        assertEquals(403, send(rollback(source, 90, "rollback-exact-key").setHeader("X-Dev-Permissions", "release:read")).statusCode());
        assertEquals(400, send(rollback(source, 90, "rollback-exact-key").setHeader("X-Dev-Shop-Ids", "shop-other")).statusCode());
        // 回执中的目标制品也受当前权限限制，目标所属组织后续变化不能靠旧回执泄漏。
        jdbc.update("update mk_campaign set organization_id='org-b' where tenant_id=? and campaign_id='cmp-90'", tenant);
        assertEquals(400, send(rollback(source, 90, "rollback-exact-key")).statusCode());
    }

    @Test void finalRollbackRechecksExpiredForeignAndStaleTargetsWithoutOutbox() throws Exception {
        String source = seed();
        assertCode(409, "ROLLBACK_TARGET_UNAVAILABLE", send(rollback(source, 50, "expired-target-key")));
        assertCode(409, "ROLLBACK_TARGET_UNAVAILABLE", send(rollback(source, 60, "staged-target-key")));
        assertEquals(400, send(rollback(source, 70, "foreign-target-key")).statusCode());
        assertCode(409, "GENERATION_NOT_RETAINED", send(rollback(source, 80, "not-retained-key")));
        // 用户获取候选后另一个发布已经改变指针，提交仍以数据库当前状态为准。
        assertEquals(200, send(request(source + "/rollback-candidates").GET()).statusCode());
        jdbc.update("update mk_release_slot set desired_generation=80 where tenant_id=?", tenant);
        assertCode(409, "ROLLBACK_SOURCE_STALE", send(rollback(source, 90, "changed-slot-key")));
        assertCode(409, "ROLLBACK_SOURCE_STALE", send(request(source + "/rollback-candidates").GET()));
        assertEquals(0, outboxCount());
    }

    @Test void activationReplayCannotBypassRevokedPermissions() throws Exception {
        String source = seed();
        // 植入以前已经完成的回执，证明控制器在访问回执之前做当下授权；不冒充真实激活验证。
        String response = mapper.writeValueAsString(Map.of("manifest", manifest(100, List.of(90L), now.plusSeconds(3600)), "state", "ACTIVE", "readyReplicas", 1, "readyCapacity", 1));
        String hash = com.acme.marketing.platform.crypto.Digests.sha256Hex(mapper.writeValueAsString(source));
        repository.tryBeginCommand(new ControlRepository.CommandWrite(tenant, "release.activate", "old-activate-key", hash, "PROCESSING", null, now.toString(), now.plusSeconds(3600).toString()));
        repository.completeCommand(tenant, "release.activate", "old-activate-key", response);
        var replay = request(source + ":activate").header("Idempotency-Key", "old-activate-key").POST(HttpRequest.BodyPublishers.noBody());
        assertEquals(200, send(replay).statusCode());
        assertEquals(403, send(replay.setHeader("X-Dev-Permissions", "release:read")).statusCode());
        assertEquals(0, outboxCount());
    }

    private String seed() {
        for (long generation : List.of(100L, 90L, 80L, 70L, 60L, 50L)) {
            repository.saveCampaign(new ControlRepository.CampaignWrite(tenant, "cmp-" + generation, "test", "release scope", "DRAFT", generation == 70 ? "org-b" : "org-a", "shop-a", now.toString(), now.toString(), "STANDARD"));
            repository.saveDefinition(new ControlRepository.DefinitionWrite(tenant, "def-" + generation, "cmp-" + generation, 1, "JOURNEY_DAG", "{}", "hash-" + generation, "APPROVED", "author", now.toString(), now.toString()));
            var item = manifest(generation, generation == 100 ? List.of(90L, 70L, 60L, 50L, 20L) : List.of(), generation == 50 ? now.minusSeconds(1) : now.plusSeconds(3600));
            repository.saveManifest(new ControlRepository.ManifestWrite(tenant, item.manifestId(), "test", "cell-a", "journey", "main", generation, generation == 60 ? "STAGED" : "ACTIVE", mapper.writeValueAsString(item), now.toString(), null));
        }
        repository.tryCreateReleaseSlot(new ControlRepository.ReleaseSlotWrite(tenant, "test", "cell-a", "journey", "main", 100, 100, 100, 1, now.toString()));
        return "manifest-100";
    }

    @Test void killSwitchReplayRequiresCurrentPermission() throws Exception {
        var request = request("kill-switches/main").header("Content-Type", "application/json").header("Idempotency-Key", "kill-switch-replay-key")
                .PUT(HttpRequest.BodyPublishers.ofString("{\"enabled\":true,\"reason\":\"isolated test\"}"));
        var first = send(request); assertEquals(200, first.statusCode(), first.body());
        assertEquals(200, send(request).statusCode());
        assertEquals(1, outboxCount());
        assertEquals(403, send(request.setHeader("X-Dev-Permissions", "release:read")).statusCode());
        assertEquals(1, outboxCount());
    }
    private ReleaseManifest manifest(long generation, List<Long> retained, Instant expiry) {
        var artifact = new ArtifactReference("artifact-" + generation, "JOURNEY_PLAN", "memory:test", "sha256:" + "a".repeat(64), "sha256:" + "b".repeat(64), "key", "signature", "marketing-journey-plan/1", "def-" + generation, 1);
        return new ReleaseManifest("manifest-" + generation, new TenantId(tenant), "test", "cell-a", "journey", "main", generation, generation, List.of(), retained, List.of(artifact), Map.of(), 0, now.minusSeconds(120), expiry, "author", List.of(), now.minusSeconds(120), "fixture-signature");
    }
    private HttpRequest.Builder rollback(String source, long target, String key) {
        return request(source + ":rollback").header("Content-Type", "application/json").header("Idempotency-Key", key).POST(HttpRequest.BodyPublishers.ofString("{\"targetGeneration\":" + target + "}"));
    }
    private HttpRequest.Builder request(String suffix) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/releases/" + suffix)).timeout(Duration.ofSeconds(10))
                .header("X-Dev-Tenant-Id", tenant).header("X-Dev-Actor-Id", "operator").header("X-Dev-Organization-Ids", "org-a")
                .header("X-Dev-Shop-Ids", "shop-a").header("X-Dev-Permissions", "*");
    }
    private HttpResponse<String> send(HttpRequest.Builder request) throws Exception { return http.send(request.build(), HttpResponse.BodyHandlers.ofString()); }
    private int outboxCount() { return jdbc.queryForObject("select count(*) from mk_outbox where tenant_id=?", Integer.class, tenant); }
    private void assertCode(int status, String code, HttpResponse<String> result) throws Exception {
        assertEquals(status, result.statusCode(), result.body()); assertEquals(code, mapper.readTree(result.body()).path("code").asString(), result.body());
    }
}
