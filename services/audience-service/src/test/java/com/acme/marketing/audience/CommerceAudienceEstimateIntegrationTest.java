package com.acme.marketing.audience;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.acme.marketing.testsupport.MySqlIntegrationTest;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 用真实规则存储和商城 HTTP 合同验证预估、旧版兼容及快照边界。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CommerceAudienceEstimateIntegrationTest extends MySqlIntegrationTest {
    private static final String TENANT = "commerce-estimate";
    private static final String TOKEN = UUID.randomUUID() + "-" + UUID.randomUUID();
    private static final AtomicReference<SourceResponse> SOURCE = new AtomicReference<>();
    private static final AtomicReference<URI> UPSTREAM_REQUEST = new AtomicReference<>();
    private static final HttpServer COMMERCE = startCommerce();

    @LocalServerPort private int port;
    @Autowired private ObjectMapper mapper;
    private final HttpClient client = HttpClient.newHttpClient();

    @DynamicPropertySource
    static void commerceProperties(DynamicPropertyRegistry registry) {
        registry.add("marketing.audience.commerce.enabled", () -> true);
        registry.add("marketing.audience.commerce.tenant", () -> TENANT);
        registry.add("marketing.audience.commerce.bearer-token", () -> TOKEN);
        registry.add("marketing.audience.commerce.allow-local-http", () -> true);
        registry.add("marketing.audience.commerce.base-url", () -> "http://127.0.0.1:" + COMMERCE.getAddress().getPort());
    }

    @AfterAll static void stopCommerce() { COMMERCE.stop(0); }

    /** 同一规则汇总大人群，旧请求与快照继续遵守原上限。 */
    @Test
    void aggregatesLargePopulationAndRetainsLegacyLimit() throws Exception {
        String version = saveRule();
        reply(200, "levels", List.of(level("GOLD", 12_500), level("SILVER", 11), level("gold", 9)));
        var preview = get(version + "/profile-estimate?allowLargePopulation=true", TENANT, "audience:preview");
        assertEquals(200, preview.statusCode());
        var result = mapper.readTree(preview.body());
        assertEquals(12_511, result.path("memberCount").asInt());
        assertEquals(2, result.size());
        assertEquals("view=level-summary", UPSTREAM_REQUEST.get().getQuery());

        reply(409, "levels", List.of());
        assertEquals(409, get(version + "/profile-estimate", TENANT, "audience:preview").statusCode());
        assertNull(UPSTREAM_REQUEST.get().getQuery());
        assertEquals(409, post(version + "/profile-snapshots", Map.of()).statusCode());
        assertNull(UPSTREAM_REQUEST.get().getQuery());

        reply(200, "profiles", List.of(Map.of("memberId", "m1", "memberLevel", "GOLD"),
                Map.of("memberId", "m2", "memberLevel", "gold")));
        preview = get(version + "/profile-estimate?allowLargePopulation=true", TENANT, "audience:preview");
        assertEquals(200, preview.statusCode());
        assertEquals(1, mapper.readTree(preview.body()).path("memberCount").asInt());
        assertEquals(201, post(version + "/profile-snapshots", Map.of()).statusCode());
        assertNull(UPSTREAM_REQUEST.get().getQuery());
    }

    /** 异常汇总和越权请求不能产生可用的预估结果。 */
    @Test
    void rejectsMalformedCountsAndUnauthorizedClients() throws Exception {
        String path = saveRule() + "/profile-estimate?allowLargePopulation=true";
        UPSTREAM_REQUEST.set(null);
        assertEquals(403, get(path, TENANT, "audience:read").statusCode());
        assertEquals(409, get(path, "unpaired-tenant", "audience:preview").statusCode());
        assertNull(UPSTREAM_REQUEST.get());

        for (var invalid : List.of(
                List.of(level("GOLD", 0)), List.of(level("GOLD", 1.5)), List.of(level("GOLD", "1")),
                List.of(level("GOLD", 1), level("GOLD", 2)),
                List.of(level("GOLD", Integer.MAX_VALUE), level("OTHER", 1)))) {
            reply(200, "levels", invalid);
            var failure = get(path, TENANT, "audience:preview");
            assertEquals(502, failure.statusCode());
            assertTrue(failure.body().contains("AUDIENCE_PROFILE_SOURCE_UNAVAILABLE"));
        }
        SOURCE.set(new SourceResponse(200, mapper.writeValueAsString(Map.of("asOf", Instant.now().minusSeconds(60).toString(),
                "levels", List.of(level("GOLD", 1))))));
        assertEquals(502, get(path, TENANT, "audience:preview").statusCode());
        SOURCE.set(new SourceResponse(200, mapper.writeValueAsString(Map.of("asOf", Instant.now().toString(),
                "levels", List.of(), "profiles", List.of()))));
        assertEquals(502, get(path, TENANT, "audience:preview").statusCode());
        reply(200, "levels", List.of());
        JsonNode empty = mapper.readTree(get(path, TENANT, "audience:preview").body());
        assertEquals(0, empty.path("memberCount").asInt());
        assertFalse(empty.has("levels"));
    }

    private String saveRule() throws Exception {
        var field = Map.of("fieldId", "member.level", "valueType", "STRING", "owner", "commerce-platform",
                    "provenance", "member_record.member_level", "classification", "PERSONAL", "allowedUses", List.of("ELIGIBILITY"),
                    "maxAgeSeconds", 60, "nullPolicy", "NO_MATCH", "missingPolicy", "NO_MATCH", "retentionDays", 30);
        assertEquals(201, send(builder("/api/v1/fields", TENANT, "*").header("Idempotency-Key", "member-level-field")
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(field)))).statusCode());
        String id = "level-" + UUID.randomUUID();
        var rule = Map.of("match", "ANY", "conditions", List.of(
                Map.of("fieldId", "member.level", "operator", "EQ", "value", "GOLD"),
                Map.of("fieldId", "member.level", "operator", "EQ", "value", "SILVER")));
        assertEquals(201, post("/api/v1/audiences", Map.of("segmentId", id, "name", "会员等级预估", "rule", rule)).statusCode());
        return "/api/v1/audiences/" + id + "/versions/1";
    }

    private static Map<String, Object> level(String level, Object count) {
        return Map.of("memberLevel", level, "memberCount", count);
    }

    private void reply(int status, String field, List<?> rows) throws Exception {
        SOURCE.set(new SourceResponse(status, mapper.writeValueAsString(Map.of("asOf", Instant.now().toString(), field, rows))));
    }

    private HttpResponse<String> post(String path, Object body) throws Exception {
        return send(builder(path, TENANT, "*").header("Idempotency-Key", UUID.randomUUID().toString())
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))));
    }

    private HttpResponse<String> get(String path, String tenant, String permissions) throws Exception {
        return send(builder(path, tenant, permissions).GET());
    }

    private HttpRequest.Builder builder(String path, String tenant, String permissions) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("X-Dev-Tenant-Id", tenant).header("X-Dev-Actor-Id", "estimate-operator")
                .header("X-Dev-Permissions", permissions);
    }

    private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpServer startCommerce() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/internal/v1/member-profiles/snapshot", request -> {
                try (request) {
                    if (!TENANT.equals(request.getRequestHeaders().getFirst("X-Marketing-Tenant"))
                            || !("Bearer " + TOKEN).equals(request.getRequestHeaders().getFirst("Authorization"))) {
                        request.sendResponseHeaders(403, -1);
                        return;
                    }
                    UPSTREAM_REQUEST.set(request.getRequestURI());
                    var response = SOURCE.get();
                    byte[] body = response.body().getBytes(StandardCharsets.UTF_8);
                    request.getResponseHeaders().set("Content-Type", "application/json");
                    request.sendResponseHeaders(response.status(), body.length);
                    request.getResponseBody().write(body);
                }
            });
            server.start();
            return server;
        } catch (IOException failure) {
            throw new IllegalStateException("无法启动隔离商城合同端点", failure);
        }
    }

    private record SourceResponse(int status, String body) { }
}
