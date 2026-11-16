package com.acme.marketing.benefit;

import static org.junit.jupiter.api.Assertions.*;
import static com.acme.marketing.platform.time.SqlTime.format;

import com.acme.marketing.benefit.application.AwardFulfillmentRepository;
import com.acme.marketing.benefit.application.AwardFulfillmentRepository.Completion;
import com.acme.marketing.benefit.application.AwardFulfillmentRepository.Lease;
import com.acme.marketing.benefit.application.AwardFulfillmentService;
import com.acme.marketing.testsupport.MySqlIntegrationTest;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 真实 MySQL 仲裁租约，真实 HTTP 适配上游合同；不连接共享权益服务。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "marketing.award.fulfillment-enabled=true", "marketing.award.fulfillment-max-failures=2",
    "marketing.award.fulfillment-initial-delay-ms=3600000", "marketing.benefit-center.request-timeout-ms=500"
})
class AwardFulfillmentIntegrationTest extends MySqlIntegrationTest {
    private static final Map<String, Reply> REPLIES = new ConcurrentHashMap<>();
    private static final AtomicReference<String> TENANT = new AtomicReference<>();
    private static final AtomicReference<String> AUTH = new AtomicReference<>();
    private static final java.util.concurrent.ExecutorService HTTP_POOL = Executors.newFixedThreadPool(4);
    private static final HttpServer SERVER = server();
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired AwardFulfillmentRepository repository;
    @Autowired AwardFulfillmentService service;
    @Autowired ObjectMapper mapper;
    @Autowired PlatformTransactionManager tx;
    private final HttpClient http = HttpClient.newHttpClient();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        r.add("marketing.benefit-center.base-url", () -> "http://127.0.0.1:" + SERVER.getAddress().getPort());
        r.add("marketing.benefit-center.fulfillment-bearer-token", () -> "test-query-token");
    }
    @AfterAll static void stop() { SERVER.stop(0); HTTP_POOL.shutdownNow(); }
    @BeforeEach void isolate() {
        // 仅隔离 Testcontainers 库中的其他测试任务，避免旧 fixture 争用当前批次。
        jdbc.update("UPDATE mk_award_fulfillment SET sync_state='PAUSED'");
    }

    @Test void observesRealResponseAndRetainsLastSuccessWhenLookupFails() throws Exception {
        String id = seed("tenant-observe", "CENTER");
        REPLIES.put(id, new Reply(200, order(id, "SUCCEEDED", "recipient-1", 3), 0));
        assertEquals(1, service.runBatch(), () -> repository.find("tenant-observe", id).toString());
        JsonNode first = get(id, "tenant-observe", "trace:read", 200);
        assertEquals("SUCCEEDED", first.path("centerStatus").asString());
        assertEquals("sku-1", first.path("items").get(0).path("skuId").asString());
        assertEquals("tenant-observe", TENANT.get());
        assertEquals("Bearer test-query-token", AUTH.get());
        assertFalse(first.toString().contains("recipient-1"));
        assertEquals("PENDING", jdbc.queryForObject("SELECT status_name FROM mk_award_intent_outbox WHERE intent_id=?", String.class, id));
        REPLIES.put(id, new Reply(404, "{}", 0));
        due(id); assertEquals(0, service.runBatch());
        JsonNode failed = get(id, "tenant-observe", "trace:read", 200);
        assertEquals(first.path("checkedAt"), failed.path("checkedAt"));
        assertEquals("SUCCEEDED", failed.path("centerStatus").asString());
        assertEquals("AWARD_CENTER_ORDER_NOT_FOUND", failed.path("errorCode").asString());
        due(id); service.runBatch();
        assertEquals("PAUSED", repository.find("tenant-observe", id).orElseThrow().state());
        assertEquals(202, refresh(id, "tenant-observe", "trace:read").statusCode());
        REPLIES.put(id, new Reply(200, order(id, "REVERSED", "recipient-1", 3), 0));
        assertEquals(1, service.runBatch());
        assertEquals("REVERSED", get(id, "tenant-observe", "trace:read", 200).path("centerStatus").asString());
    }

    @Test void rejectsWrongRecipientVersionAndUnknownStatusWithoutInventingOutcome() throws Exception {
        String id = seed("tenant-invalid", "CENTER");
        for (String body : List.of(order(id, "SUCCEEDED", "other-recipient", 3),
                order(id, "SUCCEEDED", "recipient-1", 4), order(id, "NEW_STATE", "recipient-1", 3))) {
            REPLIES.put(id, new Reply(200, body, 0));
            refresh(id, "tenant-invalid", "trace:read"); assertEquals(0, service.runBatch());
            JsonNode view = get(id, "tenant-invalid", "trace:read", 200);
            assertTrue(view.path("checkedAt").isNull()); assertTrue(view.path("centerStatus").isNull());
            assertFalse(view.path("errorCode").asString().isBlank());
        }
    }

    @Test void enforcesTenantPermissionAndModeOnReadAndRefresh() throws Exception {
        String id = seed("tenant-owner", "CENTER");
        get(id, "tenant-other", "trace:read", 404);
        get(id, "tenant-owner", "campaign:read", 403);
        assertEquals(403, refresh(id, "tenant-owner", "campaign:read").statusCode());
        assertEquals(404, refresh(id, "tenant-other", "trace:read").statusCode());
        String legacy = seed("tenant-owner", "LEGACY");
        get(legacy, "tenant-owner", "trace:read", 409);
    }

    @Test void onlyOneConcurrentOwnerWinsAndExpiredOwnerCannotPublish() throws Exception {
        String id = seed("tenant-lease", "CENTER");
        Instant now = Instant.now().plusSeconds(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> repository.claim(new Lease("tenant-lease", id, 0, "a", format(now), format(now.plusSeconds(30)))));
            var b = pool.submit(() -> repository.claim(new Lease("tenant-lease", id, 0, "b", format(now), format(now.plusSeconds(30)))));
            assertNotEquals(a.get(), b.get());
        }
        String owner = jdbc.queryForObject("SELECT lease_owner FROM mk_award_fulfillment WHERE intent_id=?", String.class, id);
        repository.requestRefresh("tenant-lease", id, format(now.plusSeconds(1)));
        assertEquals("RUNNING", repository.find("tenant-lease", id).orElseThrow().state());
        assertFalse(repository.complete(result(id, owner, 1, now.plusSeconds(30), "FAILED")));
        assertTrue(repository.claim(new Lease("tenant-lease", id, 1, "new", format(now.plusSeconds(31)), format(now.plusSeconds(60)))));
        assertTrue(repository.complete(result(id, "new", 2, now.plusSeconds(32), "SUCCEEDED")));
        assertFalse(repository.complete(result(id, owner, 1, now.plusSeconds(33), "FAILED")));
        assertEquals("SUCCEEDED", repository.find("tenant-lease", id).orElseThrow().centerStatus());
    }

    @Test void busyTenantCannotConsumeWholeScanBatch() {
        for (int i=0;i<5;i++) seed("tenant-hot", "CENTER");
        seed("tenant-cold", "CENTER");
        var due = repository.due(format(Instant.now().plusSeconds(1)), 2, 5);
        assertEquals(2, due.stream().filter(row -> row.tenantId().equals("tenant-hot")).count());
        assertEquals(1, due.stream().filter(row -> row.tenantId().equals("tenant-cold")).count());
    }

    @Test void refreshInvalidatesScannedCandidateAndRestartsFailureBudget() {
        String id = seed("tenant-refresh", "CENTER");
        jdbc.update("UPDATE mk_award_fulfillment SET failure_count=1 WHERE intent_id=?", id);
        var old = repository.due(format(Instant.now()), 2, 5).stream().filter(c -> c.intentId().equals(id)).findFirst().orElseThrow();
        Instant now = Instant.now().plusSeconds(1);
        repository.requestRefresh("tenant-refresh", id, format(now));
        assertFalse(repository.claim(new Lease("tenant-refresh", id, old.leaseVersion(), "old", format(now), format(now.plusSeconds(30)))));
        var fresh = repository.due(format(now), 2, 5).stream().filter(c -> c.intentId().equals(id)).findFirst().orElseThrow();
        assertEquals(0, fresh.failures()); assertTrue(fresh.leaseVersion() > old.leaseVersion());
        assertTrue(repository.claim(new Lease("tenant-refresh", id, fresh.leaseVersion(), "new", format(now), format(now.plusSeconds(30)))));
    }

    @Test void observationInitializationRollsBackWithParentTransaction() {
        String id = UUID.randomUUID().toString();
        assertThrows(IllegalStateException.class, () -> new TransactionTemplate(tx).executeWithoutResult(status -> {
            seedWithId("tenant-rollback", "CENTER", id);
            throw new IllegalStateException("rollback fixture");
        }));
        assertTrue(repository.findIntent("tenant-rollback", id).isEmpty());
        assertTrue(repository.find("tenant-rollback", id).isEmpty());
    }

    @Test void limitsBodyAndSlowLookupWithoutSavingAnOutcome() throws Exception {
        String id = seed("tenant-limits", "CENTER");
        for (Reply reply : List.of(new Reply(200, "x".repeat(150_000), 0),
                new Reply(200, order(id, "SUCCEEDED", "recipient-1", 3), 1000))) {
            REPLIES.put(id, reply); refresh(id, "tenant-limits", "trace:read");
            long start = System.nanoTime(); assertEquals(0, service.runBatch());
            assertTrue(System.nanoTime() - start < 2_000_000_000L);
            assertNull(repository.find("tenant-limits", id).orElseThrow().checkedAt());
        }
    }

    private Completion result(String id, String owner, long version, Instant now, String state) {
        return new Completion("tenant-lease", id, version, owner, "PENDING", "order-1", state, "[]", format(now), 0, "", format(now.plusSeconds(300)), format(now));
    }
    private void due(String id) { jdbc.update("UPDATE mk_award_fulfillment SET next_attempt_at=? WHERE intent_id=?", format(Instant.now().minusSeconds(1)), id); }
    private String seed(String tenant, String mode) { String id = UUID.randomUUID().toString(); seedWithId(tenant, mode, id); return id; }
    private void seedWithId(String tenant, String mode, String id) {
        String now = format(Instant.now().minusSeconds(1));
        String payload = mapper.writeValueAsString(Map.of("schemaVersion", "1.0", "sourceSystem", "drools-activity", "sourceRequestId", id,
                "sourceBusinessNo", "campaign", "recipientRef", "recipient-1", "partialPolicy", "BEST_EFFORT", "trace", Map.of(),
                "items", List.of(Map.of("clientItemId", "item-1", "benefitSkuId", "sku-1", "benefitType", "CASH", "quantity", 1,
                    "amountMinor", 100, "currency", "CNY", "metadata", Map.of(), "expectedSkuVersion", 3))));
        jdbc.update("INSERT INTO mk_award_intent_outbox(tenant_id,intent_id,source_system,source_request_id,campaign_id,definition_version,subject_hash,delivery_mode,request_hash,payload_hash,payload_json,status_name,delivery_result,next_attempt_at,created_at,updated_at) VALUES(?,?,'drools-activity',?,'campaign',1,?,?,?, ?,?,'PENDING','CENTER_ENQUEUED',?,?,?)",
                tenant, id, id, "a".repeat(64), mode, "b".repeat(64), "c".repeat(64), payload, now, now, now);
        if (mode.equals("CENTER")) repository.initialize(tenant, id, now);
    }
    private String order(String id, String state, String recipient, int version) {
        return mapper.writeValueAsString(Map.of("orderNo", "order-"+id, "sourceSystem", "drools-activity", "sourceRequestId", id,
                "recipientRef", recipient, "status", state, "homeCell", "local", "items", List.of(Map.of(
                    "itemNo", "external-item-1", "clientItemId", "item-1", "skuId", "sku-1", "benefitType", "CASH", "quantity", 1,
                    "amountMinor", 100, "currency", "CNY", "skuVersion", version, "status", "SUCCEEDED"))));
    }
    private JsonNode get(String id, String tenant, String permission, int expected) throws Exception {
        var response = http.send(base(id, tenant, permission).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(expected, response.statusCode(), response.body()); return mapper.readTree(response.body());
    }
    private HttpResponse<String> refresh(String id, String tenant, String permission) throws Exception {
        var builder = base(id, tenant, permission);
        builder.uri(URI.create(builder.build().uri().toString()+":refresh"));
        return http.send(builder.POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
    }
    private HttpRequest.Builder base(String id, String tenant, String permission) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/award-intents/"+id+"/fulfillment"))
                .header("X-Dev-Tenant-Id", tenant).header("X-Dev-Actor-Id", "operator").header("X-Dev-Permissions", permission);
    }
    private static HttpServer server() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/openapi/v1/award-orders", exchange -> {
                TENANT.set(exchange.getRequestHeaders().getFirst("X-Tenant-Id")); AUTH.set(exchange.getRequestHeaders().getFirst("Authorization"));
                String query = exchange.getRequestURI().getRawQuery(); String id = query.substring(query.indexOf("sourceRequestId=")+16);
                Reply reply = REPLIES.getOrDefault(id, new Reply(404, "{}", 0));
                byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(reply.code(), bytes.length);
                // 先发头再延迟正文，验证总时限确实覆盖正文，而非仅等待响应头。
                try { if (reply.delayMs()>0) Thread.sleep(reply.delayMs()); exchange.getResponseBody().write(bytes); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                finally { exchange.close(); }
            }); server.setExecutor(HTTP_POOL); server.start(); return server;
        } catch (java.io.IOException e) { throw new ExceptionInInitializerError(e); }
    }
    private record Reply(int code, String body, int delayMs) { }
}
