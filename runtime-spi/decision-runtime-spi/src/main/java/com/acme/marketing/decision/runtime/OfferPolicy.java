package com.acme.marketing.decision.runtime;

import com.acme.marketing.decision.model.Cart;
import com.acme.marketing.decision.model.CartLine;
import com.acme.marketing.decision.model.OfferCandidate;
import com.acme.marketing.decision.model.PriceStage;
import com.acme.marketing.contracts.offer.OfferContractValidation;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

public record OfferPolicy(
        OfferCandidate candidate,
        Instant validFrom,
        Instant validTo,
        Set<String> channels,
        Set<String> skuIds,
        Set<String> categoryIds,
        Set<String> shopIds,
        String requiredAudienceSnapshotId,
        String pricingCurrency) {
    public OfferPolicy(OfferCandidate candidate, Instant validFrom, Instant validTo, Set<String> channels,
            Set<String> skuIds, Set<String> categoryIds, Set<String> shopIds, String requiredAudienceSnapshotId) {
        this(candidate, validFrom, validTo, channels, skuIds, categoryIds, shopIds, requiredAudienceSnapshotId, "");
    }

    public OfferPolicy {
        if (candidate == null || validFrom == null || validTo == null || !validTo.isAfter(validFrom)) {
            throw new IllegalArgumentException("offer policy validity is invalid");
        }
        channels = Set.copyOf(channels == null ? Set.of() : channels);
        skuIds = Set.copyOf(skuIds == null ? Set.of() : skuIds);
        categoryIds = Set.copyOf(categoryIds == null ? Set.of() : categoryIds);
        shopIds = Set.copyOf(shopIds == null ? Set.of() : shopIds);
        requiredAudienceSnapshotId = requiredAudienceSnapshotId == null ? "" : requiredAudienceSnapshotId;
        pricingCurrency = pricingCurrency == null ? "" : pricingCurrency;
        if (!pricingCurrency.isEmpty()) OfferContractValidation.requireCurrency(pricingCurrency);
    }

    /** 带币种的制品按匹配商品行计算，兼容旧制品只以商品范围判断整单准入的约定。 */
    public Optional<OfferCandidate> candidateFor(Cart cart) {
        if (!pricingCurrency.isEmpty() && !pricingCurrency.equals(cart.currency())) return Optional.empty();
        if (skuIds.isEmpty() && categoryIds.isEmpty() && shopIds.isEmpty()) {
            return Optional.of(candidate);
        }
        if (pricingCurrency.isEmpty() || candidate.stage() == PriceStage.SHIPPING) {
            return cart.lines().stream().anyMatch(this::matchesProduct) ? Optional.of(candidate) : Optional.empty();
        }
        var configuredLines = candidate.eligibleLineIds();
        Set<String> eligibleLines = cart.lines().stream()
                .filter(line -> !line.shippingLine() && matchesProduct(line))
                .map(CartLine::lineId)
                .filter(id -> configuredLines.isEmpty() || configuredLines.contains(id))
                .collect(Collectors.toUnmodifiableSet());
        // PricingEngine 的空集合表示全单；无匹配时必须拒绝候选。
        if (eligibleLines.isEmpty()) return Optional.empty();
        return Optional.of(new OfferCandidate(candidate.offerId(), candidate.benefitDefinitionVersion(), candidate.stage(),
                candidate.formula(), eligibleLines, candidate.stackGroup(), candidate.hitPolicy(), candidate.maxGroupHits(),
                candidate.priority(), candidate.incompatibleOfferIds(), candidate.fundingRules(), candidate.reasonCode()));
    }

    private boolean matchesProduct(CartLine line) {
        return (skuIds.isEmpty() || skuIds.contains(line.skuId()))
                && (categoryIds.isEmpty() || categoryIds.contains(line.categoryId()))
                && (shopIds.isEmpty() || shopIds.contains(line.shopId()));
    }
}
