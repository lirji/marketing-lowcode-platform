package com.acme.marketing.decision.application;

import com.acme.marketing.decision.application.DecisionApplicationService.DecisionRequest;
import com.acme.marketing.decision.model.PriceStage;
import com.acme.marketing.decision.model.PromotionKind;
import com.acme.marketing.decision.pricing.PricingResult;
import com.acme.marketing.platform.crypto.SignedTokenCodec;
import com.acme.marketing.platform.crypto.SigningKeyRing;
import com.acme.marketing.platform.error.ConflictException;
import com.acme.marketing.platform.identity.TenantScope;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.stream.Collectors;

/** 结算报价只证明冻结价格的依据，签名格式与可预留的原生订单报价隔离。 */
final class CheckoutQuoteProof {
    private static final String PURPOSE = "marketing.checkout-price/v1";
    private static final String PRICE_CONFLICT = "CHECKOUT_PILOT_PRICING_CONFLICT";
    static final String PLATFORM = "PLATFORM";
    static final String WEB = "WEB";
    static final String CNY = "CNY";
    static final String IDENTIFIER = "[A-Za-z0-9_.:@/-]{1,64}";
    static final String QUOTE_REF = "[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}";
    static final String DIGEST = "[0-9a-f]{64}";

    private CheckoutQuoteProof() { }

    /** 仅签一笔目标 SKU 的店铺满减；其他商品可在购物篮内，金额保持原价。 */
    static String sign(SigningKeyRing keys, TenantScope scope, DecisionRequest request, PricingResult result,
            String quoteId, long generation, Instant now) {
        var checkout = request.checkout();
        var offers = result.appliedOffers();
        if (offers.size() != 1 || offers.getFirst().kind() != PromotionKind.FIXED_OFF
                || offers.getFirst().stage() != PriceStage.SHOP || offers.getFirst().funding().size() != 1
                || result.totalDiscount().minorUnits() <= 0 || !checkout.expiresAt().isAfter(now)) {
            throw invalidPricing();
        }
        var offer = offers.getFirst();
        var funder = offer.funding().getFirst();
        if (!PLATFORM.equals(funder.funderType()) || !checkout.funderId().equals(funder.funderId())
                || !result.totalDiscount().equals(funder.amount())) {
            throw invalidPricing();
        }
        String lines = request.cart().lines().stream().sorted(Comparator.comparing(line -> line.lineId()))
                .map(line -> {
                    long payable = result.linePayables().get(line.lineId()).minorUnits();
                    if (!checkout.eligibleSkuId().equals(line.skuId()) && payable != line.originalTotal().minorUnits()) {
                        throw invalidPricing();
                    }
                    return String.join("~", line.lineId(), line.skuId(), Integer.toString(line.quantity()),
                            Long.toString(line.unitPrice().minorUnits()), Long.toString(payable));
                }).collect(Collectors.joining(","));
        var fields = new LinkedHashMap<String, String>();
        fields.put("purpose", PURPOSE);
        fields.put("marketingTenantId", scope.tenantId().value());
        fields.put("tradeTenantId", checkout.tradeTenantId());
        fields.put("tradeQuoteRef", checkout.tradeQuoteRef());
        fields.put("scopeVersion", checkout.scopeVersion());
        fields.put("sourceDigest", checkout.sourceDigest());
        fields.put("organizationId", request.organizationId());
        fields.put("shopId", request.cart().lines().getFirst().shopId());
        fields.put("ownerRef", request.subjectToken());
        fields.put("cartDigest", request.cart().digest());
        fields.put("marketingQuoteId", quoteId);
        fields.put("campaignId", request.bundleContext().campaignId());
        fields.put("generation", Long.toString(generation));
        fields.put("bundleId", request.bundleContext().participation().bundleId());
        fields.put("bundleVersion", Long.toString(request.bundleContext().participation().bundleVersion()));
        fields.put("eligibleSkuId", checkout.eligibleSkuId());
        fields.put("offerId", offer.offerId());
        fields.put("benefitVersion", offer.benefitDefinitionVersion());
        fields.put("currency", result.original().currency());
        fields.put("grossMinor", Long.toString(result.original().minorUnits()));
        fields.put("discountMinor", Long.toString(result.totalDiscount().minorUnits()));
        fields.put("payableMinor", Long.toString(result.payable().minorUnits()));
        fields.put("funderId", funder.funderId());
        fields.put("issuedAt", now.toString());
        fields.put("expiresAt", checkout.expiresAt().toString());
        fields.put("lines", lines);
        return SignedTokenCodec.encode(keys.activeKeyId(), keys.activePrivateKey(), fields);
    }

    private static ConflictException invalidPricing() {
        return new ConflictException(PRICE_CONFLICT, "试点优惠不符合单店单品及出资约定");
    }
}
