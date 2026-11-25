package com.acme.marketing.decision.application;

import com.acme.marketing.decision.application.DecisionApplicationService.DecisionRequest;
import com.acme.marketing.platform.error.ForbiddenException;
import com.acme.marketing.platform.identity.TenantScope;
import java.time.Clock;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 交易签价由一组固定机器、营销范围和活动组合授权，重放前也重新检查。 */
@Component
@ConditionalOnProperty(name = "marketing.checkout-pilot.enabled", havingValue = "true")
public class CheckoutPilotAuthority {
    private static final String PERMISSION = "decision:checkout-pilot";
    private static final String DENIED = "CHECKOUT_PILOT_SCOPE_DENIED";
    private static final int MAX_ITEMS = 50;
    private static final int MAX_QUANTITY = 10_000;
    private static final long MAX_QUOTE_SECONDS = 305;
    private static final String COMMAND_PREFIX = "checkout:";
    private final Clock clock;
    private final String tenant;
    private final String actor;
    private final String organization;
    private final String shop;
    private final String campaign;
    private final String version;
    private final String sku;
    private final String funder;
    private final String tradeTenant;

    /** 已启用时要求完整、明确的范围，避免把运营账号误作交易签发机器。 */
    public CheckoutPilotAuthority(Clock clock, @Value("${marketing.checkout-pilot.tenant}") String tenant,
            @Value("${marketing.checkout-pilot.actor}") String actor,
            @Value("${marketing.checkout-pilot.organization}") String organization,
            @Value("${marketing.checkout-pilot.shop}") String shop,
            @Value("${marketing.checkout-pilot.campaign}") String campaign,
            @Value("${marketing.checkout-pilot.scope-version}") String version,
            @Value("${marketing.checkout-pilot.sku}") String sku,
            @Value("${marketing.checkout-pilot.funder}") String funder,
            @Value("${marketing.checkout-pilot.trade-tenant}") String tradeTenant) {
        if (actor.isBlank() || actor.length() > 255 || "*".equals(actor)
                || !List.of(tenant, organization, shop, campaign, version, sku, funder).stream()
                    .allMatch(value -> value.matches(CheckoutQuoteProof.IDENTIFIER))
                || !tradeTenant.matches("[1-9][0-9]{0,18}") || Long.parseLong(tradeTenant) <= 0) {
            throw new IllegalArgumentException("试点签价需要指定机器、完整业务范围及原生交易租户");
        }
        this.clock = clock;
        this.tenant = tenant;
        this.actor = actor;
        this.organization = organization;
        this.shop = shop;
        this.campaign = campaign;
        this.version = version;
        this.sku = sku;
        this.funder = funder;
        this.tradeTenant = tradeTenant;
    }

    /** 以已签参与编号匹配交易报价引用，入口仅允许本范围的 WEB 原价购物篮。 */
    public void require(TenantScope scope, DecisionRequest request) {
        requirePermission(scope);
        if (!tenant.equals(scope.tenantId().value()) || !actor.equals(scope.actorId())) {
            throw new ForbiddenException(DENIED, "机器未获该试点签价权限");
        }
        var checkout = request.checkout();
        var bundle = request.bundleContext();
        if (checkout == null || bundle == null || !matches(checkout.tradeQuoteRef(), CheckoutQuoteProof.QUOTE_REF)
                || !matches(checkout.sourceDigest(), CheckoutQuoteProof.DIGEST)
                || !request.subjectToken().matches(CheckoutQuoteProof.DIGEST)
                || !checkout.tradeQuoteRef().equals(bundle.participationId())
                || !checkout.tradeQuoteRef().equals(request.requestId())
                || !(COMMAND_PREFIX + checkout.tradeQuoteRef()).equals(request.idempotencyKey())
                || !request.orderId().isEmpty() || !request.audienceSnapshotIds().isEmpty()
                || !CheckoutQuoteProof.WEB.equals(request.channel()) || checkout.expiresAt() == null
                || !CheckoutQuoteProof.CNY.equals(request.cart().currency())
                || request.cart().lines().size() > MAX_ITEMS
                || request.cart().lines().stream().anyMatch(line -> !line.lineId().equals(line.skuId())
                    || !matches(line.skuId(), CheckoutQuoteProof.IDENTIFIER)
                    || !line.categoryId().isEmpty() || !line.brandId().isEmpty()
                    || line.floorUnitPrice().minorUnits() != 0 || line.quantity() > MAX_QUANTITY || line.shippingLine())) {
            throw new IllegalArgumentException("试点报价引用、有效期或购物篮格式无效");
        }
        var now = clock.instant();
        if (!checkout.expiresAt().isAfter(now) || checkout.expiresAt().isAfter(now.plusSeconds(MAX_QUOTE_SECONDS))) {
            throw new IllegalArgumentException("试点报价已失效或超出有效期");
        }
        if (!tradeTenant.equals(checkout.tradeTenantId()) || !organization.equals(request.organizationId())
                || !campaign.equals(bundle.campaignId()) || !version.equals(checkout.scopeVersion())
                || !sku.equals(checkout.eligibleSkuId()) || !funder.equals(checkout.funderId())
                || request.cart().lines().stream().anyMatch(line -> !shop.equals(line.shopId()))
                || request.cart().lines().stream().noneMatch(line -> sku.equals(line.skuId()))) {
            throw new ForbiddenException(DENIED, "试点报价不属于已授权门店、活动或商品");
        }
    }

    /** 单独的机器权限不能从常规决策权限推导。 */
    static void requirePermission(TenantScope scope) {
        scope.requirePermission(PERMISSION);
    }

    private static boolean matches(String value, String pattern) {
        return value != null && value.matches(pattern);
    }
}
