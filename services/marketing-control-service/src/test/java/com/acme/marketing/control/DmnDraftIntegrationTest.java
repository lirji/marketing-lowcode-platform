package com.acme.marketing.control;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterAll;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 通过真实 HTTP 与 MySQL 验证草稿版本、幂等、隔离及审计的原子性。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DmnDraftIntegrationTest {
    // 故障触发器只存在于本套独占实例，不放宽共享基础设施的数据库权限。
    private static final MySQLContainer MYSQL = mysql();
    private static MySQLContainer mysql() {
        var instance = new MySQLContainer("mysql:8.4.11").withCommand("--log-bin-trust-function-creators=1",
                        "--performance-schema=OFF", "--innodb-buffer-pool-size=64M", "--max-connections=30")
                .withDatabaseName("dmn_test").withUsername("test").withPassword("test")
                .withTmpFs(Map.of("/var/lib/mysql", "rw"))
                .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withMemory(512L * 1024 * 1024).withMemorySwap(512L * 1024 * 1024))
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
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    // 每个用例使用独立租户，不干扰既有控制面集成测试的审计链和统计。
    private final String tenant = "dmn-" + UUID.randomUUID();

    @Test void savesIncompleteDraftAndRestoresImmutableHistoryWithoutEmptyVersions() throws Exception {
        String path = campaign();
        assertCode(404, "DMN_DRAFT_NOT_FOUND", send(request(path + "/latest").GET()));
        var first = save(path, 0, "", "draft-first-key");
        assertEquals(200, first.statusCode(), first.body());
        var stored = mapper.readTree(first.body());
        assertEquals(1, stored.path("version").asLong());
        assertEquals("", stored.path("table").path("rules").get(0).path("outputs").get(0).asString());
        assertEquals("dmn-author", stored.path("createdBy").asString());
        assertEquals(64, stored.path("contentHash").asString().length());
        var noOp = save(path, 1, "", "draft-identical-key");
        assertEquals(stored, mapper.readTree(noOp.body()));
        var second = save(path, 1, "\"VIP\"", "draft-second-key");
        assertEquals(200, second.statusCode(), second.body());
        assertEquals(2, mapper.readTree(second.body()).path("version").asLong());
        assertEquals(stored, mapper.readTree(send(request(path + "/versions/1").GET()).body()));
        assertEquals(mapper.readTree(second.body()), mapper.readTree(send(request(path + "/latest").GET()).body()));
        assertCode(409, "DMN_DRAFT_VERSION_CONFLICT", save(path, 1, "\"VIP\"", "draft-stale-key"));
        assertEquals(2, count("mk_dmn_draft_version"));
        assertEquals(2, jdbc.queryForObject("select count(*) from mk_audit where tenant_id=? and action_name='DMN_DRAFT_SAVED'", Integer.class, tenant));
    }

    @Test void serializesFirstWritesAndSameBaseEdits() throws Exception {
        String path = campaign();
        for (long expected : List.of(0L, 1L)) {
            try (var pool = Executors.newFixedThreadPool(2)) {
                var start = new CountDownLatch(1);
                var a = pool.submit(() -> { start.await(); return save(path, expected, "\"A" + expected + "\"", "race-a-" + expected); });
                var b = pool.submit(() -> { start.await(); return save(path, expected, "\"B" + expected + "\"", "race-b-" + expected); });
                start.countDown();
                var left = a.get(); var right = b.get();
                assertEquals(List.of(200, 409), List.of(left.statusCode(), right.statusCode()).stream().sorted().toList(), left.body() + right.body());
                assertCode(409, "DMN_DRAFT_VERSION_CONFLICT", left.statusCode() == 409 ? left : right);
            }
        }
        assertEquals(2, count("mk_dmn_draft_version"));
    }

    @Test void concurrentSameKeyReplaysOnceAndDifferentPayloadConflicts() throws Exception {
        String path = campaign();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            var a = pool.submit(() -> { start.await(); return save(path, 0, "", "same-command-key"); });
            var b = pool.submit(() -> { start.await(); return save(path, 0, "", "same-command-key"); });
            start.countDown();
            var left = a.get(); var right = b.get();
            assertEquals(200, left.statusCode(), left.body()); assertEquals(200, right.statusCode(), right.body());
            assertEquals(mapper.readTree(left.body()), mapper.readTree(right.body()));
        }
        assertEquals(1, count("mk_dmn_draft_version"));
        assertCode(409, "IDEMPOTENCY_PAYLOAD_CONFLICT", save(path, 0, "true", "same-command-key"));
    }

    @Test void checksCurrentPermissionsAndDataScopeBeforeReplayAndHistoryReads() throws Exception {
        String path = campaign();
        assertEquals(200, save(path, 0, "", "permission-replay-key").statusCode());
        var body = mapper.writeValueAsString(payload(0, ""));
        for (String suffix : List.of("/latest", "/versions/1")) {
            assertEquals(403, send(request(path + suffix).setHeader("X-Dev-Permissions", "campaign:read").GET()).statusCode());
            // 沿用平台 TenantScope 的既有400拒绝合同，不在本片重定义全局错误语义。
            assertEquals(400, send(request(path + suffix).setHeader("X-Dev-Organization-Ids", "org-other").GET()).statusCode());
            assertEquals(400, send(request(path + suffix).setHeader("X-Dev-Shop-Ids", "shop-other").GET()).statusCode());
            assertEquals(404, send(request(path + suffix).setHeader("X-Dev-Tenant-Id", "foreign-tenant").GET()).statusCode());
        }
        for (var override : List.of(Map.entry("X-Dev-Permissions", "definition:read"), Map.entry("X-Dev-Organization-Ids", "org-other"), Map.entry("X-Dev-Shop-Ids", "shop-other"))) {
            assertEquals(override.getKey().equals("X-Dev-Permissions") ? 403 : 400, send(write(path, "permission-replay-key", body).setHeader(override.getKey(), override.getValue())).statusCode());
        }
        assertEquals(1, count("mk_dmn_draft_version"));
    }

    @Test void auditFailureRollsBackDraftAndIdempotencyReceipt() throws Exception {
        String path = campaign();
        // 仅此 Testcontainers 租户触发故障；真实 SQL 回滚证明三项写入处于同一事务。
        String trigger = "dmn_audit_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.execute("create trigger " + trigger + " before insert on mk_audit for each row begin if NEW.tenant_id='" + tenant + "' and NEW.action_name='DMN_DRAFT_SAVED' then signal sqlstate '45000' set message_text='injected audit failure'; end if; end");
        try {
            assertEquals(500, save(path, 0, "", "rollback-command-key").statusCode());
            assertEquals(0, count("mk_dmn_draft_version"));
            assertEquals(0, jdbc.queryForObject("select count(*) from mk_control_command where tenant_id=? and operation_name='dmn.draft.save'", Integer.class, tenant));
        } finally { jdbc.execute("drop trigger " + trigger); }
        assertEquals(200, save(path, 0, "", "rollback-command-key").statusCode());
        assertEquals(1, count("mk_dmn_draft_version"));
    }

    @Test void rejectsMalformedUnboundedAndDuplicateRequestsBeforeWriting() throws Exception {
        String path = campaign();
        String valid = mapper.writeValueAsString(payload(0, ""));
        for (String body : List.of(valid + "{}", valid.replace("\"expectedVersion\":0", "\"expectedVersion\":0,\"expectedVersion\":1"), valid.replace("\"expectedVersion\":0", "\"expectedVersion\":0.5"), valid.replace("\"hitPolicy\":\"UNIQUE\"", "\"hitPolicy\":\"COLLECT\""))) {
            assertEquals(400, send(write(path, "invalid-command-key", body)).statusCode(), body);
        }
        String huge = " ".repeat(512 * 1024 + 1);
        assertCode(400, "DMN_DRAFT_REQUEST_TOO_LARGE", send(write(path, "large-command-key", huge)));
        var chunked = request(path).header("Content-Type", "application/json").header("Idempotency-Key", "chunked-command-key")
                .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new java.io.ByteArrayInputStream(huge.getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        assertCode(400, "DMN_DRAFT_REQUEST_TOO_LARGE", send(chunked));
        assertEquals(403, send(write(path, "denied-command-key", huge).setHeader("X-Dev-Permissions", "definition:read")).statusCode());
        assertEquals(0, count("mk_dmn_draft_version"));
    }

    private String campaign() throws Exception {
        var result = send(write("/api/v1/campaigns", "campaign-" + UUID.randomUUID(), mapper.writeValueAsString(Map.of("name", "DMN test", "objective", "draft", "organizationId", "org-a", "shopId", "shop-a"))));
        assertEquals(201, result.statusCode(), result.body());
        return "/api/v1/campaigns/" + mapper.readTree(result.body()).path("id").asString() + "/dmn-drafts";
    }
    private Map<String, Object> payload(long version, String output) {
        return Map.of("expectedVersion", version, "table", Map.of("hitPolicy", "UNIQUE", "inputs", List.of(Map.of("name", "", "type", "number")), "outputs", List.of(Map.of("name", "level", "type", "string")), "rules", List.of(Map.of("inputs", List.of(""), "outputs", List.of(output)))));
    }
    private HttpResponse<String> save(String path, long version, String output, String key) throws Exception {
        return send(write(path, key, mapper.writeValueAsString(payload(version, output))));
    }
    private HttpRequest.Builder write(String path, String key, String body) {
        return request(path).header("Content-Type", "application/json").header("Idempotency-Key", key).POST(HttpRequest.BodyPublishers.ofString(body));
    }
    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(20))
                .header("X-Dev-Tenant-Id", tenant).header("X-Dev-Actor-Id", "dmn-author")
                .header("X-Dev-Organization-Ids", "org-a").header("X-Dev-Shop-Ids", "shop-a").header("X-Dev-Permissions", "*");
    }
    private HttpResponse<String> send(HttpRequest.Builder request) throws Exception { return http.send(request.build(), HttpResponse.BodyHandlers.ofString()); }
    private int count(String table) { return jdbc.queryForObject("select count(*) from " + table + " where tenant_id=?", Integer.class, tenant); }
    private void assertCode(int status, String code, HttpResponse<String> result) throws Exception {
        assertEquals(status, result.statusCode(), result.body());
        JsonNode problem = mapper.readTree(result.body()); assertEquals(code, problem.path("code").asString(), result.body());
    }
}
