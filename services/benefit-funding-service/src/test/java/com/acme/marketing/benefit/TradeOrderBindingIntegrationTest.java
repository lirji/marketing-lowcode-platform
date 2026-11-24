package com.acme.marketing.benefit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.acme.marketing.benefit.application.BenefitFundingService;
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
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/internal/v1/promotion-applications/"
                + reservation.applicationId() + "/trade-order-binding"))
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
