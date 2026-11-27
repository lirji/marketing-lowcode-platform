package com.acme.marketing.benefit.application;

import com.acme.marketing.benefit.application.BenefitFundingRepository.CheckoutBindingRow;
import com.acme.marketing.platform.crypto.Digests;
import com.acme.marketing.platform.crypto.Ed25519KeyPairCodec;
import com.acme.marketing.platform.crypto.SignedTokenCodec;
import com.acme.marketing.platform.error.ConflictException;
import com.acme.marketing.platform.error.DependencyUnavailableException;
import com.acme.marketing.platform.error.ForbiddenException;
import com.acme.marketing.platform.error.NotFoundException;
import com.acme.marketing.platform.identity.TenantScope;
import java.net.URI;
import java.net.http.HttpClient;
import java.security.PrivateKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/** 通过交易证据建立单店预算预留并发行只用于该订单的付款凭证。 */
@Service
@ConditionalOnProperty(name = "marketing.checkout-funding.enabled", havingValue = "true")
public class CheckoutFundingService {
    private static final String PRICE_PURPOSE = "marketing.checkout-price/v1";
    private static final String RECEIPT_PURPOSE = "marketing.checkout-funding/v1";
    private static final String EVIDENCE_PATH = "/internal/v1/checkout-pilot/quote-references/{reference}/order-evidence";
    private static final String IDENTIFIER = "[A-Za-z0-9_.:@/-]{1,64}";
    public static final String REFERENCE = "[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}";
    public static final int MAX_PROOF = 24_000;
    private static final Set<String> PAID = Set.of("PAID", "PARTIALLY_REFUNDED", "REFUNDED");
    private final BenefitFundingRepository repository;
    private final BenefitFundingService funding;
    private final OfferTokenTrust priceTrust;
    private final Clock clock;
    private final RestClient trade;
    private final PrivateKey signingKey;
    private final String keyId;
    private final String actor;
    private final String store;
    private final Map<String, String> priceScope;
    private final boolean acceptNew;

