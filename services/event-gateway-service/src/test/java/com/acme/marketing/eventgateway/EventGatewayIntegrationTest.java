package com.acme.marketing.eventgateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.acme.marketing.testsupport.MySqlIntegrationTest;
import com.acme.marketing.eventgateway.application.EventOutboxDepthRepository;
import com.acme.marketing.eventgateway.application.EventOutboxRepository;
import com.acme.marketing.eventgateway.infrastructure.EventOutboxBackpressure;
import com.acme.marketing.platform.error.DependencyUnavailableException;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class EventGatewayIntegrationTest extends MySqlIntegrationTest {
    @LocalServerPort private int port;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MeterRegistry meters;
    @Autowired private EventOutboxRepository outbox;
    @Autowired private EventOutboxDepthRepository outboxDepth;
    private final HttpClient client = HttpClient.newHttpClient();

    @Test
    void validatesDeduplicatesAndQuarantinesVersionGaps() throws Exception {
        Map<String, Object> source = Map.of(
                "sourceId", "orders", "sourceUri", "urn:orders", "allowedTypes", Set.of("ORDER_PAID"),
                "schemaVersions", Set.of("1.0.0"), "maxLatenessSeconds", 86_400);
        JsonNode createdSource = post("/api/v1/events/sources", source, "event-source-command-001");
        JsonNode replayedSource = post("/api/v1/events/sources", source, "event-source-command-001");
        assertEquals(createdSource, replayedSource);
        HttpResponse<String> sourceConflict = postRaw("/api/v1/events/sources", Map.of(
                "sourceId", "orders", "sourceUri", "urn:changed", "allowedTypes", Set.of("ORDER_PAID"),
                "schemaVersions", Set.of("1.0.0"), "maxLatenessSeconds", 86_400),
                "event-source-command-001");
        assertEquals(409, sourceConflict.statusCode());
        assertTrue(sourceConflict.body().contains("IDEMPOTENCY_PAYLOAD_CONFLICT"));
        assertEquals(1, jdbc.queryForObject(
                "select count(*) from mk_source_registration where tenant_id=? and source_id=?",
                Integer.class, "tenant-a", "orders"));
        Instant now = Instant.now();
        Map<String, Object> first = event("event-1", 1, now);
        assertEquals("ACCEPTED", post("/api/v1/events", first).get("status").asString());
        assertEquals("DUPLICATE", post("/api/v1/events", first).get("status").asString());
        EventOutboxBackpressure strictBackpressure = new EventOutboxBackpressure(
                outbox, outboxDepth, Clock.systemUTC(), meters, 1, 100, 300, 0, 100);
        DependencyUnavailableException overloaded = assertThrows(DependencyUnavailableException.class,
                () -> strictBackpressure.assertWritable("tenant-a"));
        assertEquals("EVENT_OUTBOX_BACKPRESSURE", overloaded.code());
        assertEquals("QUARANTINED", post("/api/v1/events", event("event-3", 3, now)).get("status").asString());
        JsonNode quarantine = get("/api/v1/quarantine");
        assertEquals(1, quarantine.size());
        assertEquals("AGGREGATE_VERSION_GAP", quarantine.get(0).get("reasonCode").asString());
    }

    @Test
    void concurrentCreateWithTheSameIdempotencyKeyCommitsOneSourceAndReplaysItsResponse() throws Exception {
        String sourceId = "concurrent-source-" + UUID.randomUUID();
        String key = "concurrent-source-command-" + UUID.randomUUID();
        Map<String, Object> source = Map.of(
                "sourceId", sourceId, "sourceUri", "urn:" + sourceId,
                "allowedTypes", Set.of("ORDER_PAID"), "schemaVersions", Set.of("1.0.0"),
                "maxLatenessSeconds", 86_400);
        var pool = Executors.newFixedThreadPool(2);
        try {
            List<Callable<HttpResponse<String>>> calls = List.of(
                    () -> postRaw("/api/v1/events/sources", source, key),
                    () -> postRaw("/api/v1/events/sources", source, key));
            List<HttpResponse<String>> responses = pool.invokeAll(calls).stream().map(future -> {
                try { return future.get(); }
                catch (Exception failure) { throw new IllegalStateException(failure); }
            }).toList();
            assertTrue(responses.stream().allMatch(response -> response.statusCode() == 201));
            assertEquals(1, responses.stream().map(HttpResponse::body).distinct().count());
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, jdbc.queryForObject(
                "select count(*) from mk_source_registration where tenant_id=? and source_id=?",
                Integer.class, "tenant-a", sourceId));
    }

    private static Map<String, Object> event(String id, long version, Instant now) {
        return Map.of("eventId", id, "sourceId", "orders", "eventType", "ORDER_PAID",
                "businessKey", "order-1", "subjectToken", "subject-1", "occurredAt", now,
                "schemaVersion", "1.0.0", "aggregateVersion", version, "data", Map.of("amount", 100));
    }

    private JsonNode post(String path, Object body) throws Exception {
        return post(path, body, "event-test-" + UUID.randomUUID());
    }

    private JsonNode post(String path, Object body, String key) throws Exception {
        HttpResponse<String> response = postRaw(path, body, key);
        assertTrue(response.statusCode() >= 200 && response.statusCode() < 300,
                () -> response.statusCode() + ": " + response.body());
        return mapper.readTree(response.body());
    }

    private HttpResponse<String> postRaw(String path, Object body, String key) throws Exception {
        HttpRequest request = base(path).header("Content-Type", "application/json")
                .header("Idempotency-Key", key)
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode get(String path) throws Exception { return exchange(base(path).GET().build()); }

    private HttpRequest.Builder base(String path) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(5)).header("X-Dev-Tenant-Id", "tenant-a")
                .header("X-Dev-Actor-Id", "event-test").header("X-Dev-Permissions", "*");
    }

    private JsonNode exchange(HttpRequest request) throws Exception {
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertTrue(response.statusCode() >= 200 && response.statusCode() < 300,
                () -> response.statusCode() + ": " + response.body());
        return mapper.readTree(response.body());
    }
}
