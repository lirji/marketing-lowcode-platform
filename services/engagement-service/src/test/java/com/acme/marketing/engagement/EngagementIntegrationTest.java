package com.acme.marketing.engagement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.acme.marketing.contracts.event.JourneyEffectCommand;
import com.acme.marketing.engagement.application.EngagementService;
import com.acme.marketing.testsupport.MySqlIntegrationTest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class EngagementIntegrationTest extends MySqlIntegrationTest {
    @LocalServerPort private int port;
    @Autowired private ObjectMapper mapper;
    @Autowired private EngagementService service;
    @Autowired private JdbcTemplate jdbc;
    private final HttpClient client = HttpClient.newHttpClient();

    @Test
    void consentTemplateFrequencyIdempotencyAndReceiptOrderingAreEnforced() throws Exception {
        Instant now = Instant.now();
        put("/api/v1/consents", Map.of("subjectToken", "subject-1", "channel", "SMS", "allowed", true,
                "minor", false, "personalizationAllowed", true, "source", "preference-center",
                "effectiveAt", now.minusSeconds(10)));
        put("/api/v1/contacts/frequency-policies", Map.of("campaignId", "campaign-1", "channel", "SMS",
                "windowSeconds", 3600, "maxContacts", 1, "quietStart", LocalTime.MIDNIGHT,
                "quietEnd", LocalTime.MIDNIGHT));
        Map<String, Object> template = Map.of("templateId", "sms-paid", "version", 1, "channel", "SMS",
                "content", "Hello {{name}}", "requiredVariables", Set.of("name"));
        JsonNode createdTemplate = post("/api/v1/templates", template, "template-create-command-001");
        assertEquals(createdTemplate,
                post("/api/v1/templates", template, "template-create-command-001"));
        HttpResponse<String> templateConflict = rawPost("/api/v1/templates", Map.of(
                "templateId", "sms-paid", "version", 1, "channel", "SMS",
                "content", "Changed {{name}}", "requiredVariables", Set.of("name")),
                "template-create-command-001");
        assertEquals(409, templateConflict.statusCode());
        assertTrue(templateConflict.body().contains("IDEMPOTENCY_PAYLOAD_CONFLICT"));
        assertEquals(1, jdbc.queryForObject(
                "select count(*) from mk_template_version where tenant_id=? and template_id=? and version_no=?",
                Integer.class, "tenant-a", "sms-paid", 1));
        Map<String, Object> send = Map.ofEntries(
                Map.entry("contactKey", "contact-key-001"), Map.entry("subjectToken", "subject-1"),
                Map.entry("campaignId", "campaign-1"), Map.entry("channel", "SMS"),
                Map.entry("recipientToken", "phone-token"), Map.entry("templateId", "sms-paid"),
                Map.entry("templateVersion", 1), Map.entry("variables", Map.of("name", "Ada")),
                Map.entry("timezone", "Asia/Shanghai"), Map.entry("personalized", true),
                Map.entry("requestedAt", now));
        JsonNode first = post("/api/v1/contacts", send);
        JsonNode duplicate = post("/api/v1/contacts", send);
        assertEquals(first.get("contactId").asString(), duplicate.get("contactId").asString());
        assertEquals("ACCEPTED", first.get("state").asString());
        assertEquals(first.get("contactId").asString(), get("/api/v1/contacts/contact-key-001")
                .get("contactId").asString());
        assertTrue(get("/api/v1/templates").toString().contains("sms-paid"));
        assertTrue(get("/api/v1/contacts?state=ACCEPTED&limit=10").toString()
                .contains(first.get("contactId").asString()));

        String providerRequestId = first.get("providerRequestId").asString();
        JsonNode delivered = post("/api/v1/providers/callbacks", Map.of(
                "providerEventId", "receipt-1", "providerRequestId", providerRequestId,
                "status", "DELIVERED", "occurredAt", now.plusSeconds(1), "attributes", Map.of()));
        assertEquals("DELIVERED", delivered.get("state").asString());
        JsonNode lateFailure = post("/api/v1/providers/callbacks", Map.of(
                "providerEventId", "receipt-2", "providerRequestId", providerRequestId,
                "status", "FAILED", "occurredAt", now.plusSeconds(2), "attributes", Map.of()));
        assertEquals("DELIVERED", lateFailure.get("state").asString());

        Map<String, Object> second = new java.util.LinkedHashMap<>(send);
        second.put("contactKey", "contact-key-002");
        assertEquals(409, rawPost("/api/v1/contacts", second).statusCode());
    }

    @Test
    void journeySendCommandIsIdempotentAcrossKafkaReplay() throws Exception {
        Instant now = Instant.now();
        put("/api/v1/consents", Map.of("subjectToken", "journey-subject", "channel", "SMS", "allowed", true,
                "minor", false, "personalizationAllowed", true, "source", "journey-test",
                "effectiveAt", now.minusSeconds(1)));
        put("/api/v1/contacts/frequency-policies", Map.of("campaignId", "journey-campaign", "channel", "SMS",
                "windowSeconds", 3600, "maxContacts", 1, "quietStart", LocalTime.MIDNIGHT,
                "quietEnd", LocalTime.MIDNIGHT));
        post("/api/v1/templates", Map.of("templateId", "journey-sms", "version", 1, "channel", "SMS",
                "content", "Hi {{name}}", "requiredVariables", Set.of("name")));
        JourneyEffectCommand command = new JourneyEffectCommand("JOURNEY_EFFECT_COMMAND", "tenant-a",
                "journey-enrollment", "journey-subject", "journey-campaign", 1,
                "journey-send-command-001", "SEND", "send-node",
                Map.of("channel", "SMS", "templateId", "journey-sms", "templateVersion", "1",
                        "campaignId", "journey-campaign", "recipientToken", "phone-token", "name", "Ada"),
                now.toEpochMilli());

        EngagementService.CommandResult first = service.handleJourneyCommand(command);
        EngagementService.CommandResult replay = service.handleJourneyCommand(command);
        assertEquals(first.contactId(), replay.contactId());
        assertEquals(1, jdbc.queryForObject("select count(*) from mk_contact_attempt where tenant_id=? and contact_key=?",
                Integer.class, "tenant-a", command.commandId()));
    }

    private JsonNode post(String path, Object body) throws Exception { return successful(rawPost(path, body)); }
    private JsonNode post(String path, Object body, String key) throws Exception {
        return successful(rawPost(path, body, key));
    }
    private JsonNode get(String path) throws Exception {
        return successful(client.send(base(path).GET().build(), HttpResponse.BodyHandlers.ofString()));
    }
    private JsonNode put(String path, Object body) throws Exception {
        return successful(client.send(base(path).header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build(),
                HttpResponse.BodyHandlers.ofString()));
    }
    private HttpResponse<String> rawPost(String path, Object body) throws Exception {
        return rawPost(path, body, "engagement-test-" + UUID.randomUUID());
    }
    private HttpResponse<String> rawPost(String path, Object body, String key) throws Exception {
        return client.send(base(path).header("Content-Type", "application/json")
                .header("Idempotency-Key", key)
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build(),
                HttpResponse.BodyHandlers.ofString());
    }
    private JsonNode successful(HttpResponse<String> response) throws Exception {
        assertTrue(response.statusCode() >= 200 && response.statusCode() < 300,
                () -> response.statusCode() + ": " + response.body());
        return mapper.readTree(response.body());
    }
    private HttpRequest.Builder base(String path) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).timeout(Duration.ofSeconds(30))
                .header("X-Dev-Tenant-Id", "tenant-a").header("X-Dev-Actor-Id", "engagement-test")
                .header("X-Dev-Permissions", "*");
    }
}
