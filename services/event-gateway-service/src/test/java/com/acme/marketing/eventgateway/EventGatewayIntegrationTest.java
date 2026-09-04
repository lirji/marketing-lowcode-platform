package com.acme.marketing.eventgateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.acme.marketing.testsupport.MySqlIntegrationTest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class EventGatewayIntegrationTest extends MySqlIntegrationTest {
    @LocalServerPort private int port;
    @Autowired private ObjectMapper mapper;
    private final HttpClient client = HttpClient.newHttpClient();

    @Test
    void validatesDeduplicatesAndQuarantinesVersionGaps() throws Exception {
        post("/api/v1/events/sources", Map.of(
                "sourceId", "orders", "sourceUri", "urn:orders", "allowedTypes", Set.of("ORDER_PAID"),
                "schemaVersions", Set.of("1.0.0"), "maxLatenessSeconds", 86_400));
        Instant now = Instant.now();
        Map<String, Object> first = event("event-1", 1, now);
        assertEquals("ACCEPTED", post("/api/v1/events", first).get("status").asString());
        assertEquals("DUPLICATE", post("/api/v1/events", first).get("status").asString());
        assertEquals("QUARANTINED", post("/api/v1/events", event("event-3", 3, now)).get("status").asString());
        JsonNode quarantine = get("/api/v1/quarantine");
        assertEquals(1, quarantine.size());
        assertEquals("AGGREGATE_VERSION_GAP", quarantine.get(0).get("reasonCode").asString());
    }

    private static Map<String, Object> event(String id, long version, Instant now) {
        return Map.of("eventId", id, "sourceId", "orders", "eventType", "ORDER_PAID",
                "businessKey", "order-1", "subjectToken", "subject-1", "occurredAt", now,
                "schemaVersion", "1.0.0", "aggregateVersion", version, "data", Map.of("amount", 100));
    }

    private JsonNode post(String path, Object body) throws Exception {
        HttpRequest request = base(path).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build();
        return exchange(request);
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
