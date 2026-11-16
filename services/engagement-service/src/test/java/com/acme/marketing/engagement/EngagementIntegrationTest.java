package com.acme.marketing.engagement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.acme.marketing.contracts.event.JourneyEffectCommand;
import com.acme.marketing.engagement.application.EngagementService;
import com.acme.marketing.testsupport.MySqlIntegrationTest;
import com.acme.marketing.provider.ProviderConnector;
import com.acme.marketing.provider.ProviderResult;
import com.acme.marketing.provider.SandboxProviderConnector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class EngagementIntegrationTest extends MySqlIntegrationTest {
    @LocalServerPort private int port;
    @Autowired private ObjectMapper mapper;
    @Autowired private EngagementService service;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private ProviderConnector provider;
    private final HttpClient client = HttpClient.newHttpClient();

    @BeforeEach
    void useSandboxProvider() {
        SandboxProviderConnector sandbox = new SandboxProviderConnector(Clock.systemUTC());
        when(provider.send(any(), any())).thenAnswer(call -> sandbox.send(call.getArgument(0), call.getArgument(1)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"consent", "minor", "personalization", "suppression", "quiet", "template"})
    void retryRechecksCurrentGuardsBeforeCallingProvider(String changedGuard) throws Exception {
        String suffix = "retry-" + changedGuard;
        Instant now = Instant.now();
        Map<String, Object> consent = new java.util.LinkedHashMap<>(Map.of(
                "subjectToken", suffix, "channel", "SMS", "allowed", true, "minor", false,
                "personalizationAllowed", true, "source", "retry-test", "effectiveAt", now.minusSeconds(10)));
        put("/api/v1/consents", consent);
        Map<String, Object> policy = new java.util.LinkedHashMap<>(Map.of("campaignId", suffix, "channel", "SMS",
                "windowSeconds", 86400, "maxContacts", 1, "quietStart", LocalTime.MIDNIGHT,
                "quietEnd", LocalTime.MIDNIGHT));
        put("/api/v1/contacts/frequency-policies", policy);
        post("/api/v1/templates", Map.of("templateId", suffix, "version", 1, "channel", "SMS",
                "content", "Hello {{name}}", "requiredVariables", Set.of("name")));
        Map<String, Object> send = Map.ofEntries(
                Map.entry("contactKey", suffix), Map.entry("subjectToken", suffix), Map.entry("campaignId", suffix),
                Map.entry("channel", "SMS"), Map.entry("recipientToken", "phone-token"),
                Map.entry("templateId", suffix), Map.entry("templateVersion", 1),
                Map.entry("variables", Map.of("name", "Ada")), Map.entry("timezone", "UTC"),
                Map.entry("personalized", true), Map.entry("requestedAt", now.minusSeconds(43200)));
        doReturn(new ProviderResult(ProviderResult.Status.RETRY_EXHAUSTED,
                suffix, "RETRY_EXHAUSTED", 1, now, Map.of())).when(provider).send(any(), any());
        JsonNode first = post("/api/v1/contacts", send);
        assertEquals("RETRY_PENDING", first.get("state").asString());

        switch (changedGuard) {
            case "consent" -> { consent.put("allowed", false); put("/api/v1/consents", consent); }
            case "minor" -> { consent.put("minor", true); put("/api/v1/consents", consent); }
            case "personalization" -> { consent.put("personalizationAllowed", false); put("/api/v1/consents", consent); }
            case "suppression" -> put("/api/v1/consents/suppressions", Map.of("subjectToken", suffix, "channel", "SMS",
                    "reason", "withdrawn", "expiresAt", now.plusSeconds(3600)));
            case "quiet" -> {
                // 固定九位小数，避免本机微秒时钟掩盖Linux CI暴露的列长度问题。
                LocalTime current = LocalTime.now(java.time.ZoneOffset.UTC).withNano(123456789);
                policy.put("quietStart", current.minusMinutes(5));
                policy.put("quietEnd", current.plusMinutes(5));
                put("/api/v1/contacts/frequency-policies", policy);
                assertEquals(current.minusMinutes(5).toString(), jdbc.queryForObject(
                        "select quiet_start from mk_frequency_policy where tenant_id=? and campaign_id=? and channel_name=?",
                        String.class, "tenant-a", suffix, "SMS"));
            }
            case "template" -> jdbc.update("update mk_template_version set state_name='REVOKED' "
                    + "where tenant_id=? and template_id=?", "tenant-a", suffix);
            default -> throw new AssertionError(changedGuard);
        }
        HttpResponse<String> blocked = rawPost("/api/v1/contacts", send);
        assertEquals(409, blocked.statusCode(), blocked.body());
        String code = switch (changedGuard) {
            case "consent" -> "CONSENT_REQUIRED";
            case "minor" -> "MINOR_PERSONALIZATION_BLOCKED";
            case "personalization" -> "PERSONALIZATION_OPT_OUT";
            case "suppression" -> "SUBJECT_SUPPRESSED";
            case "quiet" -> "QUIET_HOURS";
            case "template" -> "TEMPLATE_VARIABLES_INVALID";
            default -> throw new AssertionError(changedGuard);
        };
        assertTrue(blocked.body().contains(code), blocked.body());
        verify(provider, times(1)).send(any(), any());

        // 恢复策略后同一触达可以继续；原频控上限为 1，重复扣减会在此失败。
        consent.put("allowed", true); consent.put("minor", false); consent.put("personalizationAllowed", true);
        put("/api/v1/consents", consent);
        put("/api/v1/consents/suppressions", Map.of("subjectToken", suffix, "channel", "SMS", "reason", "expired",
                "expiresAt", now.minusSeconds(1)));
        policy.put("quietStart", LocalTime.MIDNIGHT); policy.put("quietEnd", LocalTime.MIDNIGHT);
        put("/api/v1/contacts/frequency-policies", policy);
        jdbc.update("update mk_template_version set state_name='APPROVED' where tenant_id=? and template_id=?",
                "tenant-a", suffix);
        doReturn(new ProviderResult(ProviderResult.Status.ACCEPTED,
                suffix, "HTTP_202", 1, Instant.now(), Map.of())).when(provider).send(any(), any());
        JsonNode accepted = post("/api/v1/contacts", send);
        assertEquals(first.get("contactId"), accepted.get("contactId"));
        assertEquals("ACCEPTED", accepted.get("state").asString());
        assertEquals(1, jdbc.queryForObject("select sum(contact_count) from mk_frequency_bucket "
                + "where tenant_id=? and campaign_id=?", Integer.class, "tenant-a", suffix));
        consent.put("allowed", false);
        put("/api/v1/consents", consent);
        assertEquals(accepted, post("/api/v1/contacts", send));
        verify(provider, times(2)).send(any(), any());
    }

    /** 创建与更新都必须完整保留API已接受的LocalTime精度，不能只让一次更新成功。 */
    @Test
    void frequencyPolicyPreservesNanosecondsOnInsertAndUpdate() throws Exception {
        String campaign = "quiet-precision-" + UUID.randomUUID();
        Map<String, Object> policy = new java.util.LinkedHashMap<>(Map.of(
                "campaignId", campaign, "channel", "SMS", "windowSeconds", 3600,
                "maxContacts", 5, "quietStart", LocalTime.of(22, 0, 0, 123456789),
                "quietEnd", LocalTime.of(7, 0, 0, 987654321)));
        for (int attempt = 0; attempt < 2; attempt++) {
            put("/api/v1/contacts/frequency-policies", policy);
            Map<String, Object> stored = jdbc.queryForMap(
                    "select quiet_start,quiet_end from mk_frequency_policy where tenant_id=? and campaign_id=? and channel_name=?",
                    "tenant-a", campaign, "SMS");
            assertEquals(policy.get("quietStart").toString(), stored.get("quiet_start"));
            assertEquals(policy.get("quietEnd").toString(), stored.get("quiet_end"));
            policy.put("quietStart", LocalTime.MIN.withNano(999999999));
            policy.put("quietEnd", LocalTime.MAX);
        }
    }

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
