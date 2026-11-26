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
import java.util.UUID;
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
        String number = snapshot.path("businessNo").asString();
        assertTrue(number.matches("MKT-SNP-[0-9]{6}-[0-9]{10}"));
        assertEquals(snapshot, get("/api/v1/audiences/snapshots/" + number));
        JsonNode member = get("/api/v1/audiences/snapshots/" + snapshotId
                + "/memberships/s1?usedAt=" + now.plusSeconds(10) + "&stalePolicy=REJECT_CANDIDATE");
        assertTrue(member.get("member").asBoolean());
        JsonNode updated = put("/api/v1/audiences/snapshots/" + snapshotId + "/memberships", Map.of(
                "subjectToken", "s1", "version", 2, "member", false));
        assertEquals("UPDATED", updated.get("freshness").asString());
        assertEquals(2, updated.get("version").asLong());
        JsonNode ignored = put("/api/v1/audiences/snapshots/" + snapshotId + "/memberships", Map.of(
                "subjectToken", "s1", "version", 1, "member", true));
        assertEquals("STALE_UPDATE_IGNORED", ignored.get("freshness").asString());
        JsonNode stale = get("/api/v1/audiences/snapshots/" + snapshotId
                + "/memberships/s1?usedAt=" + now.plusSeconds(7200) + "&stalePolicy=GENERIC_PATH");
        assertEquals("STALE_GENERIC_PATH", stale.get("freshness").asString());
    }

    @Test
    void createApiReplaysFirstResponseAndRejectsPayloadReuse() throws Exception {
        String suffix = UUID.randomUUID().toString();
        String fieldId = "idempotent-" + suffix;
        String key = "audience-idempotency-" + suffix;
        Map<String, Object> request = Map.of(
                "fieldId", fieldId, "valueType", "STRING", "owner", "identity",
                "provenance", "verified-profile", "classification", "PERSONAL",
                "allowedUses", Set.of("ELIGIBILITY"), "maxAgeSeconds", 3600,
                "nullPolicy", "NO_MATCH", "missingPolicy", "NO_MATCH", "retentionDays", 30);

        HttpResponse<String> first = postRaw("/api/v1/fields", request, key);
        HttpResponse<String> replay = postRaw("/api/v1/fields", request, key);
        assertEquals(201, first.statusCode());
        assertEquals(first.body(), replay.body());

        Map<String, Object> conflicting = new java.util.HashMap<>(request);
        conflicting.put("owner", "another-owner");
        HttpResponse<String> conflict = postRaw("/api/v1/fields", conflicting, key);
        assertEquals(409, conflict.statusCode());
        assertTrue(conflict.body().contains("IDEMPOTENCY_PAYLOAD_CONFLICT"));
        long occurrences = 0;
        for (JsonNode field : get("/api/v1/fields")) {
            if (fieldId.equals(field.get("fieldId").asString())) occurrences++;
        }
        assertEquals(1, occurrences);
    }

    @Test
    void snapshotReplayUsesPathAndUnorderedMembersAsCommandIdentity() throws Exception {
        String segmentId = newSegment();
        String path = "/api/v1/audiences/" + segmentId + "/versions/1/snapshots";
        Instant now = Instant.now();
        Map<String, Object> request = new java.util.HashMap<>(Map.of("subjectTokens", List.of("s2", "s1"),
                "asOf", now, "watermark", now, "expiresAt", now.plusSeconds(3600)));
        String key = "snapshot-" + UUID.randomUUID();
        HttpResponse<String> first = postRaw(path, request, key);
        assertEquals(201, first.statusCode(), first.body());
        request.put("subjectTokens", List.of("s1", "s2"));
        assertEquals(first.body(), postRaw(path, request, key).body());
        assertEquals(409, postRaw("/api/v1/audiences/" + newSegment() + "/versions/1/snapshots", request, key).statusCode());
        request.put("subjectTokens", List.of("s3"));
        assertEquals(409, postRaw(path, request, key).statusCode());
        String id = mapper.readTree(first.body()).get("snapshotId").asString();
        var atExpiry = get("/api/v1/audiences/snapshots/" + id + "/memberships/s1?usedAt="
                + now.plusSeconds(3600) + "&stalePolicy=REJECT_CANDIDATE");
        assertEquals("STALE_REJECTED", atExpiry.get("freshness").asString());
        assertEquals(false, atExpiry.get("member").asBoolean());
        // 权限必须在幂等重放前复核，旧响应不是绕过当前权限的通行证。
        var forbidden = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("X-Dev-Tenant-Id", "tenant-a").header("X-Dev-Actor-Id", "read-only")
                .header("X-Dev-Permissions", "audience:read").header("Idempotency-Key", key)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(request))).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(403, forbidden.statusCode(), forbidden.body());
    }

    @Test
    void snapshotRejectsInvalidTimesMembersAndAlreadyExpiredCreation() throws Exception {
        String path = "/api/v1/audiences/" + newSegment() + "/versions/1/snapshots";
        Instant now = Instant.now();
        Map<String, Object> valid = Map.of("subjectTokens", List.of("s1"), "asOf", now,
                "watermark", now, "expiresAt", now.plusSeconds(3600));
        for (Map<String, Object> change : List.of(Map.<String, Object>of("watermark", now.plusSeconds(1)),
                Map.<String, Object>of("expiresAt", now), Map.<String, Object>of("subjectTokens", List.of(" ")),
                Map.<String, Object>of("subjectTokens", java.util.stream.IntStream.range(0, 10001)
                        .mapToObj(i -> "subject-" + i).toList()),
                Map.<String, Object>of("asOf", now.minusSeconds(3600), "watermark", now.minusSeconds(3600),
                        "expiresAt", now.minusSeconds(1)))) {
            Map<String, Object> invalid = new java.util.HashMap<>(valid); invalid.putAll(change);
            assertEquals(400, postRaw(path, invalid, "invalid-" + UUID.randomUUID()).statusCode());
        }
        var missing = new java.util.HashMap<>(valid); missing.remove("watermark");
        assertEquals(400, postRaw(path, missing, "missing-" + UUID.randomUUID()).statusCode());
    }

    @Test
    void concurrentFirstMembershipWritesRemainMonotonicAndReturnTheStoredState() throws Exception {
        Instant now = Instant.now();
        String id = post("/api/v1/audiences/" + newSegment() + "/versions/1/snapshots", Map.of(
                "subjectTokens", List.of(), "asOf", now, "watermark", now, "expiresAt", now.plusSeconds(3600)))
                .get("snapshotId").asString();
        String path = "/api/v1/audiences/snapshots/" + id + "/memberships";
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(8)) {
            var writes = java.util.stream.IntStream.rangeClosed(1, 8).mapToObj(version -> pool.submit(() -> {
                start.await();
                return putRaw(path, Map.of("subjectToken", "concurrent", "version", version, "member", true));
            })).toList();
            start.countDown();
            for (var write : writes) {
                var response = write.get(30, java.util.concurrent.TimeUnit.SECONDS);
                assertEquals(200, response.statusCode(), response.body());
                assertTrue(mapper.readTree(response.body()).get("member").asBoolean());
            }
        }
        JsonNode stale = put(path, Map.of("subjectToken", "concurrent", "version", 2, "member", false));
        assertEquals(8, stale.get("version").asLong());
        assertTrue(stale.get("member").asBoolean());
        assertEquals("STALE_UPDATE_IGNORED", stale.get("freshness").asString());
        var conflict = putRaw(path, Map.of("subjectToken", "concurrent", "version", 8, "member", false));
        assertEquals(409, conflict.statusCode(), conflict.body());
        assertTrue(conflict.body().contains("AUDIENCE_MEMBERSHIP_VERSION_CONFLICT"));
        assertEquals(400, putRaw(path, Map.of("subjectToken", "new", "version", 0, "member", true)).statusCode());
        assertEquals(400, putRaw(path, Map.of("subjectToken", " ", "version", 1, "member", true)).statusCode());
        assertEquals(400, putRaw(path, Map.of("subjectToken", "concurrent", "version", 9)).statusCode());
        JsonNode current = get(path + "/concurrent?usedAt=" + now + "&stalePolicy=REJECT_CANDIDATE");
        assertEquals(8, current.get("version").asLong());
        assertTrue(current.get("member").asBoolean());
    }

    @Test
    void concurrentConflictingFirstVersionHasOneWinner() throws Exception {
        Instant now = Instant.now();
        String id = post("/api/v1/audiences/" + newSegment() + "/versions/1/snapshots", Map.of(
                "subjectTokens", List.of(), "asOf", now, "watermark", now, "expiresAt", now.plusSeconds(3600)))
                .get("snapshotId").asString();
        String path = "/api/v1/audiences/snapshots/" + id + "/memberships";
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var writes = List.of(true, false).stream().map(member -> pool.submit(() -> {
                start.await();
                return putRaw(path, Map.of("subjectToken", "same-version", "version", 1, "member", member));
            })).toList();
            start.countDown();
            var first = writes.get(0).get(30, java.util.concurrent.TimeUnit.SECONDS);
            var second = writes.get(1).get(30, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(Set.of(200, 409), Set.of(first.statusCode(), second.statusCode()), first.body() + second.body());
            JsonNode current = get(path + "/same-version?usedAt=" + now + "&stalePolicy=REJECT_CANDIDATE");
            assertEquals(first.statusCode() == 200, current.get("member").asBoolean());
        }
    }

    private String newSegment() throws Exception {
        String id = "zz-" + UUID.randomUUID();
        post("/api/v1/fields", Map.of("fieldId", id, "valueType", "STRING", "owner", "identity",
                "provenance", "isolated-contract-test", "classification", "PERSONAL", "allowedUses", Set.of("ELIGIBILITY"),
                "maxAgeSeconds", 3600, "nullPolicy", "NO_MATCH", "missingPolicy", "NO_MATCH", "retentionDays", 30));
        post("/api/v1/audiences", Map.of("segmentId", id, "name", "ZZ isolated", "rule", Map.of(
                "match", "ALL", "conditions", List.of(Map.of("fieldId", id, "operator", "EQ", "value", "yes")))));
        return id;
    }

    private HttpResponse<String> putRaw(String path, Object body) throws Exception {
        return client.send(base(path).header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode post(String path, Object body) throws Exception {
        return exchange(base(path).header("Content-Type", "application/json")
                .header("Idempotency-Key", "audience-test-" + UUID.randomUUID())
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build());
    }

    @Test
    void instantRuleSurvivesPersistenceAndInvalidOrderingIsRejectedAtSave() throws Exception {
        String suffix = UUID.randomUUID().toString();
        String instantField = "joined-" + suffix;
        for (var field : Map.of(instantField, "INSTANT", "flag-" + suffix, "BOOLEAN").entrySet()) {
            post("/api/v1/fields", Map.of("fieldId", field.getKey(), "valueType", field.getValue(),
                    "owner", "identity", "provenance", "verified-profile", "classification", "PERSONAL",
                    "allowedUses", Set.of("ELIGIBILITY"), "maxAgeSeconds", 3600,
                    "nullPolicy", "NO_MATCH", "missingPolicy", "NO_MATCH", "retentionDays", 30));
        }
        String segmentId = "recent-" + suffix;
        post("/api/v1/audiences", Map.of("segmentId", segmentId, "name", "Recent", "rule", Map.of(
                "match", "ALL", "conditions", List.of(Map.of("fieldId", instantField, "operator", "GT",
                        "value", "2026-01-01T00:00:00Z")))));
        var preview = post("/api/v1/audiences/" + segmentId + "/versions/1:preview", List.of(
                Map.of("subjectToken", "new", "attributes", Map.of(instantField, "2026-01-02T00:00:00Z")),
                Map.of("subjectToken", "same", "attributes", Map.of(instantField, "2026-01-01T08:00:00+08:00")),
                Map.of("subjectToken", "bad", "attributes", Map.of(instantField, "not-a-date"))));
        assertEquals(1, preview.get("estimatedCount").asLong());
        var invalid = postRaw("/api/v1/audiences", Map.of("segmentId", "bad-" + suffix, "name", "Bad",
                "rule", Map.of("match", "ALL", "conditions", List.of(Map.of("fieldId", "flag-" + suffix,
                        "operator", "GT", "value", "true")))), "invalid-" + suffix);
        assertEquals(400, invalid.statusCode());
    }

    private HttpResponse<String> postRaw(String path, Object body, String idempotencyKey) throws Exception {
        return client.send(base(path).header("Content-Type", "application/json")
                .header("Idempotency-Key", idempotencyKey)
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode get(String path) throws Exception { return exchange(base(path).GET().build()); }
    private JsonNode put(String path, Object body) throws Exception {
        return exchange(base(path).header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build());
    }
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