    /** 配对固定到机器、两侧租户、门店、活动、范围版本和平台出资方。 */
    public CheckoutFundingService(BenefitFundingRepository repository, BenefitFundingService funding, OfferTokenTrust priceTrust,
            Clock clock, @Value("${marketing.checkout-funding.trade-base-url}") URI origin,
            @Value("${marketing.checkout-funding.trade-token}") String token,
            @Value("${marketing.checkout-funding.key-id}") String keyId,
            @Value("${marketing.checkout-funding.private-key}") String privateKey,
            @Value("${marketing.checkout-funding.public-key}") String publicKey,
            @Value("${marketing.checkout-funding.actor}") String actor,
            @Value("${marketing.checkout-funding.tenant}") String tenant,
            @Value("${marketing.checkout-funding.trade-tenant}") String tradeTenant,
            @Value("${marketing.checkout-funding.organization}") String organization,
            @Value("${marketing.checkout-funding.shop}") String shop,
            @Value("${marketing.checkout-funding.store}") String store,
            @Value("${marketing.checkout-funding.campaign}") String campaign,
            @Value("${marketing.checkout-funding.scope-version}") String scopeVersion,
            @Value("${marketing.checkout-funding.funder}") String funder,
            @Value("${marketing.checkout-funding.accept-new:true}") boolean acceptNew) {
        if (!validOrigin(origin) || !token.matches("[A-Za-z0-9_.-]{40,8192}") || !keyId.matches("[A-Za-z0-9_-]{1,64}")
                || actor.isBlank() || actor.length() > 255 || "*".equals(actor) || positive(tradeTenant) <= 0
                || !List.of(tenant, organization, shop, store, campaign, scopeVersion, funder).stream()
                        .allMatch(value -> value.matches(IDENTIFIER))) {
            throw new IllegalArgumentException("试点预算需要配置独立机器和有效的结算范围");
        }
        this.repository = repository;
        this.funding = funding;
        this.priceTrust = priceTrust;
        this.clock = clock;
        this.keyId = keyId;
        this.signingKey = Ed25519KeyPairCodec.decode(privateKey, publicKey).getPrivate();
        this.actor = actor;
        this.store = store;
        this.acceptNew = acceptNew;
        this.priceScope = Map.of("marketingTenantId", tenant, "tradeTenantId", tradeTenant, "organizationId", organization,
            "shopId", shop, "campaignId", campaign, "scopeVersion", scopeVersion, "funderId", funder, "currency", "CNY");
        var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build();
        var factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofSeconds(3));
        trade = RestClient.builder().baseUrl(origin.toString()).requestFactory(factory)
            .defaultHeaders(headers -> headers.setBearerAuth(token)).build();
    }

    /** 先核验交易证据，再调用本地资金事务，网络等待期间不持有账本锁。 */
    public Reservation reserve(TenantScope scope, String reference, String priceToken) {
        requireScope(scope, reference);
        var claims = verifyPrice(reference, priceToken);
        String hash = Digests.sha256Hex(priceToken);
        String tenant = scope.tenantId().value();
        var prior = repository.findCheckout(tenant, reference);
        if (prior.isPresent()) return replay(prior.get(), hash);
        if (!acceptNew) throw new ConflictException("CHECKOUT_FUNDING_PAUSED", "试点已暂停新预算预留");
        var facts = evidence(reference);
        require(facts.priceProofHash().equals(hash) && "PENDING_PAYMENT".equals(facts.orderStatus())
                && "UNPAID".equals(facts.financialStatus()) && facts.paidAt() == null
                && facts.grossMinor().equals(claims.get("grossMinor")) && facts.discountMinor().equals(claims.get("discountMinor"))
                && facts.payableMinor().equals(claims.get("payableMinor")));
        long gross = positive(facts.grossMinor()), discount = positive(facts.discountMinor()), payable = positive(facts.payableMinor());
        require(discount < gross && gross - discount == payable);
        var quoteExpiry = checkedTime(claims.get("expiresAt"));
        var request = new BenefitFundingService.CheckoutReservation(reference, facts.tradeTenantId(), facts.orderId(),
                claims.get("marketingQuoteId"), claims.get("organizationId"), claims.get("shopId"), claims.get("cartDigest"),
                hash, positive(claims.get("generation")), claims.get("campaignId"), claims.get("funderId"), facts.currency(),
                discount, quoteExpiry, facts.orderExpiresAt(), facts.readableOrderNo());
        funding.reserveCheckout(scope, request, applicationId -> sign(tenant, reference, facts, applicationId));
        return replay(repository.findCheckout(tenant, reference).orElseThrow(), hash);
    }

    /** 只有交易已入账或可靠关单才可确认或释放，渠道处理中保持原预算。 */
    public Reservation reconcile(TenantScope scope, String reference, boolean refreshOrderNumber) {
        requireScope(scope, reference);
        String tenant = scope.tenantId().value();
        var current = repository.findCheckout(tenant, reference)
                .orElseThrow(() -> new NotFoundException("CHECKOUT_FUNDING_NOT_FOUND", "试点预算预留不存在"));
        boolean reserved = "RESERVED".equals(current.state());
        if (!reserved && (!refreshOrderNumber || current.orderNo() != null)) {
            recordSettlementReplay(scope, reference, current);
            return response(current);
        }
        var facts = evidence(reference);
        require(current.tradeTenant().equals(facts.tradeTenantId()) && current.orderId().equals(facts.orderId())
                && current.tokenDigest().equals(facts.priceProofHash()) && current.currency().equals(facts.currency())
                && Long.toString(current.discountMinor()).equals(facts.discountMinor()));
        // 旧申请只能从同一可信原生订单补展示号，已经结算的申请不再推进资金状态。
        String orderNo = facts.readableOrderNo();
        if (current.orderNo() == null && orderNo != null) {
            repository.recordCheckoutOrderNo(tenant, reference, current.orderId(), current.tokenDigest(), orderNo);
        }
        if (!reserved) {
            recordSettlementReplay(scope, reference, current);
            return response(current);
        }
        boolean paid = facts.paidAt() != null && PAID.contains(facts.financialStatus());
        boolean cancelled = facts.paidAt() == null && "CANCELLED".equals(facts.orderStatus()) && "UNPAID".equals(facts.financialStatus());
        if (paid || cancelled) funding.settleCheckout(scope, current.applicationId(), reference, paid);
        return response(repository.findCheckout(tenant, reference).orElseThrow());
    }

    private void recordSettlementReplay(TenantScope scope, String reference,
            BenefitFundingRepository.CheckoutBindingRow current) {
        if ("CONFIRMED".equals(current.state()) || "CANCELLED".equals(current.state())) {
            funding.recordCheckoutReplay(scope, reference, current.applicationId(), "CONFIRMED".equals(current.state()));
        }
    }

    private Map<String, String> verifyPrice(String reference, String token) {
        try {
            require(token != null && token.length() <= MAX_PROOF);
            var claims = SignedTokenCodec.decodeAndVerify(token, priceTrust::resolve).claims();
            require(PRICE_PURPOSE.equals(claims.get("purpose")) && reference.equals(claims.get("tradeQuoteRef"))
                    && claims.entrySet().containsAll(priceScope.entrySet())
                    && matches(claims.get("marketingQuoteId"), REFERENCE)
                    && matches(claims.get("cartDigest"), "sha256:[0-9a-f]{64}"));
            return claims;
        } catch (IllegalArgumentException invalid) {
            throw mismatch();
        }
    }

    private OrderFacts evidence(String reference) {
        try {
            var facts = trade.get().uri(EVIDENCE_PATH, reference).retrieve().body(OrderFacts.class);
            require(facts != null && reference.equals(facts.tradeQuoteRef()) && store.equals(facts.storeId())
                    && priceScope.get("tradeTenantId").equals(facts.tradeTenantId())
                    && priceScope.get("scopeVersion").equals(facts.scopeVersion()) && "CNY".equals(facts.currency())
                    && matches(facts.priceProofHash(), "[0-9a-f]{64}") && positive(facts.orderId()) > 0
                    && facts.orderExpiresAt() != null && facts.financialStatus() != null && facts.orderStatus() != null);
            return facts;
        } catch (RestClientResponseException rejected) {
            if (rejected.getStatusCode().value() == 404) throw mismatch();
            throw unavailable();
        } catch (RestClientException unavailable) {
            throw unavailable();
        }
    }

    private String sign(String tenant, String reference, OrderFacts facts, String application) {
        return SignedTokenCodec.encode(keyId, signingKey, Map.ofEntries(
            Map.entry("purpose", RECEIPT_PURPOSE), Map.entry("marketingTenantId", tenant),
            Map.entry("tradeTenantId", facts.tradeTenantId()), Map.entry("tradeQuoteRef", reference),
            Map.entry("orderId", facts.orderId()), Map.entry("scopeVersion", facts.scopeVersion()),
            Map.entry("storeId", facts.storeId()), Map.entry("priceProofHash", facts.priceProofHash()),
            Map.entry("currency", facts.currency()), Map.entry("discountMinor", facts.discountMinor()),
            Map.entry("payableMinor", facts.payableMinor()), Map.entry("applicationId", application),
            Map.entry("expiresAt", facts.orderExpiresAt().toString())));
    }

    private void requireScope(TenantScope scope, String reference) {
        if (!scope.permissions().contains("promotion:checkout-pilot") || !actor.equals(scope.actorId())
                || !priceScope.get("marketingTenantId").equals(scope.tenantId().value())) {
            throw new ForbiddenException("CHECKOUT_FUNDING_FORBIDDEN", "当前机器未获试点预算授权");
        }
        scope.requireOrganization(priceScope.get("organizationId"));
        scope.requireShop(priceScope.get("shopId"));
        if (!matches(reference, REFERENCE)) throw new IllegalArgumentException("试点报价引用格式无效");
    }

    private static Reservation replay(CheckoutBindingRow row, String hash) {
        require(hash.equals(row.tokenDigest()));
        return response(row);
    }

    private static Reservation response(CheckoutBindingRow row) {
        return new Reservation(row.applicationId(), row.state(), "RESERVED".equals(row.state()) ? row.receipt() : null);
    }

    private static boolean validOrigin(URI origin) {
        return origin.getHost() != null && origin.getUserInfo() == null && origin.getQuery() == null && origin.getFragment() == null
            && Set.of("", "/").contains(origin.getPath()) && ("https".equalsIgnoreCase(origin.getScheme())
                || ("http".equalsIgnoreCase(origin.getScheme()) && Set.of("127.0.0.1", "localhost", "[::1]").contains(origin.getHost())));
    }

    private static long positive(String text) {
        if (!matches(text, "[1-9][0-9]{0,18}")) throw mismatch();
        try { return Long.parseLong(text); } catch (NumberFormatException invalid) { throw mismatch(); }
    }

    private static Instant checkedTime(String value) {
        try { return Instant.parse(value); } catch (java.time.DateTimeException | NullPointerException invalid) { throw mismatch(); }
    }

    private static boolean matches(String input, String pattern) { return input != null && input.matches(pattern); }
    private static void require(boolean valid) { if (!valid) throw mismatch(); }
    private static ConflictException mismatch() {
        return new ConflictException("CHECKOUT_FUNDING_EVIDENCE_MISMATCH", "试点报价、预算或交易订单证据无法核对");
    }
    private static DependencyUnavailableException unavailable() {
        return new DependencyUnavailableException("CHECKOUT_TRADE_UNAVAILABLE", "交易订单证据暂不可用");
    }

    /** 仅预留状态返回付款凭证，可在机器重试时取得已提交的同一份签名。 */
    public record Reservation(String applicationId, String state, String fundingReceipt) { }

    /** 交易持有该订单的资金状态，响应中不需要个人资料。 */
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private record OrderFacts(String tradeTenantId, String tradeQuoteRef, String orderId, String scopeVersion, String storeId,
            String priceProofHash, String currency, String grossMinor, String discountMinor, String payableMinor,
            String orderStatus, String financialStatus, Instant paidAt, Instant orderExpiresAt,
            String orderNo, String businessNo) {
        String readableOrderNo() {
            String number = businessNo == null ? orderNo : businessNo;
            return matches(number, "TRD-ORD-[0-9]{6}-[0-9]{10}") ? number : null;
        }
    }
}
