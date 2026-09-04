package com.acme.marketing.audience;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.acme.marketing.testsupport.MySqlIntegrationTest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AudienceIntegrationTest extends MySqlIntegrationTest {
    @LocalServerPort private int port;
    @Autowired private ObjectMapper mapper;
    private final HttpClient client = HttpClient.newHttpClient();

    @Test
    void typedPreviewSnapshotAndFreshnessAreConsistent() throws Exception {
        post("/api/v1/fields", Map.of(
                "fieldId", "age", "valueType", "DECIMAL", "owner", "identity",
                "provenance", "verified-profile", "classification", "PERSONAL",
                "allowedUses", Set.of("ELIGIBILITY"), "maxAgeSeconds", 3600,
                "nullPolicy", "NO_MATCH", "missingPolicy", "NO_MATCH", "retentionDays", 30));
        JsonNode segment = post("/api/v1/audiences", Map.of(
                "segmentId", "adult", "name", "Adults", "rule", Map.of(
                        "match", "ALL", "conditions", List.of(Map.of(
                                "fieldId", "age", "operator", "GTE", "value", "18")))));
        assertEquals(1, segment.get("version").asLong());
        JsonNode fields = get("/api/v1/fields");
        assertEquals("age", fields.get(0).get("fieldId").asString());
        JsonNode audiences = get("/api/v1/audiences");
        assertEquals("adult", audiences.get(0).get("segmentId").asString());
        assertEquals("ACTIVE", audiences.get(0).get("status").asString());
        JsonNode preview = post("/api/v1/audiences/adult/versions/1:preview", List.of(
                Map.of("subjectToken", "s1", "attributes", Map.of("age", "21")),
                Map.of("subjectToken", "s2", "attributes", Map.of("age", "15"))));
        assertEquals(1, preview.get("estimatedCount").asLong());

        Instant now = Instant.now();
        JsonNode snapshot = post("/api/v1/audiences/adult/versions/1/snapshots", Map.of(
                "subjectTokens", Set.of("s1"), "asOf", now, "watermark", now,
                "expiresAt", now.plusSeconds(3600)));
        String snapshotId = snapshot.get("snapshotId").asString();
        JsonNode member = get("/api/v1/audiences/snapshots/" + snapshotId
                + "/memberships/s1?usedAt=" + now.plusSeconds(10) + "&stalePolicy=REJECT_CANDIDATE");
        assertTrue(member.get("member").asBoolean());
        JsonNode stale = get("/api/v1/audiences/snapshots/" + snapshotId
                + "/memberships/s1?usedAt=" + now.plusSeconds(7200) + "&stalePolicy=GENERIC_PATH");
        assertEquals("STALE_GENERIC_PATH", stale.get("freshness").asString());
    }

    private JsonNode post(String path, Object body) throws Exception {
        return exchange(base(path).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build());
    }

    private JsonNode get(String path) throws Exception { return exchange(base(path).GET().build()); }
    private HttpRequest.Builder base(String path) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).timeout(Duration.ofSeconds(30))
                .header("X-Dev-Tenant-Id", "tenant-a").header("X-Dev-Actor-Id", "audience-test")
                .header("X-Dev-Permissions", "*");
    }
    private JsonNode exchange(HttpRequest request) throws Exception {
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertTrue(response.statusCode() >= 200 && response.statusCode() < 300,
                () -> response.statusCode() + ": " + response.body());
        return mapper.readTree(response.body());
    }
}
