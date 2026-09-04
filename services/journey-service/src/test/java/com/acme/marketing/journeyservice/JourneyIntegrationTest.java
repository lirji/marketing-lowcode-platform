package com.acme.marketing.journeyservice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.acme.marketing.contracts.event.JourneyEffectCommand;
import com.acme.marketing.journey.EnrollmentSnapshot;
import com.acme.marketing.journey.JourneyNode;
import com.acme.marketing.journey.JourneyPlan;
import com.acme.marketing.journey.event.JourneyStateChangedEvent;
import com.acme.marketing.journeyservice.application.JourneyOutputProjector;
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
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class JourneyIntegrationTest extends MySqlIntegrationTest {
    @LocalServerPort private int port;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JourneyOutputProjector projector;
    private final HttpClient client = HttpClient.newHttpClient();

    @Test
    void versionPinnedEnrollmentWaitsResumesAndDeduplicates() throws Exception {
        put("/api/v1/journeys/journey-a/versions/1", plan(1, "wait"));
        Map<String, Object> enrollmentRequest = Map.of("journeyId", "journey-a", "journeyVersion", 1,
                "subjectToken", "subject-1", "triggerEventId", "trigger-1", "occurredAt", Instant.now());
        JsonNode enrolled = post("/api/v1/enrollments", enrollmentRequest);
        assertEquals("WAITING", enrolled.get("status").asString());
        JsonNode duplicateEnrollment = post("/api/v1/enrollments", enrollmentRequest);
        assertTrue(duplicateEnrollment.get("duplicate").asBoolean());
        String enrollmentId = enrolled.get("enrollmentId").asString();

        Map<String, Object> signal = Map.of("type", "EVENT", "signalId", "event-1", "name", "ORDER_PAID",
                "occurredAt", Instant.now(), "attributes", Map.of());
        JsonNode completed = post("/api/v1/enrollments/" + enrollmentId + "/signals", signal);
        assertEquals("COMPLETED", completed.get("status").asString());
        assertEquals(1, completed.get("commands").size());
        JsonNode duplicateSignal = post("/api/v1/enrollments/" + enrollmentId + "/signals", signal);
        assertTrue(duplicateSignal.get("duplicate").asBoolean());
        JsonNode enrollments = get("/api/v1/enrollments?status=COMPLETED&journeyId=journey-a&limit=10");
        assertEquals(enrollmentId, enrollments.get(0).get("enrollmentId").asString());
    }

    @Test
    void flinkOutputIsIdempotentlyMaterializedAndEffectIsPlacedInDispatchOutbox() throws Exception {
        put("/api/v1/journeys/journey-stream/versions/1", new JourneyPlan("journey-stream", 1, "end",
                Map.of("end", new JourneyNode("end", JourneyNode.Type.END, Map.of(), Map.of())),
                5, 1, Duration.ofDays(2)));
        Instant now = Instant.now();
        EnrollmentSnapshot snapshot = new EnrollmentSnapshot("tenant-a", "stream-enrollment-1", "stream-subject",
                "journey-stream", 1, "end", EnrollmentSnapshot.Status.COMPLETED, Map.of(),
                java.util.Set.of("stream-start-1"), Map.of(), Map.of(), now);
        String state = mapper.writeValueAsString(new JourneyStateChangedEvent("JOURNEY_STATE_CHANGED", snapshot,
                "stream-start-1", false, now.toEpochMilli(), now.plus(Duration.ofDays(2)).toEpochMilli()));
        assertTrue(projector.project(state, "mk.journey.output.v1", 2, 10).applied());
        assertTrue(projector.project(state, "mk.journey.output.v1", 2, 10).duplicate());

        JourneyEffectCommand effect = new JourneyEffectCommand("JOURNEY_EFFECT_COMMAND", "tenant-a",
                "stream-enrollment-1", "stream-subject", "journey-stream", 1, "stream-command-0001",
                "SEND", "send", Map.of("channel", "SMS", "templateId", "paid-v1"), now.toEpochMilli());
        String effectJson = mapper.writeValueAsString(effect);
        projector.project(effectJson, "mk.journey.output.v1", 2, 11);
        projector.project(effectJson, "mk.journey.output.v1", 2, 12);

        assertEquals(1, jdbc.queryForObject("select count(*) from mk_node_effect_intent where tenant_id=? and command_id=?",
                Integer.class, "tenant-a", effect.commandId()));
        assertEquals(1, jdbc.queryForObject("select count(*) from mk_journey_dispatch_outbox where tenant_id=? and command_id=?",
                Integer.class, "tenant-a", effect.commandId()));
    }

    private static JourneyPlan plan(long version, String waitId) {
        return new JourneyPlan("journey-a", version, "start", Map.of(
                "start", new JourneyNode("start", JourneyNode.Type.TRIGGER, Map.of(), Map.of("next", waitId)),
                waitId, new JourneyNode(waitId, JourneyNode.Type.WAIT_EVENT,
                        Map.of("eventType", "ORDER_PAID"), Map.of("matched", "send")),
                "send", new JourneyNode("send", JourneyNode.Type.SEND,
                        Map.of("template", "paid-v1"), Map.of("next", "end")),
                "end", new JourneyNode("end", JourneyNode.Type.END, Map.of(), Map.of())),
                20, 3, Duration.ofDays(30));
    }

    private JsonNode post(String path, Object body) throws Exception {
        return exchange(base(path).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build());
    }
    private JsonNode put(String path, Object body) throws Exception {
        return exchange(base(path).header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build());
    }
    private JsonNode get(String path) throws Exception { return exchange(base(path).GET().build()); }
    private HttpRequest.Builder base(String path) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).timeout(Duration.ofSeconds(30))
                .header("X-Dev-Tenant-Id", "tenant-a").header("X-Dev-Actor-Id", "journey-test")
                .header("X-Dev-Permissions", "*");
    }
    private JsonNode exchange(HttpRequest request) throws Exception {
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertTrue(response.statusCode() >= 200 && response.statusCode() < 300,
                () -> response.statusCode() + ": " + response.body());
        return mapper.readTree(response.body());
    }
}
