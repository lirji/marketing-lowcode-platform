package com.acme.marketing.decision;

import static org.junit.jupiter.api.Assertions.*;

import com.acme.marketing.contracts.release.DecisionDeploymentInventory;
import com.acme.marketing.contracts.release.DecisionDeploymentInventory.Replica;
import com.acme.marketing.decision.deployment.DecisionInventoryJson;
import com.acme.marketing.platform.crypto.Ed25519;
import com.acme.marketing.testsupport.MySqlIntegrationTest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyPair;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.ObjectMapper;

/** 用独占 slot 验证跨租户部署身份控制、并发仲裁与不可变历史。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DecisionInventoryIntegrationTest extends MySqlIntegrationTest {
    private static final KeyPair KEYS = Ed25519.generateKeyPair();
    private static final String TENANT = "deployment", ACTOR = "pipeline", PERMISSION = "deployment:publish";
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    private final HttpClient client = HttpClient.newHttpClient();

    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        p.add("marketing.deployment-inventory.trusted-public-keys", () ->
                "deploy=" + Base64.getEncoder().encodeToString(KEYS.getPublic().getEncoded()));
        p.add("marketing.deployment-inventory.tenant", () -> TENANT);
        p.add("marketing.deployment-inventory.actor", () -> ACTOR);
        p.add("marketing.runtime.namespace", () -> "inventory-test");
    }

    @Test void wrongMachineInvalidSignatureAndMalformedWireCannotWrite() throws Exception {
        var valid = inventory(1, "a", KEYS);
        assertEquals(403, send(request("another", ACTOR, "*").GET()).statusCode());
        assertEquals(403, send(request(TENANT, "another", "*").PUT(body(valid))).statusCode());
        assertEquals(403, send(request(TENANT, ACTOR, "campaign:read").PUT(body(valid))).statusCode());
        assertEquals(409, put(inventory(1, "a", Ed25519.generateKeyPair())).statusCode());
        assertEquals(400, send(request(TENANT, ACTOR, PERMISSION).header("X-Inventory-Digest", "wrong").PUT(body(valid))).statusCode());
        String json = DecisionInventoryJson.write(valid);
        for (String bad : List.of("", "null", "[]", json + "{}", json.replaceFirst("\\{", "{\"sequence\":1,"),
                json.replace("\"sequence\":1", "\"sequence\":1.5"), json.replace("\"sequence\":1", "\"sequence\":\"1\""),
                json.replaceFirst("\\{", "{\"unknown\":1,"), " ".repeat(65 * 1024))) {
            assertEquals(400, send(request(TENANT, ACTOR, PERMISSION).PUT(HttpRequest.BodyPublishers.ofString(bad))).statusCode());
        }
    }

    @Test void competingSignedSnapshotsAdvanceOnceAndDrainRequiresANewVersion() throws Exception {
        var first = inventory(1, "a", KEYS);
        var other = inventory(1, "b", KEYS);
        assertEquals(0, position().path("sequence").asLong());
        assertEquals(409, put(inventory(2, "gap", KEYS)).statusCode());
        List<HttpResponse<String>> results;
        try (var pool = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            Callable<HttpResponse<String>> left = () -> { start.await(); return put(first); };
            var a = pool.submit(left);
            var b = pool.submit(() -> { start.await(); return put(other); });
            start.countDown(); results = List.of(a.get(), b.get());
        }
        assertEquals(List.of(200, 409), results.stream().map(HttpResponse::statusCode).sorted().toList(), results.toString());
        var winner = results.getFirst().statusCode() == 200 ? first : other;
        assertEquals(200, put(winner).statusCode());
        assertEquals(winner.contentDigest(), position().path("digest").asString());
        var drained = inventory(2, "", KEYS);
        // 在已追加历史后让真实 head 更新失败，验证两张表随同一事务回滚。
        jdbc.execute("ALTER TABLE mk_decision_deployment_head ADD CONSTRAINT inventory_test_fault CHECK (deployment_sequence <> 2)");
        try {
            assertEquals(500, put(drained).statusCode());
            assertEquals(winner.contentDigest(), position().path("digest").asString());
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM mk_decision_deployment_inventory WHERE namespace_name=?",
                    Integer.class, "inventory-test"));
        } finally {
            jdbc.execute("ALTER TABLE mk_decision_deployment_head DROP CHECK inventory_test_fault");
        }
        assertEquals(200, put(drained).statusCode());
        assertEquals(409, put(winner).statusCode());
        assertEquals(drained.contentDigest(), position().path("digest").asString());
        var history = jdbc.queryForList("SELECT inventory_json FROM mk_decision_deployment_inventory WHERE namespace_name=? ORDER BY deployment_sequence",
                String.class, "inventory-test");
        assertEquals(List.of(winner, drained), history.stream().map(json -> mapper.readValue(json, DecisionDeploymentInventory.class)).toList());
    }

    private tools.jackson.databind.JsonNode position() throws Exception {
        var response = send(request(TENANT, ACTOR, PERMISSION).GET());
        assertEquals(200, response.statusCode(), response.body());
        return mapper.readTree(response.body());
    }
    private DecisionDeploymentInventory inventory(long sequence, String instance, KeyPair key) {
        Instant now = Instant.now().minusSeconds(1);
        var replicas = instance.isEmpty() ? List.<Replica>of() : List.of(new Replica(instance, "sha256:" + "a".repeat(64), "exec"));
        return new DecisionDeploymentInventory("local", "cell-a", "inventory-test", sequence, now, now.plusSeconds(180), replicas, "deploy", "")
                .sign(key.getPrivate());
    }
    private HttpResponse<String> put(DecisionDeploymentInventory inventory) throws Exception {
        return send(request(TENANT, ACTOR, PERMISSION).PUT(body(inventory)));
    }
    private static HttpRequest.BodyPublisher body(DecisionDeploymentInventory inventory) {
        return HttpRequest.BodyPublishers.ofString(DecisionInventoryJson.write(inventory));
    }
    private HttpResponse<String> send(HttpRequest.Builder builder) throws Exception {
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
    private HttpRequest.Builder request(String tenant, String actor, String permission) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/decisions/runtime/deployment-inventory"))
                .timeout(Duration.ofSeconds(15)).header("Content-Type", "application/json")
                .header("X-Dev-Tenant-Id", tenant).header("X-Dev-Actor-Id", actor).header("X-Dev-Permissions", permission)
                .header("X-Dev-Organization-Ids", "*").header("X-Dev-Shop-Ids", "*");
    }
}
