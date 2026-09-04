package com.acme.marketing.measurement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.acme.marketing.contracts.event.MarketingFact;
import com.acme.marketing.decision.experiment.ExperimentAssigner;
import com.acme.marketing.testsupport.MySqlIntegrationTest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MeasurementIntegrationTest extends MySqlIntegrationTest {
    @LocalServerPort private int port;
    @Autowired private ObjectMapper mapper;
    private final HttpClient client = HttpClient.newHttpClient();

    @Test
    void assignmentFactDedupAttributionDashboardAndTraceAreConsistent() throws Exception {
        post("/api/v1/experiments", Map.of("experimentId", "exp-1", "version", "1", "layer", "pricing",
                "salt", "a-secure-test-salt", "variants", List.of(
                        new ExperimentAssigner.Variant("A", 5_000, false),
                        new ExperimentAssigner.Variant("HOLDOUT", 5_000, true))));
        JsonNode assignment = post("/api/v1/experiments/exp-1/versions/1:assign", Map.of("unit", "subject-1"));
        JsonNode replay = post("/api/v1/experiments/exp-1/versions/1:assign", Map.of("unit", "subject-1"));
        assertEquals(assignment.get("variantId").asString(), replay.get("variantId").asString());

        Instant now = Instant.now();
        Map<String, Object> touch = fact("touch-1", MarketingFact.Type.OFFER_SHOWN, now,
                Map.of("actualAction", "true"));
        Map<String, Object> conversion = fact("conversion-1", MarketingFact.Type.CONVERSION, now.plusSeconds(10),
                Map.of("revenueMinor", "10000"));
        post("/api/v1/measurements/facts", touch);
        assertFalse(post("/api/v1/measurements/facts", conversion).get("duplicate").asBoolean());
        assertTrue(post("/api/v1/measurements/facts", conversion).get("duplicate").asBoolean());
        post("/api/v1/measurements/watermarks", Map.of(
                "projectionName", "dashboard", "partitionId", 0, "partitionCount", 2,
                "sourceOffset", 10, "completeThrough", now));
        JsonNode watermark = post("/api/v1/measurements/watermarks", Map.of(
                "projectionName", "dashboard", "partitionId", 1, "partitionCount", 2,
                "sourceOffset", 8, "completeThrough", now.minusSeconds(1)));
        assertEquals(2, watermark.get("reportedPartitions").asInt());
        assertEquals(now.minusSeconds(1).toEpochMilli(), Instant.parse(
                watermark.get("completeThrough").asString()).toEpochMilli());

        JsonNode dashboard = get("/api/v1/measurements/dashboard?from=" + now.minusSeconds(60)
                + "&to=" + now.plusSeconds(60));
        assertEquals(10_000, dashboard.get("attributedRevenueMinor").asLong());
        assertEquals(1, dashboard.get("counts").get("CONVERSION").asLong());
        JsonNode attribution = get("/api/v1/measurements/attribution/conversion-1?policy=LAST_TOUCH&windowSeconds=3600");
        assertEquals(1, attribution.get("touchCredits").size());
        JsonNode series = get("/api/v1/measurements/series?from=" + now.minusSeconds(60)
                + "&to=" + now.plusSeconds(7200) + "&granularity=hour");
        long conversions = 0;
        for (JsonNode point : series.get("points")) conversions += point.get("conversions").asLong();
        assertTrue(conversions >= 1);
        JsonNode recomputed = post("/api/v1/measurements/attribution:recompute", Map.of(),
                "attribution-recompute-001");
        JsonNode recomputeReplay = post("/api/v1/measurements/attribution:recompute", Map.of(),
                "attribution-recompute-001");
        assertTrue(recomputed.get("credits").asInt() >= 3);
        assertEquals(recomputed.get("completedAt").asString(), recomputeReplay.get("completedAt").asString());

        post("/api/v1/traces", Map.ofEntries(
                Map.entry("traceId", "trace-1"), Map.entry("requestId", "request-1"),
                Map.entry("orderId", "order-1"), Map.entry("subjectToken", "subject-1"),
                Map.entry("generation", 1), Map.entry("durationMicros", 900),
                Map.entry("candidates", Map.of("offer-1", "ELIGIBLE")),
                Map.entry("pricing", Map.of("payable", 8000)), Map.entry("termsVersion", "terms-v1"),
                Map.entry("expiresAt", now.plusSeconds(3600)), Map.entry("legalHold", false)));
        JsonNode trace = get("/api/v1/traces/requests/request-1");
        assertEquals("trace-1", trace.get("traceId").asString());
        assertTrue(trace.get("maskedSubject").asString().endsWith("…"));
        assertEquals(0, trace.get("events").size());
    }

    @Test
    void correctionMustCompareAndSetTheCurrentChainHead() throws Exception {
        Instant now = Instant.now();
        Map<String, Object> original = correctedFact("chain-a", "", now, "100");
        Map<String, Object> next = correctedFact("chain-b", "chain-a", now.plusSeconds(1), "80");
        Map<String, Object> stale = correctedFact("chain-c", "chain-a", now.plusSeconds(2), "70");

        post("/api/v1/measurements/facts", original);
        post("/api/v1/measurements/facts", next);

        HttpResponse<String> response = client.send(base("/api/v1/measurements/facts")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(stale))).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(409, response.statusCode());
    }

    private static Map<String, Object> fact(String id, MarketingFact.Type type, Instant occurredAt,
            Map<String, String> attributes) {
        return Map.of("eventId", id, "type", type, "businessKey", "order-1", "subjectToken", "subject-1",
                "occurredAt", occurredAt, "ingestedAt", Instant.now(), "schemaVersion", "1.0.0",
                "attributes", attributes, "correctionOf", "");
    }
    private static Map<String, Object> correctedFact(String id, String correctionOf, Instant occurredAt,
            String revenue) {
        return Map.of("eventId", id, "type", MarketingFact.Type.CONVERSION, "businessKey", "chain-order",
                "subjectToken", "chain-subject", "occurredAt", occurredAt, "ingestedAt", Instant.now(),
                "schemaVersion", "1.0.0", "attributes", Map.of("revenueMinor", revenue),
                "correctionOf", correctionOf);
    }
    private JsonNode post(String path, Object body) throws Exception {
        return exchange(base(path).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build());
    }
    private JsonNode post(String path, Object body, String idempotencyKey) throws Exception {
        return exchange(base(path).header("Content-Type", "application/json")
                .header("Idempotency-Key", idempotencyKey)
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build());
    }
    private JsonNode get(String path) throws Exception { return exchange(base(path).GET().build()); }
    private HttpRequest.Builder base(String path) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).timeout(Duration.ofSeconds(30))
                .header("X-Dev-Tenant-Id", "tenant-a").header("X-Dev-Actor-Id", "measurement-test")
                .header("X-Dev-Permissions", "*");
    }
    private JsonNode exchange(HttpRequest request) throws Exception {
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertTrue(response.statusCode() >= 200 && response.statusCode() < 300,
                () -> response.statusCode() + ": " + response.body());
        return mapper.readTree(response.body());
    }
}
