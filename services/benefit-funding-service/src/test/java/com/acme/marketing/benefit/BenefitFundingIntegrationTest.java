package com.acme.marketing.benefit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.acme.marketing.benefit.application.BenefitFundingService;
import com.acme.marketing.benefit.application.BenefitSkuCatalog;
import com.acme.marketing.benefit.application.BenefitSkuCatalog.SkuStatus;
import com.acme.marketing.benefit.infrastructure.AwardIntentRelay;
import com.acme.marketing.contracts.event.JourneyEffectCommand;
import com.acme.marketing.contracts.offer.FundingShareClaim;
import com.acme.marketing.contracts.offer.OfferLineClaim;
import com.acme.marketing.contracts.offer.OfferTokenClaims;
import com.acme.marketing.contracts.offer.OfferTokenCodec;
import com.acme.marketing.platform.crypto.Digests;
import com.acme.marketing.platform.crypto.Ed25519;
import com.acme.marketing.platform.identity.TenantId;
import com.acme.marketing.testsupport.MySqlIntegrationTest;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyPair;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
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
    private static final Instant BEIJING_MIDNIGHT = Instant.parse("2026-09-16T16:00:00Z");
    private static final AtomicReference<Instant> POSTING_TIME = new AtomicReference<>();
    private static final AtomicInteger BENEFIT_SKU_REQUESTS = new AtomicInteger();
    private static final AtomicInteger BENEFIT_CATALOG_STATUS = new AtomicInteger(200);
    private static final AtomicBoolean SKU_ACTIVE = new AtomicBoolean(true);
    private static final AtomicInteger SKU_VERSION = new AtomicInteger(3);
    private static final AtomicBoolean ENFORCE_SKU_VERSION = new AtomicBoolean(false);
    private static final AtomicReference<String> FORWARDED_TENANT = new AtomicReference<>();
    private static final AtomicReference<String> BENEFIT_AUTHORIZATION = new AtomicReference<>();
    private static final AtomicInteger AWARD_REQUESTS = new AtomicInteger();
    private static final AtomicReference<String> AWARD_IDEMPOTENCY_KEY = new AtomicReference<>();
    private static final AtomicReference<String> AWARD_TENANT = new AtomicReference<>();
    private static final AtomicReference<String> AWARD_PAYLOAD = new AtomicReference<>();
    private static final AtomicInteger AWARD_RESPONSE_STATUS = new AtomicInteger(202);
    private static final ConcurrentHashMap<String, AtomicInteger> RISK_REQUESTS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, String> RISK_PAYLOADS = new ConcurrentHashMap<>();
    private static final AtomicReference<String> RISK_AUTHORIZATION = new AtomicReference<>();
    private static final AtomicReference<String> RISK_TENANT = new AtomicReference<>();
    private static final AtomicReference<String> RISK_TRACEPARENT = new AtomicReference<>();
    private static final AtomicReference<String> SKU_BENEFIT_TYPE = new AtomicReference<>("CASH");
    private static final Pattern TXN_ID = Pattern.compile("\\\"txnId\\\":\\\"([^\\\"]+)\\\"");
    private static final ExecutorService RISK_SERVER_EXECUTOR = Executors.newCachedThreadPool();
    private static final HttpServer BENEFIT_CENTER = startBenefitCenter();
    private static final HttpServer RISK_PLATFORM = startRiskPlatform();
    @LocalServerPort private int port;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private BenefitFundingService service;
    @Autowired private BenefitSkuCatalog benefitSkuCatalog;
    @Autowired private AwardIntentRelay awardIntentRelay;
    private final HttpClient client = HttpClient.newHttpClient();

    /** 默认使用真实时钟，仅账本验证固定在北京时间零点两侧入账。 */
    @TestConfiguration
    static class PostingTimeConfiguration {
        @Bean @Primary Clock testClock() {
            InstantSource source = () -> {
                Instant fixed = POSTING_TIME.get();
                return fixed == null ? Instant.now() : fixed;
            };
            return source.withZone(ZoneOffset.UTC);
        }
    }

    @AfterEach void useSystemTime() { POSTING_TIME.set(null); }

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
        registry.add("marketing.benefit-center.base-url",
                () -> "http://127.0.0.1:" + BENEFIT_CENTER.getAddress().getPort());
        registry.add("marketing.benefit-center.bearer-token", () -> "benefit-test-token");
        registry.add("marketing.risk.base-url",
                () -> "http://127.0.0.1:" + RISK_PLATFORM.getAddress().getPort());
        registry.add("marketing.risk.bearer-token", () -> "risk-test-token");
        registry.add("marketing.risk.request-timeout-ms", () -> "100");
        registry.add("marketing.risk.circuit-failure-threshold", () -> "100");
        registry.add("marketing.award.tenant-modes",
                () -> "tenant-a=CENTER,tenant-shadow=SHADOW,tenant-coupon=SHADOW");
        registry.add("marketing.award.relay-enabled", () -> "true");
        registry.add("marketing.award.relay-initial-delay-ms", () -> "3600000");
    }

    @AfterAll
    static void stopBenefitCenter() {
        BENEFIT_CENTER.stop(0);
        RISK_PLATFORM.stop(0);
        RISK_SERVER_EXECUTOR.shutdownNow();
    }

    @Test
    void listsOnlyActiveEnabledSkusAndPersistsValidatedBinding() throws Exception {
        SKU_ACTIVE.set(true);
        BENEFIT_SKU_REQUESTS.set(0);
        JsonNode first = get("/api/v1/benefit-skus?status=ACTIVE");
        JsonNode cached = get("/api/v1/benefit-skus?status=ACTIVE");
        assertEquals(1, first.size());
        assertEquals("sku-active", first.get(0).get("skuId").asString());
        assertEquals(first, cached);
        assertEquals("tenant-a", FORWARDED_TENANT.get(),
                "service token authenticates the caller while the verified marketing tenant scopes the catalog");
        assertEquals("Bearer benefit-test-token", BENEFIT_AUTHORIZATION.get());
        assertEquals(1, BENEFIT_SKU_REQUESTS.get(), "second list request should use tenant-scoped L1 cache");

        benefitSkuCatalog.list("tenant-b", SkuStatus.ACTIVE);
        assertEquals("tenant-b", FORWARDED_TENANT.get());
        assertEquals(2, BENEFIT_SKU_REQUESTS.get(), "another tenant must not reuse tenant-a cache data");
        get("/api/v1/benefit-skus?status=ACTIVE");
        assertEquals(2, BENEFIT_SKU_REQUESTS.get(), "tenant-a cache should remain isolated and reusable");

        JsonNode stored = put("/api/v1/benefits/coupon-active", Map.of(
                "name", "Active coupon", "status", "ACTIVE", "resourceKey", "",
                "benefitSkuId", "sku-active", "policy", Map.of("type", "CASH")),
                "benefit-command-active-001");
        assertEquals("sku-active", stored.get("benefitSkuId").asString());
        assertEquals("sku-active", get("/api/v1/benefits/coupon-active").get("benefitSkuId").asString());
        assertTrue(BENEFIT_SKU_REQUESTS.get() >= 2, "ACTIVE publish must bypass the list cache");

        SKU_ACTIVE.set(false);
        int requestsBeforeReplay = BENEFIT_SKU_REQUESTS.get();
        JsonNode replay = put("/api/v1/benefits/coupon-active", Map.of(
                "name", "Active coupon", "status", "ACTIVE", "resourceKey", "",
                "benefitSkuId", "sku-active", "policy", Map.of("type", "CASH")),
                "benefit-command-active-001");
        assertEquals(stored, replay);
        assertEquals(requestsBeforeReplay, BENEFIT_SKU_REQUESTS.get(),
                "completed idempotent replay must not revalidate a subsequently paused SKU");

        HttpRequest invalid = base("/api/v1/benefits/coupon-invalid")
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", "benefit-command-invalid-001")
                .PUT(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(Map.of(
                        "name", "Invalid coupon", "status", "ACTIVE", "resourceKey", "",
                        "benefitSkuId", "sku-paused", "policy", Map.of("type", "COUPON")))))
                .build();
        HttpResponse<String> rejected = client.send(invalid, HttpResponse.BodyHandlers.ofString());
        assertEquals(409, rejected.statusCode());
        assertEquals("SKU_NOT_ACTIVE", mapper.readTree(rejected.body()).get("code").asString());
        assertFalse(get("/api/v1/benefits").toString().contains("coupon-invalid"));
        SKU_ACTIVE.set(true);
    }

    @Test
    void catalogSeparatesMissingMachineAuthenticationFromTenantMismatch() throws Exception {
        BENEFIT_CATALOG_STATUS.set(401);
        try {
            HttpResponse<String> unauthorized = client.send(base(
                            "/api/v1/benefit-skus?status=ACTIVE", "tenant-catalog-auth")
                            .GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(401, unauthorized.statusCode());
            assertEquals("BENEFIT_CATALOG_AUTH_REQUIRED",
                    mapper.readTree(unauthorized.body()).get("code").asString());

            BENEFIT_CATALOG_STATUS.set(403);
            HttpResponse<String> mismatch = client.send(base(
                            "/api/v1/benefit-skus?status=ACTIVE", "tenant-catalog-mismatch")
                            .GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(403, mismatch.statusCode());
            assertEquals("BENEFIT_CATALOG_TENANT_MISMATCH",
                    mapper.readTree(mismatch.body()).get("code").asString());
        } finally {
            BENEFIT_CATALOG_STATUS.set(200);
        }
    }

    @Test
    void benefitAndAwardRejectSkuTypeMismatchWithoutSilentRewrite() throws Exception {
        SKU_ACTIVE.set(true);
        HttpResponse<String> bindingMismatch = client.send(base("/api/v1/benefits/type-mismatch")
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", "benefit-type-mismatch-001")
                .PUT(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(Map.of(
                        "name", "Mismatched draft", "status", "DRAFT", "resourceKey", "",
                        "benefitSkuId", "sku-active", "policy", Map.of("type", "COUPON")))))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(409, bindingMismatch.statusCode());
        assertEquals("BENEFIT_SKU_TYPE_MISMATCH",
                mapper.readTree(bindingMismatch.body()).get("code").asString());

        put("/api/v1/benefits/award-type-guard", Map.of(
                "name", "Type guarded cash", "status", "ACTIVE", "resourceKey", "",
                "benefitSkuId", "sku-active", "policy", Map.of("type", "CASH")),
                "benefit-type-guard-001");
        jdbc.update("update mk_benefit_definition set policy_json=? where tenant_id=? and benefit_id=?",
                "{\"type\":\"COUPON\"}", "tenant-a", "award-type-guard");
        String source = "award-type-mismatch-001";
        HttpResponse<String> awardMismatch = postRaw("/internal/v1/award-intents",
                awardCommand(source, "campaign-type-guard", 1,
                        "tenant-a", "award-type-guard@1", 500), source, "tenant-a");
        assertEquals(409, awardMismatch.statusCode());
        assertEquals("AWARD_BENEFIT_TYPE_MISMATCH",
                mapper.readTree(awardMismatch.body()).get("code").asString());
        assertEquals(0, riskRequestCount(source), "type mismatch must fail before risk and relay side effects");
    }

    @Test
    void missingFencingEpochReturnsReadableBadRequest() throws Exception {
        HttpResponse<String> response = client.send(base("/api/v1/funding/accounts")
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", "missing-fence-account-001")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(Map.of(
                        "resourceKey", "INVENTORY:missing-fence", "type", "INVENTORY",
                        "currency", "UNIT", "authorized", 10))))
                .build(), HttpResponse.BodyHandlers.ofString());

        assertEquals(400, response.statusCode());
        assertEquals("INVALID_ARGUMENT", mapper.readTree(response.body()).get("code").asString());
        assertTrue(mapper.readTree(response.body()).get("detail").asString().contains("fencingEpoch"));
    }

    @Test
    void releaseGateRejectsInactiveAndUnboundBenefitVersions() throws Exception {
        SKU_ACTIVE.set(true);
        put("/api/v1/benefits/release-active", Map.of(
                "name", "Release active", "status", "ACTIVE", "resourceKey", "",
                "benefitSkuId", "sku-active", "policy", Map.of("type", "CASH")),
                "release-active-001");
        JsonNode accepted = post("/internal/v1/benefits:assert-releasable",
                Map.of("references", List.of("release-active@1")), null);
        assertTrue(accepted.get("releasable").asBoolean());

        put("/api/v1/benefits/release-draft", Map.of(
                "name", "Release draft", "status", "DRAFT", "resourceKey", "",
                "benefitSkuId", "sku-active", "policy", Map.of("type", "CASH")),
                "release-draft-001");
        HttpResponse<String> inactive = postRaw("/internal/v1/benefits:assert-releasable",
                Map.of("references", List.of("release-draft@1")), null, "tenant-a");
        assertEquals(409, inactive.statusCode());
        assertEquals("BENEFIT_RELEASE_NOT_ACTIVE", mapper.readTree(inactive.body()).get("code").asString());

        put("/api/v1/benefits/release-unbound", Map.of(
                "name", "Release unbound", "status", "DRAFT", "resourceKey", "",
                "policy", Map.of("type", "CASH")), "release-unbound-001");
        jdbc.update("update mk_benefit_definition set status_name='ACTIVE' where tenant_id=? and benefit_id=?",
                "tenant-a", "release-unbound");
        HttpResponse<String> unbound = postRaw("/internal/v1/benefits:assert-releasable",
                Map.of("references", List.of("release-unbound@1")), null, "tenant-a");
        assertEquals(409, unbound.statusCode());
        assertEquals("BENEFIT_RELEASE_UNBOUND", mapper.readTree(unbound.body()).get("code").asString());
    }

    @Test
    void centerAwardIntentIsConcurrentIdempotentAndRelayedExactlyOnce() throws Exception {
        SKU_ACTIVE.set(true);
        put("/api/v1/benefits/award-cash", Map.of(
                "name", "Authoritative cash", "status", "ACTIVE", "resourceKey", "",
                "benefitSkuId", "sku-active", "policy", Map.of("type", "CASH")),
                "benefit-award-command-001");
        String sourceRequestId = "award-source-center-001";
        Map<String, Object> command = awardCommand(sourceRequestId, "campaign-center", 7,
                "tenant-a", "award-cash@1", 1_234);

        var pool = Executors.newFixedThreadPool(8);
        List<Callable<JsonNode>> calls = java.util.stream.IntStream.range(0, 8)
                .mapToObj(index -> (Callable<JsonNode>) () -> post(
                        "/internal/v1/award-intents", command, sourceRequestId))
                .toList();
        List<JsonNode> responses;
        try {
            responses = pool.invokeAll(calls).stream().map(future -> {
                try { return future.get(); }
                catch (Exception failure) { throw new IllegalStateException(failure); }
            }).toList();
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, responses.stream().map(value -> value.get("intentId").asString()).distinct().count());
        assertTrue(responses.stream().allMatch(value -> "CENTER".equals(value.get("deliveryMode").asString())));
        assertTrue(responses.stream().allMatch(value -> "PENDING".equals(value.get("status").asString())));
        assertEquals(1, jdbc.queryForObject("select count(*) from mk_award_intent_outbox where tenant_id=? and source_request_id=?",
                Integer.class, "tenant-a", sourceRequestId));
        assertEquals(0, jdbc.queryForObject("select count(*) from mk_award_intent_block where tenant_id=? and source_request_id=?",
                Integer.class, "tenant-a", sourceRequestId));
        assertEquals(1, jdbc.queryForObject("select count(*) from mk_award_intent_dedupe where tenant_id=? and source_request_id=?",
                Integer.class, "tenant-a", sourceRequestId));
        assertEquals(1, jdbc.queryForObject("select count(*) from mk_benefit_outbox where tenant_id=? and aggregate_id=(select intent_id from mk_award_intent_outbox where tenant_id=? and source_request_id=?) and destination_topic=?",
                Integer.class, "tenant-a", "tenant-a", sourceRequestId, "marketing.award-expected.v1"));
        assertEquals(1, jdbc.queryForObject("select count(*) from mk_award_fulfillment where tenant_id=? and intent_id=?",
                Integer.class, "tenant-a", responses.get(0).get("intentId").asString()));
        JsonNode unobserved = get("/api/v1/award-intents/" + responses.get(0).get("intentId").asString() + "/fulfillment");
        assertTrue(unobserved.get("checkedAt").isNull());
        assertFalse(unobserved.get("refreshEnabled").asBoolean());
        assertEquals(503, postRaw("/api/v1/award-intents/" + responses.get(0).get("intentId").asString()
                + "/fulfillment:refresh", Map.of(), null, "tenant-a").statusCode());
        int riskChecksAfterFirstResult = riskRequestCount(sourceRequestId);
        assertEquals(1, riskChecksAfterFirstResult,
                "the durable pre-risk claim must allow only one concurrent risk evaluation");
        post("/internal/v1/award-intents", command, sourceRequestId);
        assertEquals(riskChecksAfterFirstResult, riskRequestCount(sourceRequestId),
                "persisted idempotent replay must not evaluate risk again");
        JsonNode riskPayload = mapper.readTree(RISK_PAYLOADS.get(sourceRequestId));
        assertEquals("MARKETING_AWARD", riskPayload.get("sourceId").asString());
        assertEquals("API", riskPayload.get("channel").asString());
        assertEquals("PAYMENT", riskPayload.get("bizType").asString());
        assertEquals(1_234L, riskPayload.get("amount").asLong());
        assertEquals("CNY", riskPayload.get("currency").asString());
        assertEquals("subject-1", riskPayload.get("accountNo").asString());
        assertEquals("Bearer risk-test-token", RISK_AUTHORIZATION.get());
        assertEquals("tenant-a", RISK_TENANT.get());
        assertTrue(RISK_TRACEPARENT.get().matches("00-[0-9a-f]{32}-[0-9a-f]{16}-0[01]"),
                "risk request must preserve the inbound trace across the bounded executor");

        String storedPayload = jdbc.queryForObject("select payload_json from mk_award_intent_outbox where tenant_id=? and source_request_id=?",
                String.class, "tenant-a", sourceRequestId);
        JsonNode outbound = mapper.readTree(storedPayload);
        assertEquals(1_234L, outbound.get("items").get(0).get("amountMinor").asLong());
        assertEquals("sku-active", outbound.get("items").get(0).get("benefitSkuId").asString());
        assertEquals(3, outbound.get("items").get(0).get("expectedSkuVersion").asLong());
        JsonNode expected = mapper.readTree(jdbc.queryForObject("select payload_json from mk_benefit_outbox where tenant_id=? and destination_topic=? and aggregate_id=(select intent_id from mk_award_intent_outbox where tenant_id=? and source_request_id=?)",
                String.class, "tenant-a", "marketing.award-expected.v1", "tenant-a", sourceRequestId));
        assertEquals("campaign-center", expected.get("campaignId").asString());
        assertEquals(1_234L, expected.get("amountMinor").asLong());

        HttpResponse<String> conflict = postRaw("/internal/v1/award-intents",
                awardCommand(sourceRequestId, "campaign-other", 7, "tenant-a", "award-cash@1", 1_234),
                sourceRequestId, "tenant-a");
        assertEquals(409, conflict.statusCode());
        assertEquals("AWARD_INTENT_IDEMPOTENCY_CONFLICT",
                mapper.readTree(conflict.body()).get("code").asString());

        AWARD_REQUESTS.set(0);
        var relayPool = Executors.newFixedThreadPool(2);
        List<AwardIntentRelay.Result> concurrentRelays;
        try {
            concurrentRelays = relayPool.invokeAll(List.of(
                    (Callable<AwardIntentRelay.Result>) awardIntentRelay::relayOnce,
                    (Callable<AwardIntentRelay.Result>) awardIntentRelay::relayOnce))
                    .stream().map(future -> {
                        try { return future.get(); }
                        catch (Exception failure) { throw new IllegalStateException(failure); }
                    }).toList();
        } finally {
            relayPool.shutdownNow();
        }
        AwardIntentRelay.Result replayRelay = awardIntentRelay.relayOnce();
        assertEquals(1, concurrentRelays.stream().mapToInt(AwardIntentRelay.Result::sent).sum());
        assertEquals(0, replayRelay.sent());
        assertEquals(1, AWARD_REQUESTS.get());
        assertEquals(sourceRequestId, AWARD_IDEMPOTENCY_KEY.get());
        assertEquals("tenant-a", AWARD_TENANT.get(),
                "relay must forward the persisted business tenant together with its machine identity");
        assertEquals(outbound, mapper.readTree(AWARD_PAYLOAD.get()));
        assertEquals(1L, jdbc.queryForObject("select lease_version from mk_award_intent_outbox where tenant_id=? and source_request_id=?",
                Long.class, "tenant-a", sourceRequestId), "relay claim must advance the fencing generation");

        JsonNode listed = get("/api/v1/award-intents?campaignId=campaign-center");
        assertEquals(1, listed.size());
        assertEquals("SENT", listed.get(0).get("status").asString());
        assertEquals("BO-CENTER-001", listed.get(0).get("benefitOrderNo").asString());
        assertFalse(listed.get(0).has("riskAction"));
        assertFalse(listed.get(0).has("riskReason"));
        assertFalse(listed.get(0).has("riskDecisionId"));
    }

    @Test
    void catalogVersionChangeCannotSilentlyRewriteQueuedAwardOnRetry() throws Exception {
        SKU_ACTIVE.set(true); SKU_BENEFIT_TYPE.set("CASH"); SKU_VERSION.set(3);
        AWARD_RESPONSE_STATUS.set(202); ENFORCE_SKU_VERSION.set(true);
        String source = "sku-fence-" + java.util.UUID.randomUUID();
        String benefit = "versioned-" + java.util.UUID.randomUUID();
        try {
            put("/api/v1/benefits/" + benefit, Map.of("name", "Versioned cash", "status", "ACTIVE",
                    "resourceKey", "", "benefitSkuId", "sku-active", "policy", Map.of("type", "CASH")),
                    "definition-" + source);
            var command = awardCommand(source, "campaign-version-fence", 1, "tenant-a", benefit + "@1", 500);
            post("/internal/v1/award-intents", command, source);
            String original = jdbc.queryForObject("select payload_json from mk_award_intent_outbox where tenant_id=? and source_request_id=?",
                    String.class, "tenant-a", source);
            assertEquals(3, mapper.readTree(original).path("items").get(0).path("expectedSkuVersion").asLong());
            SKU_VERSION.set(4);
            post("/internal/v1/award-intents", command, source);
            assertEquals(original, jdbc.queryForObject("select payload_json from mk_award_intent_outbox where tenant_id=? and source_request_id=?",
                    String.class, "tenant-a", source), "replay must preserve the original outbound bytes");
            awardIntentRelay.relayOnce();
            assertEquals("DEAD", jdbc.queryForObject("select status_name from mk_award_intent_outbox where tenant_id=? and source_request_id=?",
                    String.class, "tenant-a", source));
            assertEquals(3, mapper.readTree(AWARD_PAYLOAD.get()).path("items").get(0).path("expectedSkuVersion").asLong());
        } finally {
            SKU_VERSION.set(3); ENFORCE_SKU_VERSION.set(false); AWARD_RESPONSE_STATUS.set(202);
        }
    }

    @Test
    void legacyAndShadowModesNeverEnterCenterDeliveryQueue() throws Exception {
        SKU_ACTIVE.set(true);
        putTenant("/api/v1/benefits/shadow-cash", Map.of(
                "name", "Shadow cash", "status", "ACTIVE", "resourceKey", "",
                "benefitSkuId", "sku-active", "policy", Map.of("type", "CASH")),
                "benefit-shadow-command-001", "tenant-shadow");
        JsonNode shadow = postTenant("/internal/v1/award-intents",
                awardCommand("award-shadow-001", "campaign-shadow", 1,
                        "tenant-shadow", "shadow-cash@1", 500),
                "award-shadow-001", "tenant-shadow");
        assertEquals("SHADOW", shadow.get("deliveryMode").asString());
        assertEquals("SENT", shadow.get("status").asString());
        assertEquals("SHADOW_RECORDED", shadow.get("deliveryResult").asString());

        JsonNode legacy = postTenant("/internal/v1/award-intents", Map.of(
                "sourceRequestId", "award-legacy-001", "campaignId", "campaign-legacy",
                "definitionVersion", 1, "subjectRef", "subject-legacy",
                "offerToken", "not-a-client-controlled-award-payload", "cartDigest", CART_DIGEST),
                "award-legacy-001", "tenant-legacy");
        assertEquals("LEGACY", legacy.get("deliveryMode").asString());
        assertEquals("SENT", legacy.get("status").asString());
        assertEquals("LEGACY_OWNED", legacy.get("deliveryResult").asString());
        postTenant("/internal/v1/award-intents", Map.of(
                "sourceRequestId", "award-legacy-002", "campaignId", "campaign-legacy",
                "definitionVersion", 1, "subjectRef", "subject-legacy",
                "offerToken", "not-used-in-legacy-mode", "cartDigest", CART_DIGEST),
                "award-legacy-002", "tenant-legacy");
        JsonNode firstPage = getTenant("/api/v1/award-intents?campaignId=campaign-legacy&limit=1",
                "tenant-legacy");
        assertEquals(1, firstPage.size());
        JsonNode secondPage = getTenant("/api/v1/award-intents?campaignId=campaign-legacy&limit=1&cursor="
                + firstPage.get(0).get("intentId").asString(), "tenant-legacy");
        assertEquals(1, secondPage.size());
        assertFalse(firstPage.get(0).get("intentId").equals(secondPage.get(0).get("intentId")));
        assertEquals(0, jdbc.queryForObject("select count(*) from mk_award_intent_outbox where source_request_id in (?,?) and status_name='PENDING'",
                Integer.class, "award-shadow-001", "award-legacy-001"));
        assertEquals(0, jdbc.queryForObject("select count(*) from mk_benefit_outbox where aggregate_id in (select intent_id from mk_award_intent_outbox where source_request_id in (?,?)) and destination_topic=?",
                Integer.class, "award-shadow-001", "award-legacy-001", "marketing.award-expected.v1"));
    }

    @Test
    void businessRejectIsPersistedWithoutOutboxAndUnionCursorTraversesBothTables() throws Exception {
        SKU_ACTIVE.set(true);
        put("/api/v1/benefits/award-risk", Map.of(
                "name", "Risk checked cash", "status", "ACTIVE", "resourceKey", "",
                "benefitSkuId", "sku-active", "policy", Map.of("type", "CASH")),
                "benefit-risk-command-001");
        String rejectedSource = "award-risk-reject-001";
        Map<String, Object> rejectedCommand = awardCommand(rejectedSource, "campaign-risk-union", 4,
                "tenant-a", "award-risk@1", 700);

        JsonNode rejected = post("/internal/v1/award-intents", rejectedCommand, rejectedSource);
        assertEquals("RISK_BLOCKED", rejected.get("status").asString());
        assertEquals("REJECT", rejected.get("riskAction").asString());
        assertEquals("BLACKLIST_ACCOUNT", rejected.get("riskReason").asString());
        assertEquals("risk-" + rejectedSource, rejected.get("riskDecisionId").asString());
        assertTrue(rejected.get("deliveryResult").isNull());
        assertTrue(rejected.get("benefitOrderNo").isNull());
        assertTrue(rejected.get("sentAt").isNull());
        assertEquals(0, rejected.get("attempts").asInt());
        assertEquals("", rejected.get("lastError").asString());
        assertEquals(0, jdbc.queryForObject(
                "select count(*) from mk_award_intent_outbox where tenant_id=? and source_request_id=?",
                Integer.class, "tenant-a", rejectedSource));
        assertEquals(0, jdbc.queryForObject(
                "select count(*) from mk_benefit_outbox where tenant_id=? and payload_json like ?",
                Integer.class, "tenant-a", "%\"sourceRequestId\":\"" + rejectedSource + "\"%"));

        int checks = riskRequestCount(rejectedSource);
        JsonNode replay = post("/internal/v1/award-intents", rejectedCommand, rejectedSource);
        assertEquals(rejected.get("intentId"), replay.get("intentId"));
        assertEquals(checks, riskRequestCount(rejectedSource));

        String allowedSource = "award-risk-allow-union-001";
        post("/internal/v1/award-intents",
                awardCommand(allowedSource, "campaign-risk-union", 4,
                        "tenant-a", "award-risk@1", 701), allowedSource);
        String cursorBlockSource = "award-risk-reject-cursor-001";
        post("/internal/v1/award-intents",
                awardCommand(cursorBlockSource, "campaign-risk-union", 4,
                        "tenant-a", "award-risk@1", 702), cursorBlockSource);
        JsonNode combined = get("/api/v1/award-intents?campaignId=campaign-risk-union");
        assertTrue(java.util.stream.IntStream.range(0, combined.size())
                .mapToObj(combined::get).anyMatch(row -> "PENDING".equals(row.get("status").asString())));
        assertTrue(java.util.stream.IntStream.range(0, combined.size())
                .mapToObj(combined::get).anyMatch(row -> "RISK_BLOCKED".equals(row.get("status").asString())));
        JsonNode first = get("/api/v1/award-intents?campaignId=campaign-risk-union&limit=1");
        JsonNode second = get("/api/v1/award-intents?campaignId=campaign-risk-union&limit=1&cursor="
                + first.get(0).get("intentId").asString());
        JsonNode third = get("/api/v1/award-intents?campaignId=campaign-risk-union&limit=1&cursor="
                + second.get(0).get("intentId").asString());
        assertEquals("RISK_BLOCKED", first.get(0).get("status").asString(),
                "newest block row must itself be a valid cursor");
        assertEquals("PENDING", second.get(0).get("status").asString());
        assertEquals("RISK_BLOCKED", third.get(0).get("status").asString(),
                "outbox row must remain a valid cursor into the block table");
        // 本测试只验证合并分页；收尾待投递样例，避免影响同类中 Relay 的精确计数断言。
        jdbc.update("update mk_award_intent_outbox set status_name='SENT',delivery_result='CENTER_ACCEPTED' where tenant_id=? and source_request_id=?",
                "tenant-a", allowedSource);

        HttpResponse<String> conflict = postRaw("/internal/v1/award-intents",
                awardCommand(rejectedSource, "campaign-other", 4,
                        "tenant-a", "award-risk@1", 700), rejectedSource, "tenant-a");
        assertEquals(409, conflict.statusCode());
        assertEquals("AWARD_INTENT_IDEMPOTENCY_CONFLICT",
                mapper.readTree(conflict.body()).get("code").asString());
    }

    @Test
    void timeoutAndDegradedChallengeRemainFailClosedOnReplay() throws Exception {
        SKU_ACTIVE.set(true);
        put("/api/v1/benefits/award-risk-unavailable", Map.of(
                "name", "Unavailable risk cash", "status", "ACTIVE", "resourceKey", "",
                "benefitSkuId", "sku-active", "policy", Map.of("type", "CASH")),
                "benefit-risk-unavailable-001");
        String timeoutSource = "award-risk-timeout-001";
        Map<String, Object> timeoutCommand = awardCommand(timeoutSource, "campaign-risk-unavailable", 5,
                "tenant-a", "award-risk-unavailable@1", 800);

        HttpResponse<String> timeout = postRaw("/internal/v1/award-intents", timeoutCommand,
                timeoutSource, "tenant-a");
        assertRiskUnavailable(timeout);
        assertEquals(0, jdbc.queryForObject(
                "select count(*) from mk_award_intent_outbox where tenant_id=? and source_request_id=?",
                Integer.class, "tenant-a", timeoutSource));
        assertEquals("UNAVAILABLE", jdbc.queryForObject(
                "select risk_action from mk_award_intent_block where tenant_id=? and source_request_id=?",
                String.class, "tenant-a", timeoutSource));
        int checks = riskRequestCount(timeoutSource);
        assertRiskUnavailable(postRaw("/internal/v1/award-intents", timeoutCommand,
                timeoutSource, "tenant-a"));
        assertEquals(checks, riskRequestCount(timeoutSource),
                "UNAVAILABLE replay must return 503 without another risk call");

        String degradedSource = "award-risk-degraded-001";
        HttpResponse<String> degraded = postRaw("/internal/v1/award-intents",
                awardCommand(degradedSource, "campaign-risk-unavailable", 5,
                        "tenant-a", "award-risk-unavailable@1", 801), degradedSource, "tenant-a");
        assertRiskUnavailable(degraded);
        assertEquals("DEGRADED_FEATURE_UNAVAILABLE", jdbc.queryForObject(
                "select risk_reason from mk_award_intent_block where tenant_id=? and source_request_id=?",
                String.class, "tenant-a", degradedSource));

        String serverFailureSource = "award-risk-http-500-001";
        HttpResponse<String> serverFailure = postRaw("/internal/v1/award-intents",
                awardCommand(serverFailureSource, "campaign-risk-unavailable", 5,
                        "tenant-a", "award-risk-unavailable@1", 802), serverFailureSource, "tenant-a");
        assertRiskUnavailable(serverFailure);
        assertEquals("RISK_HTTP_5XX", jdbc.queryForObject(
                "select risk_reason from mk_award_intent_block where tenant_id=? and source_request_id=?",
                String.class, "tenant-a", serverFailureSource));
        assertEquals(0, jdbc.queryForObject(
                "select count(*) from mk_award_intent_outbox where tenant_id=? and source_request_id=?",
                Integer.class, "tenant-a", serverFailureSource));
    }

    @Test
    void legacyShadowAndNonCashAwardsUseRiskGateAndPositiveSentinel() throws Exception {
        SKU_ACTIVE.set(true);
        putTenant("/api/v1/benefits/shadow-risk", Map.of(
                "name", "Shadow risk cash", "status", "ACTIVE", "resourceKey", "",
                "benefitSkuId", "sku-active", "policy", Map.of("type", "CASH")),
                "benefit-shadow-risk-001", "tenant-shadow");
        JsonNode shadow = postTenant("/internal/v1/award-intents",
                awardCommand("award-shadow-reject-001", "campaign-mode-risk", 1,
                        "tenant-shadow", "shadow-risk@1", 500),
                "award-shadow-reject-001", "tenant-shadow");
        assertEquals("RISK_BLOCKED", shadow.get("status").asString());

        JsonNode shadowReview = postTenant("/internal/v1/award-intents",
                awardCommand("award-shadow-review-001", "campaign-mode-risk", 1,
                        "tenant-shadow", "shadow-risk@1", 501),
                "award-shadow-review-001", "tenant-shadow");
        assertEquals("REVIEW", shadowReview.get("riskAction").asString());

        JsonNode legacy = postTenant("/internal/v1/award-intents", Map.of(
                "sourceRequestId", "award-legacy-reject-001", "campaignId", "campaign-mode-risk",
                "definitionVersion", 1, "subjectRef", "subject-legacy",
                "offerToken", "not-used-in-legacy-mode", "cartDigest", CART_DIGEST),
                "award-legacy-reject-001", "tenant-legacy");
        assertEquals("RISK_BLOCKED", legacy.get("status").asString());
        assertEquals("LEGACY", legacy.get("deliveryMode").asString());
        JsonNode legacyRisk = mapper.readTree(RISK_PAYLOADS.get("award-legacy-reject-001"));
        assertEquals(1L, legacyRisk.get("amount").asLong());
        assertEquals("XXX", legacyRisk.get("currency").asString());

        JsonNode legacyChallenge = postTenant("/internal/v1/award-intents", Map.of(
                "sourceRequestId", "award-legacy-challenge-001", "campaignId", "campaign-mode-risk",
                "definitionVersion", 1, "subjectRef", "subject-legacy",
                "offerToken", "not-used-in-legacy-mode", "cartDigest", CART_DIGEST),
                "award-legacy-challenge-001", "tenant-legacy");
        assertEquals("CHALLENGE", legacyChallenge.get("riskAction").asString());

        SKU_BENEFIT_TYPE.set("COUPON");
        try {
            putTenant("/api/v1/benefits/coupon-risk", Map.of(
                    "name", "Coupon risk", "status", "ACTIVE", "resourceKey", "",
                    "benefitSkuId", "sku-active", "policy", Map.of("type", "COUPON")),
                    "benefit-coupon-risk-001", "tenant-coupon");
            postTenant("/internal/v1/award-intents",
                    awardCommand("award-coupon-allow-001", "campaign-coupon-risk", 1,
                            "tenant-coupon", "coupon-risk@1", 999),
                    "award-coupon-allow-001", "tenant-coupon");
            JsonNode couponRisk = mapper.readTree(RISK_PAYLOADS.get("award-coupon-allow-001"));
            assertEquals(1L, couponRisk.get("amount").asLong());
            assertEquals("XXX", couponRisk.get("currency").asString());
        } finally {
            SKU_BENEFIT_TYPE.set("CASH");
        }
        assertEquals(0, jdbc.queryForObject(
                "select count(*) from mk_award_intent_outbox where source_request_id in (?,?,?,?)",
                Integer.class, "award-shadow-reject-001", "award-shadow-review-001",
                "award-legacy-reject-001", "award-legacy-challenge-001"));
    }

    @Test
    void idempotencyKeyMismatchNeverCallsRiskOrPersistsAResult() throws Exception {
        String source = "award-key-mismatch-001";
        HttpResponse<String> mismatch = postRaw("/internal/v1/award-intents",
                awardCommand(source, "campaign-key-mismatch", 1,
                        "tenant-a", "unused@1", 100), "different-key", "tenant-a");
        assertEquals(409, mismatch.statusCode());
        assertEquals("AWARD_IDEMPOTENCY_KEY_MISMATCH",
                mapper.readTree(mismatch.body()).get("code").asString());
        assertEquals(0, riskRequestCount(source));
        assertEquals(0, jdbc.queryForObject(
                "select count(*) from mk_award_intent_dedupe where tenant_id=? and source_request_id=?",
                Integer.class, "tenant-a", source));
    }

    @Test
    void permanentBenefitCenterRejectionMovesIntentToDead() throws Exception {
        SKU_ACTIVE.set(true);
        put("/api/v1/benefits/award-dead", Map.of(
                "name", "Rejected cash", "status", "ACTIVE", "resourceKey", "",
                "benefitSkuId", "sku-active", "policy", Map.of("type", "CASH")),
                "benefit-award-dead-command-001");
        String sourceRequestId = "award-source-dead-001";
        post("/internal/v1/award-intents",
                awardCommand(sourceRequestId, "campaign-dead", 3, "tenant-a", "award-dead@1", 300),
                sourceRequestId);
        AWARD_RESPONSE_STATUS.set(400);
        try {
            AwardIntentRelay.Result result = awardIntentRelay.relayOnce();
            assertEquals(1, result.dead());
        } finally {
            AWARD_RESPONSE_STATUS.set(202);
        }
        JsonNode listed = get("/api/v1/award-intents?campaignId=campaign-dead");
        assertEquals("DEAD", listed.get(0).get("status").asString());
        assertTrue(listed.get(0).get("lastError").asString().contains("HTTP 400"));
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

        POSTING_TIME.set(BEIJING_MIDNIGHT.minusSeconds(10));
        String token = token("1", "campaign-ledger", "tenant-a");
        Map<String, Object> reserve = Map.of("offerToken", token, "cartDigest", CART_DIGEST,
                "orderId", "order-1", "expectedFencingEpochs", writerEpochs(1));
        JsonNode first = post("/api/v1/promotion-applications", reserve, "reserve-command-001");
        JsonNode duplicate = post("/api/v1/promotion-applications", reserve, "reserve-command-001");
        String applicationId = first.get("applicationId").asString();
        assertEquals(applicationId, duplicate.get("applicationId").asString());
        assertEquals("RESERVED", first.get("state").asString());
        assertEquals(16, jdbc.queryForObject(
                "select count(*) from mk_resource_escrow_bucket where tenant_id=? and resource_key=?",
                Integer.class, "tenant-a", "BUDGET:PLATFORM:platform:CNY"));
        assertEquals(2, jdbc.queryForObject(
                "select count(*) from mk_resource_escrow_bucket where tenant_id=? and resource_key=? and reserved_amount>0",
                Integer.class, "tenant-a", "BUDGET:PLATFORM:platform:CNY"));
        assertEquals(700L, jdbc.queryForObject(
                "select sum(reserved_amount) from mk_resource_escrow_bucket where tenant_id=? and resource_key=?",
                Long.class, "tenant-a", "BUDGET:PLATFORM:platform:CNY"));
        assertEquals(0L, jdbc.queryForObject(
                "select reserved_amount from mk_resource_account where tenant_id=? and resource_key=?",
                Long.class, "tenant-a", "BUDGET:PLATFORM:platform:CNY"),
                "budget balance writes must not serialize on the resource-account root row");
        assertEquals(3, jdbc.queryForObject(
                "select count(*) from mk_reservation_escrow_allocation where tenant_id=? and application_id=?",
                Integer.class, "tenant-a", applicationId));

        post("/api/v1/funding/accounts/INVENTORY:benefit-v1:advance-fence",
                Map.of("expectedEpoch", 1, "state", "ACTIVE"), null);

        String operations = "/api/v1/promotion-applications/" + applicationId;
        JsonNode confirmed = post(operations + ":confirm",
                Map.of("expectedFencingEpochs", writerEpochs(2)), "confirm-command-001");
        assertEquals("CONFIRMED", confirmed.get("state").asString());
        assertEquals(confirmed, post(operations + ":confirm",
                Map.of("expectedFencingEpochs", writerEpochs(2)), "confirm-command-001"));

        POSTING_TIME.set(BEIJING_MIDNIGHT);
        var refund = Map.of("resourceAmounts", Map.of("BUDGET:PLATFORM:platform:CNY", 200),
                "expectedFencingEpochs", writerEpochs(2));
        JsonNode partial = post(operations + ":refund", refund, "refund-command-001");
        assertEquals("PARTIALLY_REFUNDED", partial.get("state").asString());
        assertEquals(partial, post(operations + ":refund", refund, "refund-command-001"));
        assertSubsidyPostings(applicationId);

        POSTING_TIME.set(BEIJING_MIDNIGHT.plusSeconds(3));
        JsonNode reversed = post(operations + ":reverse",
                Map.of("expectedFencingEpochs", writerEpochs(2)), "reverse-command-001");
        assertEquals("REVERSED", reversed.get("state").asString());
        assertEquals(-800, totalPostings(postings(BEIJING_MIDNIGHT.plusSeconds(3), BEIJING_MIDNIGHT.plusSeconds(4))
                + "&campaignId=campaign-ledger"));

        JsonNode reconciliation = get("/api/v1/funding/reconciliation");
        assertTrue(reconciliation.get("balanced").asBoolean());
        assertEquals(0, reconciliation.get("violations").size());

        jdbc.update("delete from mk_benefit_outbox where tenant_id=? and aggregate_id=? and event_type=?",
                "tenant-a", applicationId, "PromotionREVERSED");
        JsonNode tampered = get("/api/v1/funding/reconciliation");
        assertEquals(false, tampered.get("balanced").asBoolean());
        assertTrue(tampered.get("violations").toString().contains("OUTBOX_INCOMPLETE"));
    }

    /** 实际两天的请求分别读确认和部分退款，分页、权限及历史归属沿用同一账本。 */
    private void assertSubsidyPostings(String applicationId) throws Exception {
        String firstDay = postings(BEIJING_MIDNIGHT.minusSeconds(20), BEIJING_MIDNIGHT) + "&campaignId=campaign-ledger";
        String nextDay = postings(BEIJING_MIDNIGHT, BEIJING_MIDNIGHT.plusSeconds(5)) + "&campaignId=campaign-ledger";
        assertEquals(1_000, totalPostings(firstDay));
        assertEquals(-200, totalPostings(nextDay));
        String firstSummary = firstDay.replace("subsidy-postings?", "subsidy-postings/summary?");
        var scopedSummary = get(firstSummary);
        assertFalse(scopedSummary.path("tenantWide").asBoolean());
        assertEquals("campaign-ledger", scopedSummary.path("campaignId").asString());
        assertEquals("1000", scopedSummary.path("groups").get(0).path("netAmountMinor").asString());
        assertEquals("3", scopedSummary.path("groups").get(0).path("postingCount").asString());
        assertFalse(scopedSummary.path("groups").get(0).path("unassigned").asBoolean());
        var tenantWide = exchange(base(firstSummary).setHeader("X-Dev-Organization-Ids", "*")
                .setHeader("X-Dev-Shop-Ids", "*").GET().build());
        assertTrue(tenantWide.path("tenantWide").asBoolean());
        assertEquals("0", tenantWide.path("unmatchedPostingCount").asString());
        assertTrue(scopedSummary.path("unmatchedPostingCount").isNull());
        var otherShopSummary = exchange(base(firstSummary).setHeader("X-Dev-Shop-Ids", "shop-other").GET().build());
        assertEquals(0, otherShopSummary.path("groups").size());
        assertEquals(0, getTenant(firstSummary, "other-tenant").path("groups").size());
        assertEquals("-200", get(nextDay.replace("subsidy-postings?", "subsidy-postings/summary?"))
                .path("groups").get(0).path("netAmountMinor").asString());
        List<JsonNode> paged = getSubsidyPostings(firstDay + "&limit=1");
        assertEquals(3, paged.size());
        assertEquals(3, new HashSet<>(paged.stream().map(row -> row.path("ledgerId").asString()).toList()).size());
        assertTrue(paged.stream().allMatch(row -> applicationId.equals(row.path("applicationId").asString())
                && "CNY".equals(row.path("currency").asString()) && "CONFIRM".equals(row.path("operation").asString())));
        assertEquals(700, paged.stream().filter(row -> row.path("resourceKey").asString().startsWith("BUDGET:PLATFORM:"))
                .mapToLong(row -> row.path("amountMinor").asLong()).sum());
        assertEquals(0, getTenant(firstDay, "other-tenant").path("items").size());
        var otherShop = exchange(base(firstDay).setHeader("X-Dev-Shop-Ids", "shop-other").GET().build());
        assertEquals(0, otherShop.path("items").size());
        var otherOrg = exchange(base(firstDay).setHeader("X-Dev-Organization-Ids", "Org-a").GET().build());
        assertEquals(0, otherOrg.path("items").size());
        var denied = client.send(base(firstDay).setHeader("X-Dev-Permissions", "measurement:read").GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(403, denied.statusCode());
        var cursorFromOtherDay = client.send(base(nextDay + "&cursor=" + paged.getFirst().path("ledgerId").asString())
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(400, cursorFromOtherDay.statusCode());
        assertOrderPostingScope(firstDay, nextDay, applicationId);
    }

    /** 订单查看复用同一授权和游标契约，区分大小写且不把退款移到原支付日。 */
    private void assertOrderPostingScope(String firstDay, String nextDay, String applicationId) throws Exception {
        String orderPath = firstDay.replace("subsidy-postings?", "subsidy-postings/by-order?");
        String firstUrl = orderPath + "&orderId=order-1&limit=1";
        var first = get(firstUrl);
        assertEquals("order-1", first.path("orderId").asString());
        var page = first.path("page");
        assertEquals(applicationId, page.at("/items/0/applicationId").asString());
        assertTrue(page.path("hasMore").asBoolean());
        String cursor = page.path("nextCursor").asString();
        assertEquals(applicationId, get(firstUrl + "&cursor=" + cursor).at("/page/items/0/applicationId").asString());
        assertTrue(get(orderPath + "&orderId=ORDER-1").at("/page/items").isEmpty());
        assertTrue(exchange(base(firstUrl).setHeader("X-Dev-Shop-Ids", "shop-other").GET().build()).at("/page/items").isEmpty());
        assertTrue(getTenant(firstUrl, "other-tenant").at("/page/items").isEmpty());
        assertEquals(400, client.send(base(orderPath + "&orderId=order-2&cursor=" + cursor)
                .GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(403, client.send(base(firstUrl).setHeader("X-Dev-Permissions", "measurement:read")
                .GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        String refunds = nextDay.replace("subsidy-postings?", "subsidy-postings/by-order?") + "&orderId=order-1";
        assertEquals(-200, java.util.stream.StreamSupport.stream(get(refunds).at("/page/items").spliterator(), false)
                .mapToLong(item -> Long.parseLong(item.path("amountMinor").asString())).sum());
    }

    @Test void olderOffersStayUnassignedAndRetainLegacyPostingPrecision() throws Exception {
        POSTING_TIME.set(BEIJING_MIDNIGHT);
        Map<String, Integer> epochs = writerEpochs(1);
        String tenant = "tenant-legacy-postings";
        createAccount("INVENTORY:benefit-v1", "INVENTORY", "UNIT", 10, tenant);
        createAccount("BUDGET:PLATFORM:platform:CNY", "BUDGET", "CNY", 10_000, tenant);
        createAccount("BUDGET:MERCHANT:shop-1:CNY", "BUDGET", "CNY", 10_000, tenant);
        String legacyToken = token("2", null, tenant);
        JsonNode reserved = postTenant("/api/v1/promotion-applications", Map.of("offerToken", legacyToken,
                "cartDigest", CART_DIGEST, "orderId", "order-2", "expectedFencingEpochs", epochs), "legacy-posting-reserve", tenant);
        String applicationId = reserved.path("applicationId").asString();
        postTenant("/api/v1/promotion-applications/" + applicationId + ":confirm", Map.of("expectedFencingEpochs", epochs),
                "legacy-posting-confirm", tenant);
        jdbc.update("update mk_funding_ledger set occurred_at=? where tenant_id=? and application_id=? and operation_name='CONFIRM'",
                BEIJING_MIDNIGHT.toString(), tenant, applicationId);
        String day = postings(BEIJING_MIDNIGHT, BEIJING_MIDNIGHT.plusNanos(1));
        var unassigned = getTenant(day + "&unassigned=true", tenant).path("items");
        assertEquals(3, unassigned.size());
        assertTrue(unassigned.get(0).path("campaignId").isNull());
        assertEquals(0, getTenant(day + "&campaignId=campaign-ledger", tenant).path("items").size());
        var groups = getTenant(day.replace("subsidy-postings?", "subsidy-postings/summary?") + "&campaignId=campaign-ledger", tenant).path("groups");
        assertEquals(1, groups.size());
        assertTrue(groups.get(0).path("unassigned").asBoolean());
        assertEquals("1000", groups.get(0).path("netAmountMinor").asString());
        var legacy = postRaw("/api/v1/promotion-applications/" + applicationId + ":attribute-campaign",
                Map.of("offerToken", legacyToken), null, tenant);
        assertEquals(409, legacy.statusCode());
        assertEquals("SUBSIDY_ATTRIBUTION_CAMPAIGN_MISSING", mapper.readTree(legacy.body()).path("code").asString());
        assertEquals(3, getTenant(day + "&unassigned=true", tenant).path("items").size());
        assertEquals(0, getTenant(postings(BEIJING_MIDNIGHT.minusSeconds(1), BEIJING_MIDNIGHT), tenant).path("items").size());
    }

    @Test void onlyExactOriginalQuoteRecoversCampaignAndFirstAuditWinsConcurrentRetries() throws Exception {
        POSTING_TIME.set(BEIJING_MIDNIGHT.minusSeconds(5));
        String tenant = "tenant-recover-attribution";
        createAccount("INVENTORY:benefit-v1", "INVENTORY", "UNIT", 10, tenant);
        createAccount("BUDGET:PLATFORM:platform:CNY", "BUDGET", "CNY", 10_000, tenant);
        createAccount("BUDGET:MERCHANT:shop-1:CNY", "BUDGET", "CNY", 10_000, tenant);
        String original = token("reconcile", "recover-campaign", tenant);
        String different = token("reconcile", "unrelated-campaign", tenant);
        var epochs = writerEpochs(1);
        String id = postTenant("/api/v1/promotion-applications", Map.of("offerToken", original,
                "cartDigest", CART_DIGEST, "orderId", "order-reconcile", "expectedFencingEpochs", epochs),
                "attribution-reserve", tenant).path("applicationId").asString();
        String application = "/api/v1/promotion-applications/" + id;
        // 模拟资金服务尚未保存签名中活动归属时写入的申请，其余凭证和账本走正常入口。
        jdbc.update("UPDATE mk_promotion_application SET campaign_id=NULL WHERE tenant_id=? AND application_id=?", tenant, id);
        postTenant(application + ":confirm", Map.of("expectedFencingEpochs", epochs), "attribution-confirm", tenant);
        POSTING_TIME.set(BEIJING_MIDNIGHT);
        postTenant(application + ":refund", Map.of("expectedFencingEpochs", epochs, "resourceAmounts",
                Map.of("BUDGET:PLATFORM:platform:CNY", 200)), "attribution-refund", tenant);
        String confirmWindow = postings(BEIJING_MIDNIGHT.minusSeconds(5), BEIJING_MIDNIGHT);
        String refundWindow = postings(BEIJING_MIDNIGHT, BEIJING_MIDNIGHT.plusSeconds(1));
        JsonNode before = getTenant(confirmWindow + "&unassigned=true", tenant).path("items");
        assertEquals(3, before.size());

        POSTING_TIME.set(BEIJING_MIDNIGHT.plusSeconds(600));
        String path = application + ":attribute-campaign";
        var mismatch = postRaw(path, Map.of("offerToken", different), null, tenant);
        assertEquals(409, mismatch.statusCode());
        assertEquals("SUBSIDY_ATTRIBUTION_EVIDENCE_MISMATCH", mapper.readTree(mismatch.body()).path("code").asString());
        assertEquals(404, postRaw(path, Map.of("offerToken", original), null, "other-tenant").statusCode());
        String body = mapper.writeValueAsString(Map.of("offerToken", original));
        var denied = client.send(base(path, tenant).setHeader("X-Dev-Permissions", "funding:reconcile")
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(403, denied.statusCode());
        var wrongShop = client.send(base(path, tenant).setHeader("X-Dev-Shop-Ids", "shop-other")
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(400, wrongShop.statusCode());

        var first = client.sendAsync(base(path, tenant).setHeader("X-Dev-Actor-Id", "first-operator")
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
        var second = client.sendAsync(base(path, tenant).setHeader("X-Dev-Actor-Id", "second-operator")
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, first.get().statusCode(), first.get().body());
        assertEquals(200, second.get().statusCode(), second.get().body());
        var recovered = mapper.readTree(first.get().body());
        assertEquals(recovered, mapper.readTree(second.get().body()));
        assertEquals("recover-campaign", recovered.path("campaignId").asString());
        assertEquals(POSTING_TIME.get(), Instant.parse(recovered.path("attributedAt").asString()));
        String auditActor = jdbc.queryForObject("SELECT campaign_attributed_by FROM mk_promotion_application "
                + "WHERE tenant_id=? AND application_id=?", String.class, tenant, id);
        assertTrue(List.of("first-operator", "second-operator").contains(auditActor));
        POSTING_TIME.set(BEIJING_MIDNIGHT.plusSeconds(900));
        assertEquals(recovered, postTenant(path, Map.of("offerToken", original), null, tenant));
        assertEquals(auditActor, jdbc.queryForObject("SELECT campaign_attributed_by FROM mk_promotion_application "
                + "WHERE tenant_id=? AND application_id=?", String.class, tenant, id));
        assertEquals(0, getTenant(confirmWindow + "&unassigned=true", tenant).path("items").size());
        var confirmed = getTenant(confirmWindow + "&campaignId=recover-campaign", tenant).path("items");
        assertEquals(before.size(), confirmed.size());
        for (int index = 0; index < confirmed.size(); index++) {
            for (String field : List.of("ledgerId", "postedAt", "amountMinor", "resourceKey")) {
                assertEquals(before.get(index).path(field), confirmed.get(index).path(field));
            }
        }
        assertEquals("-200", getTenant(refundWindow.replace("subsidy-postings?", "subsidy-postings/summary?")
                + "&campaignId=recover-campaign", tenant).path("groups").get(0).path("netAmountMinor").asString());
    }

    @Test void ledgerCoverageCountsOnlyUnmatchedPotentialBudgetPostingsInItsTenantAndWindow() throws Exception {
        String tenant = "tenant-unmatched-postings";
        createAccount("BUDGET:PLATFORM:platform:CNY", "BUDGET", "CNY", 10_000, tenant);
        createAccount("INVENTORY:benefit-v1", "INVENTORY", "UNIT", 10, tenant);
        insertOrphanPosting(tenant, "BUDGET:PLATFORM:platform:CNY", "budget-confirm", "CONFIRM", BEIJING_MIDNIGHT);
        insertOrphanPosting(tenant, "unknown-resource", "unknown-refund", "REFUND", BEIJING_MIDNIGHT);
        insertOrphanPosting(tenant, "INVENTORY:benefit-v1", "inventory-confirm", "CONFIRM", BEIJING_MIDNIGHT);
        insertOrphanPosting(tenant, "other-resource", "outside-window", "CONFIRM", BEIJING_MIDNIGHT.plusSeconds(1));
        insertOrphanPosting(tenant, "reserved-resource", "reserve-only", "RESERVE", BEIJING_MIDNIGHT);
        insertOrphanPosting("other-tenant", "unknown-resource", "other-tenant", "CONFIRM", BEIJING_MIDNIGHT);
        String summary = postings(BEIJING_MIDNIGHT, BEIJING_MIDNIGHT.plusSeconds(1))
                .replace("subsidy-postings?", "subsidy-postings/summary?") + "&campaignId=selected-campaign";
        var completeScope = exchange(base(summary, tenant).setHeader("X-Dev-Organization-Ids", "*")
                .setHeader("X-Dev-Shop-Ids", "*").GET().build());
        assertEquals("2", completeScope.path("unmatchedPostingCount").asString());
        assertEquals(0, completeScope.path("groups").size());
        assertTrue(getTenant(summary, tenant).path("unmatchedPostingCount").isNull());
    }

    /** 制造历史流水缺少申请关联，确认统计未把缺行悄悄当零。 */
    private void insertOrphanPosting(String tenant, String resourceKey, String ledgerId, String operation, Instant at) {
        jdbc.update("""
                INSERT INTO mk_funding_ledger (tenant_id, ledger_id, application_id, order_id, resource_key,
                    operation_name, debit_bucket, credit_bucket, amount_value, account_version, occurred_at, escrow_bucket_id)
                VALUES (?, ?, 'missing-application', 'example-order', ?, ?, 'RESERVED', 'CONSUMED', 1, 1, ?, 0)
                """, tenant, ledgerId, resourceKey, operation, at.toString());
    }

    private static String postings(Instant from, Instant to) {
        return "/api/v1/funding/subsidy-postings?from=" + from + "&to=" + to;
    }

    private long totalPostings(String path) throws Exception {
        return getSubsidyPostings(path).stream().mapToLong(row -> row.path("amountMinor").asLong()).sum();
    }

    @Test
    void tradeOrderBindingRouteIsDisabledByDefault() throws Exception {
        var request = base("/internal/v1/promotion-applications/example/trade-order-binding")
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString("{}"))
                .build();
        assertEquals(404, client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode());
    }

    private List<JsonNode> getSubsidyPostings(String path) throws Exception {
        List<JsonNode> rows = new ArrayList<>();
        JsonNode response = get(path);
        while (true) {
            response.path("items").forEach(rows::add);
            if (!response.path("hasMore").asBoolean()) return rows;
            assertTrue(rows.size() < 20, "fee fixture should use fewer than 20 records");
            response = get(path + "&cursor=" + response.path("nextCursor").asString());
        }
    }

    @Test
    void concurrentHotBudgetReservationsSpreadAcrossEscrowBucketsWithoutOverspend() throws Exception {
        String inventory = "INVENTORY:benefit-hot-v1";
        String budget = "BUDGET:PLATFORM:hot-platform:CNY";
        createAccount(inventory, "INVENTORY", "UNIT", 1_000);
        createAccount(budget, "BUDGET", "CNY", 10_000);
        var pool = Executors.newFixedThreadPool(16);
        try {
            List<Callable<JsonNode>> calls = java.util.stream.IntStream.range(0, 16)
                    .mapToObj(index -> (Callable<JsonNode>) () -> post(
                            "/api/v1/promotion-applications",
                            Map.of("offerToken", hotBudgetToken(index), "cartDigest", CART_DIGEST,
                                    "orderId", "hot-order-" + index, "expectedFencingEpochs",
                                    Map.of(inventory, 1, budget, 1)),
                            "hot-reserve-command-" + index))
                    .toList();
            List<JsonNode> applications = pool.invokeAll(calls).stream().map(future -> {
                try { return future.get(); }
                catch (Exception failure) { throw new IllegalStateException(failure); }
            }).toList();
            assertEquals(16, applications.stream()
                    .map(value -> value.get("applicationId").asString()).distinct().count());
        } finally {
            pool.shutdownNow();
        }

        for (int index = 16; index < 100; index++) {
            post("/api/v1/promotion-applications",
                    Map.of("offerToken", hotBudgetToken(index), "cartDigest", CART_DIGEST,
                            "orderId", "hot-order-" + index, "expectedFencingEpochs",
                            Map.of(inventory, 1, budget, 1)),
                    "hot-reserve-command-" + index);
        }
        HttpResponse<String> exhausted = postRaw("/api/v1/promotion-applications",
                Map.of("offerToken", hotBudgetToken(100), "cartDigest", CART_DIGEST,
                        "orderId", "hot-order-100", "expectedFencingEpochs",
                        Map.of(inventory, 1, budget, 1)),
                "hot-reserve-command-100", "tenant-a");
        assertEquals(409, exhausted.statusCode());
        assertTrue(exhausted.body().contains("REPRICE_REQUIRED"));

        assertTrue(jdbc.queryForObject(
                "select count(distinct bucket_id) from mk_reservation_escrow_allocation where tenant_id=? and resource_key=?",
                Integer.class, "tenant-a", budget) > 1,
                "stable quote hashing must spread normal reservations across independent rows");
        assertEquals(10_000L, jdbc.queryForObject(
                "select sum(reserved_amount) from mk_resource_escrow_bucket where tenant_id=? and resource_key=?",
                Long.class, "tenant-a", budget));
        assertEquals(0, jdbc.queryForObject(
                "select count(*) from mk_resource_escrow_bucket where tenant_id=? and resource_key=? and authorized_amount<>available_amount+reserved_amount+consumed_amount",
                Integer.class, "tenant-a", budget));
        assertEquals(10_000L, jdbc.queryForObject(
                "select sum(amount_value) from mk_funding_ledger where tenant_id=? and resource_key=? and operation_name='RESERVE'",
                Long.class, "tenant-a", budget));
        assertEquals(0L, jdbc.queryForObject(
                "select reserved_amount from mk_resource_account where tenant_id=? and resource_key=?",
                Long.class, "tenant-a", budget));
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
        createAccount(key, type, currency, authorized, "tenant-a");
    }

    private void createAccount(String key, String type, String currency, long authorized, String tenant) throws Exception {
        postTenant("/api/v1/funding/accounts", Map.of("resourceKey", key, "type", type, "currency", currency,
                "authorized", authorized, "fencingEpoch", 1),
                "account-" + Digests.sha256Hex(key).substring(0, 16), tenant);
    }

    private static String token(String suffix, String campaignId, String tenant) {
        Instant now = POSTING_TIME.get();
        OfferLineClaim line = new OfferLineClaim("offer-1", "benefit-v1", "CNY", 1_000, 1,
                List.of(new FundingShareClaim("PLATFORM", "platform", "CNY", 700),
                        new FundingShareClaim("MERCHANT", "shop-1", "CNY", 300)));
        OfferTokenClaims claims = new OfferTokenClaims("decision", new TenantId(tenant), "org-a", "subject-1",
                "order-" + suffix, List.of("shop-1"), CART_DIGEST, "quote-" + suffix, "request-" + suffix, 1,
                List.of("artifact-1"), List.of(line),
                "terms-v1", now.minusSeconds(1), now.plusSeconds(300), "nonce-" + suffix, List.of(), campaignId);
        return OfferTokenCodec.encode("offer-test-key", OFFER_KEYS.getPrivate(), claims);
    }

    private static String hotBudgetToken(int index) {
        Instant now = Instant.now();
        OfferLineClaim line = new OfferLineClaim("hot-offer-" + index, "benefit-hot-v1", "CNY", 100, 1,
                List.of(new FundingShareClaim("PLATFORM", "hot-platform", "CNY", 100)));
        OfferTokenClaims claims = new OfferTokenClaims("decision", new TenantId("tenant-a"), "org-a", "subject-1",
                "hot-order-" + index, List.of("shop-1"), CART_DIGEST, "hot-quote-" + index,
                "hot-request-" + index, 1, List.of("artifact-1"), List.of(line),
                "terms-v1", now.minusSeconds(1), now.plusSeconds(300), "hot-nonce-" + index, List.of());
        return OfferTokenCodec.encode("offer-test-key", OFFER_KEYS.getPrivate(), claims);
    }

    private static Map<String, Object> awardCommand(String sourceRequestId, String campaignId,
            long definitionVersion, String tenantId, String benefitVersion, long amountMinor) {
        return Map.of("sourceRequestId", sourceRequestId, "campaignId", campaignId,
                "definitionVersion", definitionVersion, "subjectRef", "subject-1",
                "offerToken", awardToken(tenantId, benefitVersion, amountMinor),
                "cartDigest", CART_DIGEST);
    }

    private static String awardToken(String tenantId, String benefitVersion, long amountMinor) {
        Instant now = Instant.now();
        OfferLineClaim line = new OfferLineClaim("award-offer", benefitVersion, "CNY", amountMinor, 1,
                List.of(new FundingShareClaim("PLATFORM", "platform", "CNY", amountMinor)));
        OfferTokenClaims claims = new OfferTokenClaims("offer-decision-service", new TenantId(tenantId),
                "org-a", "subject-1", "order-award-1", List.of("shop-1"), CART_DIGEST,
                "quote-award-1", "decision-award-1", 9, List.of("artifact-award-1"), List.of(line),
                "terms-award-1", now.minusSeconds(1), now.plusSeconds(300), "nonce-award-1", List.of());
        return OfferTokenCodec.encode("offer-test-key", OFFER_KEYS.getPrivate(), claims);
    }

    private JsonNode post(String path, Object body, String idempotencyKey) throws Exception {
        return postTenant(path, body, idempotencyKey, "tenant-a");
    }
    private JsonNode postTenant(String path, Object body, String idempotencyKey, String tenantId) throws Exception {
        HttpResponse<String> response = postRaw(path, body, idempotencyKey, tenantId);
        assertTrue(response.statusCode() >= 200 && response.statusCode() < 300,
                () -> response.statusCode() + ": " + response.body());
        return mapper.readTree(response.body());
    }
    private HttpResponse<String> postRaw(String path, Object body, String idempotencyKey,
            String tenantId) throws Exception {
        HttpRequest.Builder builder = base(path, tenantId).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
        if (idempotencyKey != null) builder.header("Idempotency-Key", idempotencyKey);
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
    private JsonNode get(String path) throws Exception { return getTenant(path, "tenant-a"); }
    private JsonNode getTenant(String path, String tenantId) throws Exception {
        return exchange(base(path, tenantId).GET().build());
    }
    private JsonNode put(String path, Object body, String idempotencyKey) throws Exception {
        return putTenant(path, body, idempotencyKey, "tenant-a");
    }
    private JsonNode putTenant(String path, Object body, String idempotencyKey, String tenantId) throws Exception {
        return exchange(base(path, tenantId).header("Content-Type", "application/json")
                .header("Idempotency-Key", idempotencyKey)
                .PUT(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build());
    }
    private HttpRequest.Builder base(String path) {
        return base(path, "tenant-a");
    }
    private HttpRequest.Builder base(String path, String tenantId) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).timeout(Duration.ofSeconds(30))
                .header("X-Dev-Tenant-Id", tenantId).header("X-Dev-Actor-Id", "benefit-test")
                .header("X-Dev-Organization-Ids", "org-a").header("X-Dev-Shop-Ids", "shop-1")
                .header("X-Dev-Permissions", "*");
    }
    private JsonNode exchange(HttpRequest request) throws Exception {
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertTrue(response.statusCode() >= 200 && response.statusCode() < 300,
                () -> response.statusCode() + ": " + response.body());
        return mapper.readTree(response.body());
    }

    private void assertRiskUnavailable(HttpResponse<String> response) throws Exception {
        assertEquals(503, response.statusCode());
        JsonNode problem = mapper.readTree(response.body());
        assertEquals("RISK_UNAVAILABLE", problem.get("code").asString());
        assertTrue(problem.get("retryable").asBoolean());
        assertTrue(problem.hasNonNull("detail"));
    }

    private static int riskRequestCount(String transactionId) {
        AtomicInteger count = RISK_REQUESTS.get(transactionId);
        return count == null ? 0 : count.get();
    }

    /** 启动一个契约级权益中台替身，验证 tenant 传递、过滤和发布门禁。 */
    private static HttpServer startBenefitCenter() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/internal/v1/catalog/skus", exchange -> {
                BENEFIT_SKU_REQUESTS.incrementAndGet();
                FORWARDED_TENANT.set(exchange.getRequestHeaders().getFirst("X-Tenant-Id"));
                BENEFIT_AUTHORIZATION.set(exchange.getRequestHeaders().getFirst("Authorization"));
                int responseStatus = BENEFIT_CATALOG_STATUS.get();
                if (responseStatus != 200) {
                    byte[] problem = (responseStatus == 401
                            ? "{\"code\":\"UNAUTHORIZED\"}"
                            : "{\"code\":\"BENEFIT_TENANT_MISMATCH\"}")
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/problem+json");
                    exchange.sendResponseHeaders(responseStatus, problem.length);
                    try (var output = exchange.getResponseBody()) {
                        output.write(problem);
                    }
                    return;
                }
                String active = SKU_ACTIVE.get() ? """
                          {"skuId":"sku-active","benefitType":"%s","faceValueMinor":8000,
                           "currency":"CNY","status":"ACTIVE","enabled":true,"validityType":"RELATIVE",
                           "relativeDays":7,"usableWeekdays":[],"userLimitPerDay":1,"version":%d},
                        """.formatted(SKU_BENEFIT_TYPE.get(), SKU_VERSION.get()) : "";
                byte[] body = ("""
                        [
                        """ + active + """
                          {"skuId":"sku-disabled","benefitType":"COUPON","status":"ACTIVE","enabled":false,
                           "validityType":"RELATIVE","relativeDays":7,"usableWeekdays":[],"version":2},
                          {"skuId":"sku-paused","benefitType":"COUPON","status":"PAUSED","enabled":true,
                           "validityType":"RELATIVE","relativeDays":7,"usableWeekdays":[],"version":4}
                        ]
                        """).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (var output = exchange.getResponseBody()) {
                    output.write(body);
                }
            });
            server.createContext("/openapi/v1/award-orders", exchange -> {
                AWARD_REQUESTS.incrementAndGet();
                AWARD_IDEMPOTENCY_KEY.set(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
                AWARD_TENANT.set(exchange.getRequestHeaders().getFirst("X-Tenant-Id"));
                AWARD_PAYLOAD.set(new String(exchange.getRequestBody().readAllBytes(),
                        java.nio.charset.StandardCharsets.UTF_8));
                int status = AWARD_RESPONSE_STATUS.get();
                if (ENFORCE_SKU_VERSION.get()) {
                    var expected = new ObjectMapper().readTree(AWARD_PAYLOAD.get()).path("items").get(0)
                            .path("expectedSkuVersion").asLong(-1);
                    if (expected != SKU_VERSION.get()) status = 409;
                }
                byte[] body = (status == 202
                        ? "{\"awardOrderNo\":\"BO-CENTER-001\",\"status\":\"ACCEPTED\",\"replay\":false}"
                        : "{\"code\":\"INVALID_AWARD\"}")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, body.length);
                try (var output = exchange.getResponseBody()) {
                    output.write(body);
                }
            });
            server.start();
            return server;
        } catch (IOException failure) {
            throw new IllegalStateException("cannot start benefit center test server", failure);
        }
    }

    /** 启动契约级 risk-platform 替身，按 txnId 驱动稳定的允许、拒绝、降级和超时场景。 */
    private static HttpServer startRiskPlatform() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/api/v1/risk/evaluations", exchange -> {
                String requestBody = new String(exchange.getRequestBody().readAllBytes(),
                        java.nio.charset.StandardCharsets.UTF_8);
                var matcher = TXN_ID.matcher(requestBody);
                String transactionId = matcher.find() ? matcher.group(1) : "missing-txn";
                RISK_REQUESTS.computeIfAbsent(transactionId, ignored -> new AtomicInteger()).incrementAndGet();
                RISK_PAYLOADS.put(transactionId, requestBody);
                RISK_AUTHORIZATION.set(exchange.getRequestHeaders().getFirst("Authorization"));
                RISK_TENANT.set(exchange.getRequestHeaders().getFirst("X-Tenant-Id"));
                RISK_TRACEPARENT.set(exchange.getRequestHeaders().getFirst("traceparent"));

                if (transactionId.contains("timeout")) {
                    try {
                        Thread.sleep(300L);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                }
                int status = transactionId.contains("http-500") ? 500 : 200;
                String action = transactionId.contains("reject") ? "REJECT"
                        : transactionId.contains("degraded") || transactionId.contains("challenge")
                                ? "CHALLENGE"
                                : transactionId.contains("review") ? "REVIEW" : "ALLOW";
                String hitRules = transactionId.contains("reject") ? "[\"BLACKLIST_ACCOUNT\"]"
                        : transactionId.contains("degraded") ? "[\"DEGRADED_FEATURE_UNAVAILABLE\"]"
                                : transactionId.contains("challenge") ? "[\"DEVICE_CHALLENGE\"]"
                                : transactionId.contains("review") ? "[\"MANUAL_REVIEW\"]" : "[]";
                byte[] body = (status == 200
                        ? "{\"decisionId\":\"risk-" + transactionId + "\",\"txnId\":\""
                                + transactionId + "\",\"action\":\"" + action
                                + "\",\"hitRules\":" + hitRules + "}"
                        : "{\"code\":\"RISK_INTERNAL_ERROR\"}")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                try {
                    exchange.sendResponseHeaders(status, body.length);
                    try (var output = exchange.getResponseBody()) {
                        output.write(body);
                    }
                } catch (IOException clientTimedOut) {
                    exchange.close();
                }
            });
            server.setExecutor(RISK_SERVER_EXECUTOR);
            server.start();
            return server;
        } catch (IOException failure) {
            throw new IllegalStateException("cannot start risk platform test server", failure);
        }
    }
}
