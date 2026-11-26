package com.acme.marketing.engagement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.acme.marketing.contracts.event.JourneyEffectCommand;
import com.acme.marketing.engagement.application.EngagementService;
import com.acme.marketing.engagement.application.EngagementRepository;
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
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class EngagementIntegrationTest extends MySqlIntegrationTest {
    @LocalServerPort private int port;
    @Autowired private ObjectMapper mapper;
    @Autowired private EngagementService service;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private ProviderConnector provider;
    @MockitoBean private Clock clock;
    @MockitoSpyBean private EngagementRepository repository;
    private final HttpClient client = HttpClient.newHttpClient();

    @BeforeEach
    void useSandboxProvider() {
        when(clock.instant()).thenReturn(Instant.now());
        when(clock.getZone()).thenReturn(java.time.ZoneOffset.UTC);
        SandboxProviderConnector sandbox = new SandboxProviderConnector(Clock.systemUTC());
        when(provider.send(any(), any())).thenAnswer(call -> sandbox.send(call.getArgument(0), call.getArgument(1)));
    }

    /** 幂等身份必须在许可判断之前校验，变更任一业务目标都不能借原触达的频控名额重发。 */
    @ParameterizedTest
    @ValueSource(strings = {"subjectToken", "campaignId", "channel", "recipientToken", "templateId",
            "templateVersion", "variables", "timezone", "personalized", "requestedAt"})
    void contactKeyCannotBeReusedForChangedPayload(String changedField) throws Exception {
        Map<String, Object> send = contactFixture();
        retryProvider(send);
        post("/api/v1/contacts", send);
        Map<String, Object> changed = new java.util.LinkedHashMap<>(send);
        Object replacement = switch (changedField) {
            case "templateVersion" -> 2;
            case "variables" -> Map.of("name", "Another recipient");
            case "timezone" -> "Asia/Shanghai";
            case "personalized" -> true;
            case "requestedAt" -> clock.instant().plusSeconds(60);
            default -> "different-" + send.get(changedField);
        };
        changed.put(changedField, replacement);
        HttpResponse<String> conflict = rawPost("/api/v1/contacts", changed);
        assertEquals(409, conflict.statusCode(), conflict.body());
        assertTrue(conflict.body().contains("IDEMPOTENCY_PAYLOAD_CONFLICT"), conflict.body());
        verify(provider, times(1)).send(any(), any());
        assertEquals(1, jdbc.queryForObject("select sum(contact_count) from mk_frequency_bucket "
                + "where tenant_id=? and campaign_id=?", Integer.class, "tenant-a", send.get("campaignId")));
    }

    @Test
    void nestedVariableOrderDoesNotChangeIdentityAndTerminalStillChecksIt() throws Exception {
        Map<String, Object> send = contactFixture();
        send.put("variables", Map.of("name", "Ada", "nested", Map.of("a", 1, "b", 2)));
        retryProvider(send);
        JsonNode first = post("/api/v1/contacts", send);
        var nested = new java.util.LinkedHashMap<String, Object>();
        nested.put("b", 2); nested.put("a", 1);
        var reordered = new java.util.LinkedHashMap<String, Object>();
        reordered.put("nested", nested); reordered.put("name", "Ada");
        send.put("variables", reordered);
        doReturn(new ProviderResult(ProviderResult.Status.ACCEPTED,
                "canonical-provider", "HTTP_202", 1, clock.instant(), Map.of())).when(provider).send(any(), any());
        JsonNode accepted = post("/api/v1/contacts", send);
        assertEquals(first.get("contactId"), accepted.get("contactId"));
        String contactNo = accepted.path("businessNo").asString();
        assertTrue(contactNo.matches("MKT-CNT-[0-9]{6}-[0-9]{10}"));
        assertEquals(first.path("businessNo").asString(), contactNo);
        assertEquals(accepted, get("/api/v1/contacts/" + contactNo));
        assertEquals(accepted, get("/api/v1/contacts/" + accepted.path("contactId").asString()));
        assertEquals("ACCEPTED", accepted.get("state").asString());
        assertEquals(accepted, post("/api/v1/contacts", send));
        send.put("recipientToken", "changed-recipient");
        HttpResponse<String> conflict = rawPost("/api/v1/contacts", send);
        assertEquals(409, conflict.statusCode(), conflict.body());
        assertTrue(conflict.body().contains("IDEMPOTENCY_PAYLOAD_CONFLICT"));
        verify(provider, times(2)).send(any(), any());
    }

    @Test
    void legacyAttemptWithoutFingerprintCannotAcquireAnArbitraryNewRecipient() throws Exception {
        Map<String, Object> send = contactFixture();
        retryProvider(send);
        JsonNode first = post("/api/v1/contacts", send);
        jdbc.update("update mk_contact_attempt set request_fingerprint=null where tenant_id=? and contact_id=?",
                "tenant-a", first.get("contactId").asString());
        send.put("recipientToken", "not-provable-historical-recipient");
        HttpResponse<String> blocked = rawPost("/api/v1/contacts", send);
        assertEquals(409, blocked.statusCode(), blocked.body());
        assertTrue(blocked.body().contains("CONTACT_RETRY_UNVERIFIABLE"));
        assertEquals("RETRY_PENDING", get("/api/v1/contacts/" + send.get("contactKey")).get("state").asString());
        // 历史终态只读重放，不会使用无法核实的请求调用渠道。
        jdbc.update("update mk_contact_attempt set state_name='DELIVERED' where tenant_id=? and contact_id=?",
                "tenant-a", first.get("contactId").asString());
        assertEquals("DELIVERED", post("/api/v1/contacts", send).get("state").asString());
        verify(provider, times(1)).send(any(), any());
    }

    @Test
    void callerTimestampsCannotMoveFrequencyConsumptionOutsideTheCurrentWindow() throws Exception {
        Map<String, Object> send = contactFixture();
        send.put("requestedAt", clock.instant().minusSeconds(172800));
        post("/api/v1/contacts", send);
        long bucket = Math.floorDiv(clock.instant().getEpochSecond(), 3600);
        assertEquals(bucket, jdbc.queryForObject("select window_bucket from mk_frequency_bucket "
                + "where tenant_id=? and campaign_id=?", Long.class, "tenant-a", send.get("campaignId")));
        send.put("contactKey", UUID.randomUUID().toString());
        send.put("requestedAt", clock.instant().plusSeconds(172800));
        HttpResponse<String> capped = rawPost("/api/v1/contacts", send);
        assertEquals(409, capped.statusCode(), capped.body());
        assertTrue(capped.body().contains("FREQUENCY_CAP_REACHED"));
        assertEquals(0, jdbc.queryForObject("select count(*) from mk_contact_attempt where tenant_id=? and contact_key=?",
                Integer.class, "tenant-a", send.get("contactKey")));
        assertEquals(1, jdbc.queryForObject("select contact_count from mk_frequency_bucket where tenant_id=? and campaign_id=?",
                Integer.class, "tenant-a", send.get("campaignId")));
        verify(provider, times(1)).send(any(), any());
    }

    @Test
    void providerResponseArrivingAfterDeliveredCallbackCannotRegressOrCreateDlq() throws Exception {
        Map<String, Object> send = contactFixture();
        retryProvider(send);
        JsonNode first = post("/api/v1/contacts", send);
        doAnswer(invocation -> {
            post("/api/v1/providers/callbacks", Map.of("providerEventId", UUID.randomUUID().toString(),
                    "providerRequestId", first.get("providerRequestId").asString(), "status", "DELIVERED",
                    "occurredAt", clock.instant(), "attributes", Map.of()));
            return new ProviderResult(ProviderResult.Status.PERMANENT_FAILURE,
                    first.get("providerRequestId").asString(), "LATE_FAILURE", 1, clock.instant(), Map.of());
        }).when(provider).send(any(), any());
        assertEquals("DELIVERED", post("/api/v1/contacts", send).get("state").asString());
        assertEquals(0, jdbc.queryForObject("select count(*) from mk_contact_dlq where tenant_id=? and contact_id=?",
                Integer.class, "tenant-a", first.get("contactId").asString()));
        assertEquals(0, jdbc.queryForObject("select count(*) from mk_engagement_outbox "
                + "where tenant_id=? and contact_id=? and event_type='ContactFAILED'",
                Integer.class, "tenant-a", first.get("contactId").asString()));
    }

    /** 两个HTTP事务都先读到不存在，再竞争真实唯一键，覆盖不能依赖普通顺序重放证明的竞态。 */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void concurrentFirstRequestsShareOneClaimOrRejectChangedPayload(boolean changedPayload) throws Exception {
        Map<String, Object> send = contactFixture();
        Map<String, Object> second = new java.util.LinkedHashMap<>(send);
        if (changedPayload) second.put("recipientToken", "another-recipient");
        CyclicBarrier initialReads = new CyclicBarrier(2);
        AtomicInteger reads = new AtomicInteger();
        doAnswer(call -> {
            Object found = call.callRealMethod();
            if (reads.incrementAndGet() <= 2) initialReads.await(10, TimeUnit.SECONDS);
            return found;
        }).when(repository).findContactByKey("tenant-a", send.get("contactKey").toString());
        try (var executor = Executors.newFixedThreadPool(2)) {
            var firstCall = executor.submit(() -> rawPost("/api/v1/contacts", send));
            var secondCall = executor.submit(() -> rawPost("/api/v1/contacts", second));
            HttpResponse<String> first = firstCall.get(30, TimeUnit.SECONDS);
            HttpResponse<String> other = secondCall.get(30, TimeUnit.SECONDS);
            if (changedPayload) {
                HttpResponse<String> accepted = first.statusCode() == 202 ? first : other;
                HttpResponse<String> conflict = first.statusCode() == 409 ? first : other;
                assertEquals(202, accepted.statusCode(), accepted.body());
                assertEquals(409, conflict.statusCode(), conflict.body());
                assertTrue(conflict.body().contains("IDEMPOTENCY_PAYLOAD_CONFLICT"));
            } else {
                assertEquals(202, first.statusCode(), first.body());
                assertEquals(202, other.statusCode(), other.body());
                assertEquals(mapper.readTree(first.body()).get("contactId"),
                        mapper.readTree(other.body()).get("contactId"));
            }
        }
        verify(provider, times(1)).send(any(), any());
        assertEquals(1, jdbc.queryForObject("select count(*) from mk_contact_attempt where tenant_id=? and contact_key=?",
                Integer.class, "tenant-a", send.get("contactKey")));
        assertEquals(1, jdbc.queryForObject("select contact_count from mk_frequency_bucket where tenant_id=? and campaign_id=?",
                Integer.class, "tenant-a", send.get("campaignId")));
    }

    /** 每个用例使用独立业务范围，保留真实MySQL约束与HTTP边界。 */
    private Map<String, Object> contactFixture() throws Exception {
        String id = "identity-" + UUID.randomUUID();
        put("/api/v1/consents", Map.of("subjectToken", id, "channel", "SMS", "allowed", true,
                "minor", false, "personalizationAllowed", true, "source", "identity-test",
                "effectiveAt", clock.instant().minusSeconds(10)));
        put("/api/v1/contacts/frequency-policies", Map.of("campaignId", id, "channel", "SMS",
                "windowSeconds", 3600, "maxContacts", 1, "quietStart", LocalTime.MIDNIGHT,
                "quietEnd", LocalTime.MIDNIGHT));
        post("/api/v1/templates", Map.of("templateId", id, "version", 1, "channel", "SMS",
                "content", "Hello {{name}}", "requiredVariables", Set.of("name")));
        return new java.util.LinkedHashMap<>(Map.ofEntries(
                Map.entry("contactKey", id), Map.entry("subjectToken", id), Map.entry("campaignId", id),
                Map.entry("channel", "SMS"), Map.entry("recipientToken", "phone-token"),
                Map.entry("templateId", id), Map.entry("templateVersion", 1),
                Map.entry("variables", Map.of("name", "Ada")), Map.entry("timezone", "UTC"),
                Map.entry("personalized", false), Map.entry("requestedAt", clock.instant())));
    }

    private void retryProvider(Map<String, Object> request) {
        doReturn(new ProviderResult(ProviderResult.Status.RETRY_EXHAUSTED,
                request.get("contactKey").toString(), "RETRY_EXHAUSTED", 1, clock.instant(), Map.of()))
                .when(provider).send(any(), any());
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

    @Test
    void templateVersionsStayImmutableUnderConcurrentCreationAndRetry() throws Exception {
        String id = "version-test-" + UUID.randomUUID();
        Map<String, Object> first = Map.of("templateId", id, "version", 1, "channel", "SMS",
                "content", "Original {{name}}", "requiredVariables", Set.of("name"));
        String key = "template-" + UUID.randomUUID();
        JsonNode created = post("/api/v1/templates", first, key);
        assertEquals(created, post("/api/v1/templates", first, key));
        Map<String, Object> next = new java.util.LinkedHashMap<>(first);
        next.put("version", 2); next.put("content", "New {{name}}");
        CyclicBarrier gate = new CyclicBarrier(2);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var a = workers.submit(() -> { gate.await(); return rawPost("/api/v1/templates", next); });
            var b = workers.submit(() -> { gate.await(); return rawPost("/api/v1/templates", next); });
            var results = java.util.List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS));
            assertEquals(Set.of(201, 409), results.stream().map(HttpResponse::statusCode).collect(java.util.stream.Collectors.toSet()));
            assertTrue(results.stream().filter(response -> response.statusCode() == 409)
                    .allMatch(response -> response.body().contains("TEMPLATE_VERSION_EXISTS")));
        }
        assertEquals("Original {{name}}", jdbc.queryForObject("select content_text from mk_template_version where tenant_id=? and template_id=? and version_no=1", String.class, "tenant-a", id));
        assertEquals(2, jdbc.queryForObject("select count(*) from mk_template_version where tenant_id=? and template_id=?", Integer.class, "tenant-a", id));
        JsonNode latest = get("/api/v1/templates");
        assertTrue(latest.findValues("templateId").stream().anyMatch(value -> value.asString().equals(id)));
        for (JsonNode item : latest) if (id.equals(item.path("templateId").asString())) assertEquals(2, item.path("version").asInt());
    }

    @Test
    void templateReplayRechecksWritePermissionAndTenantScope() throws Exception {
        String id = "scope-test-" + UUID.randomUUID(), key = "template-" + UUID.randomUUID();
        Map<String, Object> body = Map.of("templateId", id, "version", 1, "channel", "SMS",
                "content", "Scope-only content", "requiredVariables", Set.of());
        post("/api/v1/templates", body, key);
        var revoked = client.send(base("/api/v1/templates").setHeader("X-Dev-Permissions", "template:read")
                .header("Content-Type", "application/json").header("Idempotency-Key", key)
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(403, revoked.statusCode());
        var other = client.send(base("/api/v1/templates").setHeader("X-Dev-Tenant-Id", "tenant-template-other").GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, other.statusCode()); assertTrue(!other.body().contains(id));
        var otherWrite = client.send(base("/api/v1/templates").setHeader("X-Dev-Tenant-Id", "tenant-template-other")
                .header("Content-Type", "application/json").header("Idempotency-Key", key)
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(201, otherWrite.statusCode());
        assertEquals(2, jdbc.queryForObject("select count(*) from mk_template_version where template_id=?", Integer.class, id));
    }

    @Test
    void templateInputBoundsAndEmailNoticeAreEnforcedBeforePersistence() throws Exception {
        String id = "invalid-test-" + UUID.randomUUID();
        Map<String, Object> valid = Map.of("templateId", id, "version", 1, "channel", "SMS", "content", "Message", "requiredVariables", Set.of());
        var badFields = java.util.List.of(Map.entry("templateId", (Object) " "), Map.entry("templateId", (Object) "x".repeat(129)),
                Map.entry("channel", (Object) " SMS"), Map.entry("content", (Object) " "), Map.entry("content", (Object) "x".repeat(8001)),
                Map.entry("version", (Object) 0), Map.entry("version", (Object) 9_007_199_254_740_992L),
                Map.entry("requiredVariables", (Object) java.util.List.of("name,other")),
                Map.entry("requiredVariables", (Object) java.util.stream.IntStream.range(0, 33).mapToObj(value -> "v" + value).toList()));
        for (var field : badFields) {
            var body = new java.util.LinkedHashMap<>(valid); body.put(field.getKey(), field.getValue());
            var response = rawPost("/api/v1/templates", body);
            assertEquals(400, response.statusCode(), response.body());
            assertTrue(response.body().contains("TEMPLATE_INPUT_INVALID"), response.body());
        }
        var missing = new java.util.LinkedHashMap<>(valid); missing.put("requiredVariables", null);
        assertEquals(400, rawPost("/api/v1/templates", missing).statusCode());
        var email = new java.util.LinkedHashMap<>(valid); email.put("channel", "EMAIL");
        var response = rawPost("/api/v1/templates", email); assertEquals(409, response.statusCode());
        assertTrue(response.body().contains("UNSUBSCRIBE_NOTICE_REQUIRED"));
        assertEquals(0, jdbc.queryForObject("select count(*) from mk_template_version where tenant_id=? and template_id=?", Integer.class, "tenant-a", id));
        email.put("content", "Message unsubscribe"); assertEquals(201, rawPost("/api/v1/templates", email).statusCode());
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
