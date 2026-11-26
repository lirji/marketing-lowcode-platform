package com.acme.marketing.benefit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.acme.marketing.benefit.application.BenefitFundingService;
import com.acme.marketing.benefit.application.TradeOrderBindingMachineAuthority;
import com.acme.marketing.benefit.application.TradeOrderBindingMachineAuthority.Grant;
import com.acme.marketing.benefit.application.TradeOrderBindingService.BindRequest;
import com.acme.marketing.benefit.domain.ResourceAccount.Type;
import com.acme.marketing.benefit.infrastructure.persistence.mapper.TradeReceiptMapper;
import com.acme.marketing.benefit.infrastructure.trade.TradeReceipt;
import com.acme.marketing.contracts.offer.FundingShareClaim;
import com.acme.marketing.contracts.offer.OfferLineClaim;
import com.acme.marketing.contracts.offer.OfferTokenClaims;
import com.acme.marketing.contracts.offer.OfferTokenCodec;
import com.acme.marketing.platform.crypto.Digests;
import com.acme.marketing.platform.crypto.Ed25519;
import com.acme.marketing.platform.identity.TenantId;
import com.acme.marketing.testsupport.MySqlIntegrationTest;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "marketing.security.mode=OIDC",
        "marketing.risk.bearer-token=unused-binding-test-risk-token",
        "marketing.trade-order-bindings.enabled=true",
        "marketing.trade-order-bindings.casdoor.enabled=true",
        "marketing.trade-order-bindings.sources=mk-binding|trade-machine|7,mk-binding|trade-machine|8,"
                + "mk-other-binding|trade-machine|7,MK-BINDING|trade-machine|7",
        "marketing.offer.trusted-key-id=binding-test-key"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TradeOrderBindingIntegrationTest extends MySqlIntegrationTest {
    private static final String TENANT = "mk-binding";
    private static final String ACTOR = "trade-machine";
    private static final String PERMISSION = "trade-order-binding.write";
    private static final String AUDIENCE = "marketing-api";
    private static final String CASDOOR_CLIENT = "trade-binding-app";
    private static final String CASDOOR_SUBJECT = "admin/" + CASDOOR_CLIENT;
    private static final String CART_DIGEST = "sha256:" + "a".repeat(64);
    private static final String BUDGET = "BUDGET:PLATFORM:platform:CNY";
    private static final String INVENTORY = "INVENTORY:binding-benefit";
    private static final KeyPair OFFER_KEYS = Ed25519.generateKeyPair();
    private static final RSAKey OIDC_KEY = createRsaKey();
    private static final HttpServer JWKS = startJwks();

    @Autowired private TradeReceiptMapper receipts;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper json;
    @Autowired private Clock clock;
    @LocalServerPort private int port;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    @DynamicPropertySource
    static void bindRealKeys(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", TradeOrderBindingIntegrationTest::issuer);
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", () -> issuer() + "/jwks");
        registry.add("spring.security.oauth2.resourceserver.jwt.audiences", () -> AUDIENCE);
        registry.add("marketing.trade-order-bindings.casdoor.issuer-uri", () -> issuer() + "/casdoor");
        registry.add("marketing.trade-order-bindings.casdoor.jwk-set-uri", () -> issuer() + "/jwks");
        registry.add("marketing.trade-order-bindings.casdoor.grants", () -> """
                [
                  {"clientId":"trade-binding-app","subject":"admin/trade-binding-app","marketingTenantId":"mk-binding",
                   "sourceTenantId":"7","organizationId":"org-a","shopIds":["shop-a"]},
                  {"clientId":"wrong-shop-app","subject":"admin/wrong-shop-app","marketingTenantId":"mk-binding",
                   "sourceTenantId":"7","organizationId":"org-a","shopIds":["shop-b"]},
                  {"clientId":"wrong-org-app","subject":"admin/wrong-org-app","marketingTenantId":"mk-binding",
                   "sourceTenantId":"7","organizationId":"org-b","shopIds":["shop-a"]},
                  {"clientId":"other-tenant-app","subject":"admin/other-tenant-app","marketingTenantId":"mk-other-binding",
                   "sourceTenantId":"7","organizationId":"org-a","shopIds":["shop-a"]}
                ]
                """);
        registry.add("marketing.offer.public-key-base64",
                () -> Base64.getEncoder().encodeToString(OFFER_KEYS.getPublic().getEncoded()));
    }

    @BeforeAll
    void setupFunding() throws Exception {
        String jwt = jwt(TENANT, "funding-setup", "funding:account-write", AUDIENCE, "org-a", "shop-a");
        expect(post("/api/v1/funding/accounts", jwt, "binding-budget", new BenefitFundingService.CreateAccountRequest(
                BUDGET, Type.BUDGET, "CNY", 10_000, 1L)), 201, null);
        expect(post("/api/v1/funding/accounts", jwt, "binding-inventory", new BenefitFundingService.CreateAccountRequest(
                INVENTORY, Type.INVENTORY, "UNIT", 100, 1L)), 201, null);
    }

    @AfterAll
    static void stopJwks() {
        JWKS.stop(0);
    }

    @Test
    void concurrentBindingsUseHighestAvailableVersionAndReplayFirstAuditWithoutLedgerWrites() throws Exception {
        long order = Long.MAX_VALUE;
        Reservation reservation = reserve(order, Long.toString(order));
        String jwt = machine();
        String chosen = receipt(7, order, 3, "CNY");
        receipt(7, order, 2, "CNY"); // 晚到的低版本不能替换最高证据。
        receipt(8, order, 4, "CNY"); // 原生租户也是查询条件。
        long before = ledgerCount(reservation.applicationId());

        HttpRequest request = request(reservation, "7", Long.toString(order), jwt);
        var calls = List.of(
                client.sendAsync(request, HttpResponse.BodyHandlers.ofString()),
                client.sendAsync(request, HttpResponse.BodyHandlers.ofString()));
        CompletableFuture.allOf(calls.toArray(CompletableFuture[]::new)).get(20, TimeUnit.SECONDS);
        assertEquals(200, calls.getFirst().get().statusCode(), calls.getFirst().get().body());
        assertEquals(200, calls.getLast().get().statusCode(), calls.getLast().get().body());
        JsonNode binding = json.readTree(calls.getFirst().get().body());
        assertEquals(binding, json.readTree(calls.getLast().get().body()));
        assertEquals(reservation.applicationId(), binding.path("applicationId").asString());
        assertEquals("7", binding.path("sourceTenantId").asString());
        assertEquals(Long.toString(order), binding.path("sourceOrderId").asString());
        assertEquals(chosen, binding.path("receiptEventId").asString());
        assertEquals("3", binding.path("evidenceVersion").asString());

        receipt(7, order, 5, "CNY");
        assertEquals(binding, expect(request, 200, null));
        expect(request(reservation, "8", Long.toString(order), jwt), 409, "TRADE_ORDER_BINDING_CONFLICT");
        assertEquals(1, bindingCount(reservation.applicationId()));
        assertEquals(ACTOR, jdbc.queryForObject("SELECT bound_by FROM mk_trade_order_binding WHERE application_id = ?",
                String.class, reservation.applicationId()));
        assertEquals(before, ledgerCount(reservation.applicationId()));
    }

    @Test
    void bindingRequiresReceiptInAuthorizedTenantAndCanRetryAfterArrival() throws Exception {
        long order = 701;
        Reservation reservation = reserve(order, Long.toString(order));
        receipt(8, order, 2, "CNY");
        HttpRequest request = request(reservation, "7", Long.toString(order), machine());
        expect(request, 409, "TRADE_ORDER_BINDING_RECEIPT_MISSING");
        assertEquals(0, bindingCount(reservation.applicationId()));
        receipt(7, order, 3, "USD");
        expect(request, 409, "TRADE_ORDER_BINDING_CURRENCY_MISMATCH");
        assertEquals(0, bindingCount(reservation.applicationId()));
        assertEquals(receipt(7, order, 4, "CNY"), expect(request, 200, null).path("receiptEventId").asString());
    }

    @Test
    void rejectsUnsignedOrderWrongOrderAndDifferentSignedQuote() throws Exception {
        receipt(7, 702, 2, "CNY");
        receipt(7, 703, 2, "CNY");
        Reservation legacy = reserve(702, "");
        Reservation signed = reserve(703, "703");
        String jwt = machine();
        expect(request(legacy, "7", "702", jwt), 409, "TRADE_ORDER_BINDING_ORDER_MISMATCH");
        expect(request(signed, "7", "702", jwt), 409, "TRADE_ORDER_BINDING_ORDER_MISMATCH");
        expect(request(new Reservation(signed.applicationId(), legacy.offerToken()), "7", "703", jwt), 409,
                "TRADE_ORDER_BINDING_OFFER_MISMATCH");
        expect(request(signed, "7", "9223372036854775808", jwt), 400, "INVALID_ARGUMENT");
        expect(request(signed, "7", "0703", jwt), 400, "INVALID_ARGUMENT");
        assertEquals(0, bindingCount(legacy.applicationId()));
        assertEquals(0, bindingCount(signed.applicationId()));
    }

    @Test
    void rejectsUntrustedIdentityAndScopesBeforeWriting() throws Exception {
        Reservation reservation = reserve(704, "704");
        receipt(7, 704, 2, "CNY");
        expect(request(reservation, "7", "704", null), 401, null);
        String legitimate = machine();
        int signatureStart = legitimate.lastIndexOf('.') + 1;
        char substitute = legitimate.charAt(signatureStart) == 'A' ? 'B' : 'A';
        String tampered = legitimate.substring(0, signatureStart) + substitute + legitimate.substring(signatureStart + 1);
        expect(request(reservation, "7", "704", tampered), 401, null);
        expect(request(reservation, "7", "704", jwt(TENANT, ACTOR, PERMISSION, "other-audience", "org-a", "shop-a")),
                401, null);
        expect(request(reservation, "7", "704", jwt(TENANT, "other-machine", PERMISSION, AUDIENCE, "org-a", "shop-a")),
                403, "TRADE_ORDER_BINDING_FORBIDDEN");
        expect(request(reservation, "7", "704", jwt(TENANT, ACTOR, "*", AUDIENCE, "org-a", "shop-a")), 403,
                "TRADE_ORDER_BINDING_FORBIDDEN");
        expect(request(reservation, "9", "704", machine()), 403, "TRADE_ORDER_BINDING_FORBIDDEN");
        expect(request(reservation, "7", "704", jwt("mk-other-binding", ACTOR, PERMISSION, AUDIENCE, "org-a", "shop-a")),
                404, "APPLICATION_NOT_FOUND");
        expect(request(reservation, "7", "704", jwt("MK-BINDING", ACTOR, PERMISSION, AUDIENCE, "org-a", "shop-a")),
                404, "APPLICATION_NOT_FOUND");
        expect(request(reservation, "7", "704", jwt(TENANT, ACTOR, PERMISSION, AUDIENCE, "other", "shop-a")),
                400, "INVALID_ARGUMENT");
        expect(request(reservation, "7", "704", jwt(TENANT, ACTOR, PERMISSION, AUDIENCE, "org-a", "other")),
                400, "INVALID_ARGUMENT");
        assertEquals(0, bindingCount(reservation.applicationId()));
    }

    @Test
    void reportsScopedBindingEvidenceBeforePostingAndDetectsAnUnverifiableFirstReceipt() throws Exception {
        long order = 714;
        Reservation reservation = reserve(order, Long.toString(order));
        String path = "/api/v1/funding/subsidy-postings/by-order/bindings?orderId=";
        String auditor = jwt(TENANT, "auditor", "funding:reconcile", AUDIENCE, "org-a", "shop-a");
        JsonNode unbound = expect(get(path + order + "&unassigned=true", auditor), 200, null);
        assertEquals(Long.toString(order), unbound.path("orderId").asString());
        assertEquals(true, unbound.path("unassigned").asBoolean());
        assertEquals(1, unbound.path("items").size());
        JsonNode item = unbound.path("items").get(0);
        assertEquals(reservation.applicationId(), item.path("applicationId").asString());
        assertEquals("UNBOUND", item.path("status").asString());
        assertEquals(true, item.path("evidenceVersion").isNull());
        assertEquals(true, item.path("boundAt").isNull());
        assertEquals(true, unbound.path("nextCursor").isNull());
        long ledgerBefore = ledgerCount(reservation.applicationId());

        assertEquals(0, expect(get(path + "0" + order, auditor), 200, null).path("items").size());
        assertEquals(0, expect(get(path + order + "&campaignId=other", auditor), 200, null).path("items").size());
        assertEquals(0, expect(get(path + order + "&after=" + reservation.applicationId(), auditor), 200, null)
                .path("items").size());
        expect(get(path + order + "&after=%25", auditor), 400, null);
        expect(get(path + order + "&campaignId=other&unassigned=true", auditor), 400, null);
        expect(get(path + order, machine()), 403, null);
        String otherTenant = jwt("mk-other-binding", "auditor", "funding:reconcile", AUDIENCE, "org-a", "shop-a");
        String caseVariantTenant = jwt("MK-BINDING", "auditor", "funding:reconcile", AUDIENCE, "org-a", "shop-a");
        String otherShop = jwt(TENANT, "auditor", "funding:reconcile", AUDIENCE, "org-a", "shop-b");
        String otherOrg = jwt(TENANT, "auditor", "funding:reconcile", AUDIENCE, "org-b", "shop-a");
        for (String inaccessible : List.of(otherTenant, caseVariantTenant, otherShop, otherOrg)) {
            assertEquals(0, expect(get(path + order, inaccessible), 200, null).path("items").size());
        }

        long originalVersion = 9_007_199_254_740_993L;
        String originalReceipt = receipt(7, order, originalVersion, "CNY");
        JsonNode binding = expect(request(reservation, "7", Long.toString(order), machine()), 200, null);
        receipt(7, order, originalVersion + 1, "CNY");
        JsonNode bound = expect(get(path + order, auditor), 200, null).path("items").get(0);
        assertEquals("BOUND", bound.path("status").asString());
        assertEquals(Long.toString(originalVersion), bound.path("evidenceVersion").asString());
        assertEquals(Instant.parse(binding.path("boundAt").asString()), Instant.parse(bound.path("boundAt").asString()));
        assertTrue(bound.path("applicationNo").asString().matches("MKT-FAP-[0-9]{6}-[0-9]{10}"));
        assertEquals(6, bound.size());
        jdbc.update("UPDATE mk_trade_receipt_inbox SET source_tenant_id = ? WHERE event_id = ?", 8, originalReceipt);
        JsonNode missing = expect(get(path + order, auditor), 200, null).path("items").get(0);
        assertEquals("EVIDENCE_MISSING", missing.path("status").asString());
        assertEquals(bound.path("evidenceVersion"), missing.path("evidenceVersion"));
        assertEquals(ledgerBefore, ledgerCount(reservation.applicationId()));
    }

    private HttpRequest get(String path, String jwt) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Bearer " + jwt).GET().build();
    }

    @Test
    void casdoorApplicationUsesConfiguredGrantAndReplaysFirstBinding() throws Exception {
        long order = 801;
        Reservation reservation = reserve(order, Long.toString(order));
        String token = casdoorJwt(CASDOOR_CLIENT, claims -> { });
        long before = ledgerCount(reservation.applicationId());
        expect(casdoorRequest(reservation, "7", order, token), 409, "TRADE_ORDER_BINDING_RECEIPT_MISSING");
        String firstReceipt = receipt(7, order, 2, "CNY");
        JsonNode binding = expect(casdoorRequest(reservation, "7", order, token), 200, null);
        assertEquals(firstReceipt, binding.path("receiptEventId").asString());
        receipt(7, order, 3, "CNY");
        assertEquals(binding, expect(casdoorRequest(reservation, "7", order, token), 200, null));
        assertEquals(1, bindingCount(reservation.applicationId()));
        assertEquals(CASDOOR_SUBJECT, jdbc.queryForObject(
                "SELECT bound_by FROM mk_trade_order_binding WHERE application_id = ?", String.class,
                reservation.applicationId()));
        assertEquals(before, ledgerCount(reservation.applicationId()));
        expect(request(reservation, "7", Long.toString(order), token), 401, null);
        expect(post("/api/v1/promotion-applications/" + reservation.applicationId() + ":confirm", token,
                "casdoor-must-not-confirm", new BenefitFundingService.SettlementRequest(Map.of(BUDGET, 1L, INVENTORY, 1L))),
                401, null);
        assertEquals(before, ledgerCount(reservation.applicationId()));
    }

    @Test
    void casdoorApplicationRequiresClientClaimsAndAllBusinessScopes() throws Exception {
        long order = 802;
        Reservation reservation = reserve(order, Long.toString(order));
        receipt(7, order, 2, "CNY");
        expect(casdoorRequest(reservation, "7", order, null), 401, null);
        expect(casdoorRequest(reservation, "7", order, machine()), 401, null);
        expect(casdoorRequest(reservation, "7", order, casdoorJwt(CASDOOR_CLIENT,
                claims -> claims.issuer("https://wrong-issuer.test"))), 401, null);
        for (String unapproved : List.of(casdoorJwt("not-approved", claims -> { }),
                casdoorJwt(CASDOOR_CLIENT, claims -> claims.subject("admin/another-application")),
                casdoorJwt(CASDOOR_CLIENT, claims -> claims.audience(List.of(CASDOOR_CLIENT, AUDIENCE))),
                casdoorJwt(CASDOOR_CLIENT, claims -> claims.claim("azp", "another-client")),
                casdoorJwt(CASDOOR_CLIENT, claims -> claims.claim("type", "normal-user")),
                casdoorJwt(CASDOOR_CLIENT, claims -> claims.claim("tokenType", "id-token")),
                casdoorJwt(CASDOOR_CLIENT, claims -> claims.expirationTime(Date.from(Instant.now().plusSeconds(301)))))) {
            expect(casdoorRequest(reservation, "7", order, unapproved), 403, "TRADE_ORDER_BINDING_FORBIDDEN");
        }
        expect(casdoorRequest(reservation, "8", order, casdoorJwt(CASDOOR_CLIENT, claims -> { })),
                403, "TRADE_ORDER_BINDING_FORBIDDEN");
        expect(casdoorRequest(reservation, "7", order, casdoorJwt("wrong-shop-app",
                claims -> claims.claim("shop_ids", List.of("shop-a")))), 400, "INVALID_ARGUMENT");
        expect(casdoorRequest(reservation, "7", order, casdoorJwt("wrong-org-app",
                claims -> claims.claim("org_ids", List.of("org-a")))), 400, "INVALID_ARGUMENT");
        expect(casdoorRequest(reservation, "7", order, casdoorJwt("other-tenant-app",
                claims -> claims.claim("tenant_id", TENANT))), 404, "APPLICATION_NOT_FOUND");
        assertEquals(0, bindingCount(reservation.applicationId()));
    }

    @Test
    void casdoorGrantsRejectWildcardScopesAndDuplicateClients() {
        Grant approved = new Grant(CASDOOR_CLIENT, CASDOOR_SUBJECT, TENANT, "7", "org-a", List.of("shop-a"));
        assertThrows(IllegalArgumentException.class, () -> new TradeOrderBindingMachineAuthority(clock, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new TradeOrderBindingMachineAuthority(clock, List.of(approved, approved)));
        assertThrows(IllegalArgumentException.class, () -> new TradeOrderBindingMachineAuthority(clock,
                List.of(new Grant(CASDOOR_CLIENT, CASDOOR_SUBJECT, TENANT, "7", "org-a", List.of("*")))));
    }

    /** 通过真实资金事务创建预留，让报价摘要和预留时间来自应用写路径。 */
    private Reservation reserve(long order, String quotedOrder) throws Exception {
        Instant now = clock.instant();
        String suffix = UUID.randomUUID().toString();
        OfferLineClaim line = new OfferLineClaim("offer-a", "binding-benefit", "CNY", 100, 1,
                List.of(new FundingShareClaim("PLATFORM", "platform", "CNY", 100)));
        OfferTokenClaims claims = new OfferTokenClaims("decision", new TenantId(TENANT), "org-a", "member-a",
                quotedOrder, List.of("shop-a"), CART_DIGEST, "q-" + suffix, "r-" + suffix, 1,
                List.of("artifact-a"), List.of(line), "v1", now.minusSeconds(10), now.plusSeconds(300),
                "n-" + suffix, List.of());
        String token = OfferTokenCodec.encode("binding-test-key", OFFER_KEYS.getPrivate(), claims);
        String jwt = jwt(TENANT, "funding-setup", "promotion:reserve", AUDIENCE, "org-a", "shop-a");
        var application = expect(post("/api/v1/promotion-applications", jwt, "cmd-" + suffix,
                new BenefitFundingService.ReserveRequest(token, CART_DIGEST, Long.toString(order),
                        Map.of(BUDGET, 1L, INVENTORY, 1L))), 201, null);
        return new Reservation(application.path("applicationId").asString(), token);
    }

    private String receipt(long sourceTenant, long order, long version, String currency) {
        String id = UUID.randomUUID().toString();
        receipts.reserve(new TradeReceipt(id, "trade.order-paid.v1", sourceTenant, order, version,
                "receipt-" + version, null, currency, 500, null, null, null, clock.instant().toString(),
                Digests.sha256Hex(id), "trade.order-paid.v1", 0, version));
        return id;
    }

    private long bindingCount(String application) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM mk_trade_order_binding WHERE application_id = ?",
                Long.class, application);
    }

    private long ledgerCount(String application) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM mk_funding_ledger WHERE application_id = ?",
                Long.class, application);
    }

    private HttpRequest request(Reservation reservation, String sourceTenant, String order, String jwt) {
        return bindingRequest(reservation, sourceTenant, order, jwt, "");
    }

    private HttpRequest casdoorRequest(Reservation reservation, String sourceTenant, long order, String jwt) {
        return bindingRequest(reservation, sourceTenant, Long.toString(order), jwt, "/casdoor");
    }

    private HttpRequest bindingRequest(Reservation reservation, String sourceTenant, String order, String jwt,
            String routeSuffix) {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/internal/v1/promotion-applications/"
                + reservation.applicationId() + "/trade-order-binding" + routeSuffix))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json");
        if (jwt != null) {
            request.header("Authorization", "Bearer " + jwt);
        } else {
            request.header("X-Dev-Tenant-Id", TENANT).header("X-Dev-Actor-Id", ACTOR)
                    .header("X-Dev-Permissions", PERMISSION);
        }
        return request.PUT(HttpRequest.BodyPublishers.ofString(
                json.writeValueAsString(new BindRequest(sourceTenant, order, reservation.offerToken())))).build();
    }

    private HttpRequest post(String path, String jwt, String command, Object body) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", command)
                .header("Authorization", "Bearer " + jwt)
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
    }

    private JsonNode expect(HttpRequest request, int status, String code) throws Exception {
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(status, response.statusCode(), response.body());
        assertFalse(response.body().contains("offerToken"));
        JsonNode body = response.body().isBlank() ? null : json.readTree(response.body());
        if (code != null) assertEquals(code, body.path("code").asString());
        return body;
    }

    private static String machine() throws Exception {
        return jwt(TENANT, ACTOR, PERMISSION, AUDIENCE, "org-a", "shop-a");
    }

    private static String jwt(String tenant, String actor, String permission, String audience, String org, String shop)
            throws Exception {
        Instant now = Instant.now();
        var claims = new JWTClaimsSet.Builder().issuer(issuer()).subject(actor).audience(audience)
                .issueTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(300)))
                .claim("tenant_id", tenant).claim("org_ids", List.of(org)).claim("shop_ids", List.of(shop))
                .claim("permissions", List.of(permission)).build();
        return sign(claims);
    }

    private static String casdoorJwt(String client, Consumer<JWTClaimsSet.Builder> configure) throws Exception {
        Instant now = Instant.now();
        var claims = new JWTClaimsSet.Builder().issuer(issuer() + "/casdoor").subject("admin/" + client).audience(client)
                .issueTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(300)))
                .claim("type", "application").claim("tokenType", "access-token").claim("azp", client);
        configure.accept(claims);
        return sign(claims.build());
    }

    private static String sign(JWTClaimsSet claims) throws Exception {
        var token = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(OIDC_KEY.getKeyID()).build(), claims);
        token.sign(new RSASSASigner(OIDC_KEY));
        return token.serialize();
    }

    private static String issuer() {
        return "http://127.0.0.1:" + JWKS.getAddress().getPort();
    }

    private static RSAKey createRsaKey() {
        try {
            return new RSAKeyGenerator(2048).keyID("test-jwt").generate();
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    private static HttpServer startJwks() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/jwks", exchange -> {
                byte[] body = ("{\"keys\":[" + OIDC_KEY.toPublicJWK().toJSONString() + "]}")
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (var output = exchange.getResponseBody()) {
                    output.write(body);
                }
            });
            server.start();
            return server;
        } catch (IOException error) {
            throw new IllegalStateException(error);
        }
    }

    private record Reservation(String applicationId, String offerToken) { }
}
