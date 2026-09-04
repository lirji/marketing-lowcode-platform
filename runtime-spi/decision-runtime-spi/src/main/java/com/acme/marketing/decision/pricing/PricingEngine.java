package com.acme.marketing.decision.pricing;

import com.acme.marketing.decision.model.Cart;
import com.acme.marketing.decision.model.CartLine;
import com.acme.marketing.decision.model.FundingRule;
import com.acme.marketing.decision.model.Money;
import com.acme.marketing.decision.model.OfferCandidate;
import com.acme.marketing.decision.model.PriceStage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class PricingEngine {
    private final OfferSelector selector = new OfferSelector();
    private final LargestRemainderAllocator allocator = new LargestRemainderAllocator();

    public PricingResult price(Cart cart, List<OfferCandidate> candidates) {
        Map<String, CartLine> lines = cart.lines().stream().collect(Collectors.toUnmodifiableMap(
                CartLine::lineId, Function.identity()));
        Map<String, Long> payable = new LinkedHashMap<>();
        cart.lines().stream().sorted(Comparator.comparing(CartLine::lineId))
                .forEach(line -> payable.put(line.lineId(), line.originalTotal().minorUnits()));
        Map<PriceStage, List<OfferCandidate>> byStage = new EnumMap<>(PriceStage.class);
        candidates.forEach(candidate -> byStage.computeIfAbsent(candidate.stage(), ignored -> new ArrayList<>()).add(candidate));

        List<PricingResult.AppliedOffer> applied = new ArrayList<>();
        List<String> reasons = new ArrayList<>();
        for (PriceStage stage : PriceStage.values()) {
            List<OfferCandidate> stageCandidates = byStage.getOrDefault(stage, List.of()).stream()
                    .filter(candidate -> isApplicable(candidate, lines, payable, stage))
                    .toList();
            List<OfferCandidate> selected = selector.select(stageCandidates,
                    subset -> previewSubset(subset, cart.currency(), lines, payable, stage));
            for (OfferCandidate candidate : selected) {
                PricingResult.AppliedOffer result = apply(candidate, cart.currency(), lines, payable, stage);
                if (result.discount().minorUnits() > 0 || candidate.formula().createsNonMonetaryBenefit()) {
                    applied.add(result);
                    reasons.add(candidate.reasonCode());
                }
            }
        }

        Map<String, Money> linePayables = new LinkedHashMap<>();
        payable.forEach((lineId, amount) -> linePayables.put(lineId, new Money(cart.currency(), amount)));
        Money finalPayable = linePayables.values().stream().reduce(Money.zero(cart.currency()), Money::add);
        return new PricingResult(cart.originalTotal(), finalPayable, linePayables, applied, reasons);
    }

    private PricingResult.AppliedOffer apply(
            OfferCandidate candidate,
            String currency,
            Map<String, CartLine> lines,
            Map<String, Long> payable,
            PriceStage stage) {
        Map<String, Long> headroom = eligibleHeadroom(candidate, lines, payable, stage);
        long subtotal = eligibleSubtotal(candidate, lines, payable, stage);
        int quantity = eligibleQuantity(candidate, lines, stage);
        long requested = candidate.formula().discount(subtotal, quantity);
        long available = headroom.values().stream().reduce(0L, Math::addExact);
        long discount = Math.min(requested, available);
        Map<String, Long> allocation = discount == 0 ? zeroAllocation(headroom) : allocator.allocate(discount, headroom);
        allocation.forEach((lineId, amount) -> payable.compute(lineId, (ignored, current) -> Math.subtractExact(current, amount)));

        Map<String, Money> lineDiscounts = new LinkedHashMap<>();
        allocation.forEach((lineId, amount) -> lineDiscounts.put(lineId, new Money(currency, amount)));
        return new PricingResult.AppliedOffer(candidate.offerId(), candidate.benefitDefinitionVersion(), stage,
                candidate.formula().kind(), new Money(currency, discount), lineDiscounts,
                allocateFunding(candidate.fundingRules(), currency, discount), candidate.reasonCode());
    }

    private List<PricingResult.FundingAllocation> allocateFunding(
            List<FundingRule> rules, String currency, long discount) {
        if (rules.isEmpty()) {
            return List.of(new PricingResult.FundingAllocation("PLATFORM", "default", new Money(currency, discount)));
        }
        Map<String, Long> weights = new LinkedHashMap<>();
        rules.stream().sorted(Comparator.comparing(FundingRule::funderType).thenComparing(FundingRule::funderId))
                .forEach(rule -> weights.put(rule.funderType() + ":" + rule.funderId(), (long) rule.basisPoints()));
        Map<String, Long> amounts = discount == 0 ? zeroAllocation(weights)
                : allocator.allocateProportionally(discount, weights);
        return amounts.entrySet().stream().map(entry -> {
            int separator = entry.getKey().indexOf(':');
            return new PricingResult.FundingAllocation(entry.getKey().substring(0, separator),
                    entry.getKey().substring(separator + 1), new Money(currency, entry.getValue()));
        }).toList();
    }

    private static Map<String, Long> zeroAllocation(Map<String, Long> weights) {
        Map<String, Long> result = new LinkedHashMap<>();
        weights.keySet().stream().sorted().forEach(key -> result.put(key, 0L));
        return Map.copyOf(result);
    }

    private boolean isApplicable(
            OfferCandidate candidate, Map<String, CartLine> lines, Map<String, Long> payable, PriceStage stage) {
        return candidate.formula().applicable(eligibleSubtotal(candidate, lines, payable, stage),
                eligibleQuantity(candidate, lines, stage));
    }

    private long previewDiscount(
            OfferCandidate candidate, Map<String, CartLine> lines, Map<String, Long> payable, PriceStage stage) {
        long requested = candidate.formula().discount(eligibleSubtotal(candidate, lines, payable, stage),
                eligibleQuantity(candidate, lines, stage));
        long headroom = eligibleHeadroom(candidate, lines, payable, stage).values().stream().reduce(0L, Math::addExact);
        long monetaryValue = Math.min(requested, headroom);
        return candidate.formula().createsNonMonetaryBenefit() ? Math.max(1, monetaryValue) : monetaryValue;
    }

    private long previewSubset(List<OfferCandidate> candidates, String currency,
            Map<String, CartLine> lines, Map<String, Long> payable, PriceStage stage) {
        Map<String, Long> simulatedPayable = new LinkedHashMap<>(payable);
        long value = 0;
        for (OfferCandidate candidate : candidates.stream()
                .sorted(Comparator.comparingInt(OfferCandidate::priority).reversed()
                        .thenComparing(OfferCandidate::offerId)).toList()) {
            if (!isApplicable(candidate, lines, simulatedPayable, stage)) continue;
            PricingResult.AppliedOffer applied = apply(candidate, currency, lines, simulatedPayable, stage);
            value = Math.addExact(value, applied.discount().minorUnits());
            if (candidate.formula().createsNonMonetaryBenefit()) value = Math.addExact(value, 1);
        }
        return value;
    }

    private static long eligibleSubtotal(
            OfferCandidate candidate, Map<String, CartLine> lines, Map<String, Long> payable, PriceStage stage) {
        return eligibleIds(candidate, lines, stage).stream().map(payable::get).reduce(0L, Math::addExact);
    }

    private static int eligibleQuantity(OfferCandidate candidate, Map<String, CartLine> lines, PriceStage stage) {
        return eligibleIds(candidate, lines, stage).stream().map(lines::get).mapToInt(CartLine::quantity).sum();
    }

    private static Map<String, Long> eligibleHeadroom(
            OfferCandidate candidate, Map<String, CartLine> lines, Map<String, Long> payable, PriceStage stage) {
        Map<String, Long> result = new LinkedHashMap<>();
        for (String id : eligibleIds(candidate, lines, stage)) {
            result.put(id, Math.max(0, payable.get(id) - lines.get(id).floorTotal().minorUnits()));
        }
        return result;
    }

    private static Set<String> eligibleIds(
            OfferCandidate candidate, Map<String, CartLine> lines, PriceStage stage) {
        return lines.values().stream()
                .filter(line -> candidate.eligibleLineIds().isEmpty() || candidate.eligibleLineIds().contains(line.lineId()))
                .filter(line -> stage == PriceStage.SHIPPING ? line.shippingLine() : !line.shippingLine())
                .map(CartLine::lineId)
                .collect(Collectors.toUnmodifiableSet());
    }
}
