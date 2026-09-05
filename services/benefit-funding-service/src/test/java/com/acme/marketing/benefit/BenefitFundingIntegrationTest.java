package com.acme.marketing.benefit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
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
    private static final AtomicInteger BENEFIT_SKU_REQUESTS = new AtomicInteger();
    private static final AtomicBoolean SKU_ACTIVE = new AtomicBoolean(true);
    private static final AtomicReference<String> FORWARDED_TENANT = new AtomicReference<>();
    private static final AtomicInteger AWARD_REQUESTS = new AtomicInteger();
    private static final AtomicReference<String> AWARD_IDEMPOTENCY_KEY = new AtomicReference<>();
    private static final AtomicReference<String> AWARD_TENANT = new AtomicReference<>();
    private static final AtomicReference<String> AWARD_PAYLOAD = new AtomicReference<>();
    private static final AtomicInteger AWARD_RESPONSE_STATUS = new AtomicInteger(202);
    private static final ConcurrentHashMap<String, AtomicInteger> RISK_REQUESTS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, String> RISK_PAYLOADS = new ConcurrentHashMap<>();
    private static final AtomicReference<String> RISK_AUTHORIZATION = new AtomicReference<>();
    private static final AtomicReference<String> RISK_TENANT = new AtomicReference<>();
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
        assertEquals("tenant-a", FORWARDED_TENANT.get());
        assertEquals(1, BENEFIT_SKU_REQUESTS.get(), "second list request should use tenant-scoped L1 cache");

        benefitSkuCatalog.list("tenant-b", SkuStatus.ACTIVE);
        assertEquals("tenant-b", FORWARDED_TENANT.get());
        assertEquals(2, BENEFIT_SKU_REQUESTS.get(), "another tenant must not reuse tenant-a cache data");
        get("/api/v1/benefit-skus?status=ACTIVE");
        assertEquals(2, BENEFIT_SKU_REQUESTS.get(), "tenant-a cache should remain isolated and reusable");

        JsonNode stored = put("/api/v1/benefits/coupon-active", Map.of(
                "name", "Active coupon", "status", "ACTIVE", "resourceKey", "",
                "benefitSkuId", "sku-active", "policy", Map.of()), "benefit-command-active-001");
        assertEquals("sku-active", stored.get("benefitSkuId").asString());
        assertEquals("sku-active", get("/api/v1/benefits/coupon-active").get("benefitSkuId").asString());
        assertTrue(BENEFIT_SKU_REQUESTS.get() >= 2, "ACTIVE publish must bypass the list cache");

        SKU_ACTIVE.set(false);
        int requestsBeforeReplay = BENEFIT_SKU_REQUESTS.get();
        JsonNode replay = put("/api/v1/benefits/coupon-active", Map.of(
                "name", "Active coupon", "status", "ACTIVE", "resourceKey", "",
                "benefitSkuId", "sku-active", "policy", Map.of()), "benefit-command-active-001");
        assertEquals(stored, replay);
        assertEquals(requestsBeforeReplay, BENEFIT_SKU_REQUESTS.get(),
                "completed idempotent replay must not revalidate a subsequently paused SKU");

        HttpRequest invalid = base("/api/v1/benefits/coupon-invalid")
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", "benefit-command-invalid-001")
                .PUT(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(Map.of(
                        "name", "Invalid coupon", "status", "ACTIVE", "resourceKey", "",
                        "benefitSkuId", "sku-paused", "policy", Map.of()))))
                .build();
        HttpResponse<String> rejected = client.send(invalid, HttpResponse.BodyHandlers.ofString());
        assertEquals(409, rejected.statusCode());
        assertEquals("SKU_NOT_ACTIVE", mapper.readTree(rejected.body()).get("code").asString());
        assertFalse(get("/api/v1/benefits").toString().contains("coupon-invalid"));
        SKU_ACTIVE.set(true);
    }

    @Test
    void centerAwardIntentIsConcurrentIdempotentAndRelayedExactlyOnce() throws Exception {
        SKU_ACTIVE.set(true);
        put("/api/v1/benefits/award-cash", Map.of(
                "name", "Authoritative cash", "status", "ACTIVE", "resourceKey", "",
                "benefitSkuId", "sku-active", "policy", Map.of()), "benefit-award-command-001");
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
        int riskChecksAfterFirstResult = riskRequestCount(sourceRequestId);
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

        String storedPayload = jdbc.queryForObject("select payload_json from mk_award_intent_outbox where tenant_id=? and source_request_id=?",
                String.class, "tenant-a", sourceRequestId);
        JsonNode outbound = mapper.readTree(storedPayload);
        assertEquals(1_234L, outbound.get("items").get(0).get("amountMinor").asLong());
        assertEquals("sku-active", outbound.get("items").get(0).get("benefitSkuId").asString());
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
        assertEquals("tenant-a", AWARD_TENANT.get());
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
    void legacyAndShadowModesNeverEnterCenterDeliveryQueue() throws Exception {
        SKU_ACTIVE.set(true);
        putTenant("/api/v1/benefits/shadow-cash", Map.of(
                "name", "Shadow cash", "status", "ACTIVE", "resourceKey", "",
                "benefitSkuId", "sku-active", "policy", Map.of()),
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
                "benefitSkuId", "sku-active", "policy", Map.of()), "benefit-risk-command-001");
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
                "benefitSkuId", "sku-active", "policy", Map.of()), "benefit-risk-unavailable-001");
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
                "benefitSkuId", "sku-active", "policy", Map.of()),
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
                    "benefitSkuId", "sku-active", "policy", Map.of()),
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
                "benefitSkuId", "sku-active", "policy", Map.of()), "benefit-award-dead-command-001");
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
            server.createContext("/admin/v1/skus", exchange -> {
                BENEFIT_SKU_REQUESTS.incrementAndGet();
                FORWARDED_TENANT.set(exchange.getRequestHeaders().getFirst("X-Tenant-Id"));
                String active = SKU_ACTIVE.get() ? """
                          {"skuId":"sku-active","benefitType":"%s","faceValueMinor":8000,
                           "currency":"CNY","status":"ACTIVE","enabled":true,"validityType":"RELATIVE",
                           "relativeDays":7,"usableWeekdays":[],"userLimitPerDay":1,"version":3},
                        """.formatted(SKU_BENEFIT_TYPE.get()) : "";
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
