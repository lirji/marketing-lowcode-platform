package com.acme.marketing.benefit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.acme.marketing.benefit.application.BenefitFundingService;
import com.acme.marketing.contracts.event.JourneyEffectCommand;
import com.acme.marketing.contracts.offer.FundingShareClaim;
import com.acme.marketing.contracts.offer.OfferLineClaim;
import com.acme.marketing.contracts.offer.OfferTokenClaims;
import com.acme.marketing.contracts.offer.OfferTokenCodec;
import com.acme.marketing.platform.crypto.Ed25519;
import com.acme.marketing.platform.identity.TenantId;
import com.acme.marketing.testsupport.MySqlIntegrationTest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyPair;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BenefitFundingIntegrationTest extends MySqlIntegrationTest {
    private static final KeyPair OFFER_KEYS = Ed25519.generateKeyPair();
    private static final String CART_DIGEST = "sha256:" + "c".repeat(64);
    @LocalServerPort private int port;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private BenefitFundingService service;
    private final HttpClient client = HttpClient.newHttpClient();

    private static Map<String, Integer> writerEpochs(int inventoryEpoch) {
        return Map.of("INVENTORY:benefit-v1", inventoryEpoch,
                "BUDGET:PLATFORM:platform:CNY", 1,
                "BUDGET:MERCHANT:shop-1:CNY", 1);
    }

    @DynamicPropertySource
    static void offerTrust(DynamicPropertyRegistry registry) {
        registry.add("marketing.offer.trusted-key-id", () -> "offer-test-key");
        registry.add("marketing.offer.public-key-base64",
                () -> Base64.getEncoder().encodeToString(OFFER_KEYS.getPublic().getEncoded()));
    }

    @Test
    void reserveConfirmRefundReverseAndReplayConserveAllResources() throws Exception {
        createAccount("INVENTORY:benefit-v1", "INVENTORY", "UNIT", 10);
        createAccount("BUDGET:PLATFORM:platform:CNY", "BUDGET", "CNY", 10_000);
        createAccount("BUDGET:MERCHANT:shop-1:CNY", "BUDGET", "CNY", 10_000);
        assertTrue(get("/api/v1/funding/accounts").size() >= 3);

        JsonNode benefit = put("/api/v1/benefits/coupon-80", Map.of(
                "name", "Coupon 80", "status", "DRAFT", "resourceKey", "INVENTORY:benefit-v1",
                "policy", Map.of("discountMinor", 8000)), "benefit-command-001");
        JsonNode benefitReplay = put("/api/v1/benefits/coupon-80", Map.of(
                "name", "Coupon 80", "status", "DRAFT", "resourceKey", "INVENTORY:benefit-v1",
                "policy", Map.of("discountMinor", 8000)), "benefit-command-001");
        assertEquals(benefit.get("version").asLong(), benefitReplay.get("version").asLong());
        assertEquals("coupon-80", get("/api/v1/benefits/coupon-80").get("benefitId").asString());
        assertTrue(get("/api/v1/benefits").isArray());

        String token = token();
        Map<String, Object> reserve = Map.of("offerToken", token, "cartDigest", CART_DIGEST,
                "orderId", "order-1", "expectedFencingEpochs", writerEpochs(1));
        JsonNode first = post("/api/v1/promotion-applications", reserve, "reserve-command-001");
        JsonNode duplicate = post("/api/v1/promotion-applications", reserve, "reserve-command-001");
        String applicationId = first.get("applicationId").asString();
        assertEquals(applicationId, duplicate.get("applicationId").asString());
        assertEquals("RESERVED", first.get("state").asString());

        post("/api/v1/funding/accounts/INVENTORY:benefit-v1:advance-fence",
                Map.of("expectedEpoch", 1, "state", "ACTIVE"), null);

        JsonNode confirmed = post("/api/v1/promotion-applications/" + applicationId + ":confirm",
                Map.of("expectedFencingEpochs", writerEpochs(2)), "confirm-command-001");
        assertEquals("CONFIRMED", confirmed.get("state").asString());
        JsonNode partial = post("/api/v1/promotion-applications/" + applicationId + ":refund",
                Map.of("resourceAmounts", Map.of("BUDGET:PLATFORM:platform:CNY", 200),
                        "expectedFencingEpochs", writerEpochs(2)), "refund-command-001");
        assertEquals("PARTIALLY_REFUNDED", partial.get("state").asString());
        JsonNode reversed = post("/api/v1/promotion-applications/" + applicationId + ":reverse",
                Map.of("expectedFencingEpochs", writerEpochs(2)), "reverse-command-001");
        assertEquals("REVERSED", reversed.get("state").asString());

        JsonNode reconciliation = get("/api/v1/funding/reconciliation");
        assertTrue(reconciliation.get("balanced").asBoolean());
        assertEquals(0, reconciliation.get("violations").size());

        jdbc.update("delete from mk_benefit_outbox where tenant_id=? and aggregate_id=? and event_type=?",
                "tenant-a", applicationId, "PromotionREVERSED");
        JsonNode tampered = get("/api/v1/funding/reconciliation");
        assertEquals(false, tampered.get("balanced").asBoolean());
        assertTrue(tampered.get("violations").toString().contains("OUTBOX_INCOMPLETE"));
    }

    @Test
    void journeyGrantConsumesInventoryExactlyOnce() throws Exception {
        createAccount("INVENTORY:journey-grant-v1", "INVENTORY", "UNIT", 5);
        JourneyEffectCommand command = new JourneyEffectCommand("JOURNEY_EFFECT_COMMAND", "tenant-a",
                "enrollment-grant-1", "subject-grant-1", "journey-grant", 1,
                "journey-grant-command-001", "GRANT", "grant-node",
                Map.of("resourceKey", "INVENTORY:journey-grant-v1", "benefitId", "coupon-5", "quantity", "2"),
                Instant.now().toEpochMilli());
        BenefitFundingService.JourneyGrantView first = service.grantFromJourney(command);
        BenefitFundingService.JourneyGrantView replay = service.grantFromJourney(command);

        assertEquals(first, replay);
        assertEquals(3L, jdbc.queryForObject("select available_amount from mk_resource_account where tenant_id=? and resource_key=?",
                Long.class, "tenant-a", "INVENTORY:journey-grant-v1"));
        assertEquals(1, jdbc.queryForObject("select count(*) from mk_journey_benefit_grant where tenant_id=? and command_id=?",
                Integer.class, "tenant-a", command.commandId()));
    }

    private void createAccount(String key, String type, String currency, long authorized) throws Exception {
        post("/api/v1/funding/accounts", Map.of("resourceKey", key, "type", type, "currency", currency,
                "authorized", authorized, "fencingEpoch", 1), null);
    }

    private static String token() {
        Instant now = Instant.now();
        OfferLineClaim line = new OfferLineClaim("offer-1", "benefit-v1", "CNY", 1_000, 1,
                List.of(new FundingShareClaim("PLATFORM", "platform", "CNY", 700),
                        new FundingShareClaim("MERCHANT", "shop-1", "CNY", 300)));
        OfferTokenClaims claims = new OfferTokenClaims("decision", new TenantId("tenant-a"), "org-a", "subject-1",
                "order-1", List.of("shop-1"), CART_DIGEST, "quote-1", "request-1", 1,
                List.of("artifact-1"), List.of(line),
                "terms-v1", now.minusSeconds(1), now.plusSeconds(300), "nonce-1", List.of());
        return OfferTokenCodec.encode("offer-test-key", OFFER_KEYS.getPrivate(), claims);
    }

    private JsonNode post(String path, Object body, String idempotencyKey) throws Exception {
        HttpRequest.Builder builder = base(path).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
        if (idempotencyKey != null) builder.header("Idempotency-Key", idempotencyKey);
        return exchange(builder.build());
    }
    private JsonNode get(String path) throws Exception { return exchange(base(path).GET().build()); }
    private JsonNode put(String path, Object body, String idempotencyKey) throws Exception {
        return exchange(base(path).header("Content-Type", "application/json")
                .header("Idempotency-Key", idempotencyKey)
                .PUT(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build());
    }
    private HttpRequest.Builder base(String path) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).timeout(Duration.ofSeconds(30))
                .header("X-Dev-Tenant-Id", "tenant-a").header("X-Dev-Actor-Id", "benefit-test")
                .header("X-Dev-Organization-Ids", "org-a").header("X-Dev-Shop-Ids", "shop-1")
                .header("X-Dev-Permissions", "*");
    }
    private JsonNode exchange(HttpRequest request) throws Exception {
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertTrue(response.statusCode() >= 200 && response.statusCode() < 300,
                () -> response.statusCode() + ": " + response.body());
        return mapper.readTree(response.body());
    }
}
