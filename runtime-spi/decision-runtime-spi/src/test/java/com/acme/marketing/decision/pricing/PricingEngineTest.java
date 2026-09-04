package com.acme.marketing.decision.pricing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.acme.marketing.decision.model.Cart;
import com.acme.marketing.decision.model.CartLine;
import com.acme.marketing.decision.model.FundingRule;
import com.acme.marketing.decision.model.HitPolicy;
import com.acme.marketing.decision.model.Money;
import com.acme.marketing.decision.model.OfferCandidate;
import com.acme.marketing.decision.model.PriceStage;
import com.acme.marketing.decision.model.PromotionFormula;
import com.acme.marketing.decision.model.PromotionKind;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class PricingEngineTest {
    @Test
    void allocatesVeryLargeFundingAmountsWithoutIntermediateOverflow() {
        long price = Long.MAX_VALUE / 20;
        Cart cart = new Cart("CNY", List.of(line("huge", price, 0)));
        OfferCandidate offer = offer("huge-funding", PriceStage.PLATFORM,
                new PromotionFormula(PromotionKind.FIXED_OFF, 0, price, 0, price, 1),
                Set.of(), "huge", HitPolicy.SUM, Set.of(),
                List.of(new FundingRule("PLATFORM", "platform", 7_000),
                        new FundingRule("MERCHANT", "merchant-1", 3_000)));

        PricingResult result = new PricingEngine().price(cart, List.of(offer));

        assertEquals(price, result.totalDiscount().minorUnits());
        assertEquals(price, result.appliedOffers().getFirst().funding().stream()
                .mapToLong(allocation -> allocation.amount().minorUnits()).reduce(0L, Math::addExact));
    }

    @Test
    void appliesStagesConflictsFloorsFundingAndExactAllocation() {
        Cart cart = new Cart("CNY", List.of(
                line("line-a", 30_000, 10_000),
                line("line-b", 20_000, 10_000),
                new CartLine("shipping", "shipping", "shop-1", "", "", 1,
                        new Money("CNY", 1_000), Money.zero("CNY"), true)));
        OfferCandidate itemDiscount = offer("item-20", PriceStage.ITEM,
                new PromotionFormula(PromotionKind.PERCENT_OFF, 0, 0, 2_000, 0, 0),
                Set.of("line-a", "line-b"), "item", HitPolicy.SUM, Set.of(),
                List.of(new FundingRule("PLATFORM", "platform", 7_000),
                        new FundingRule("MERCHANT", "merchant-1", 3_000)));
        OfferCandidate platformBest = offer("platform-5k", PriceStage.PLATFORM,
                new PromotionFormula(PromotionKind.FIXED_OFF, 30_000, 5_000, 0, 5_000, 1),
                Set.of(), "platform", HitPolicy.BEST, Set.of(), List.of());
        OfferCandidate weakerConflict = new OfferCandidate("platform-3k", "benefit@1", PriceStage.PLATFORM,
                new PromotionFormula(PromotionKind.FIXED_OFF, 30_000, 3_000, 0, 3_000, 1), Set.of(),
                "other", HitPolicy.BEST, 0, 5, Set.of("platform-5k"), List.of(), "WEAKER");
        OfferCandidate shipping = offer("free-shipping", PriceStage.SHIPPING,
                new PromotionFormula(PromotionKind.FREE_SHIPPING, 0, 1_000, 0, 1_000, 1),
                Set.of("shipping"), "shipping", HitPolicy.SUM, Set.of(), List.of());

        PricingResult result = new PricingEngine().price(cart,
                List.of(itemDiscount, platformBest, weakerConflict, shipping));

        assertEquals(51_000, result.original().minorUnits());
        assertEquals(35_000, result.payable().minorUnits());
        assertEquals(16_000, result.totalDiscount().minorUnits());
        assertEquals(3, result.appliedOffers().size());
        assertEquals(10_000, result.appliedOffers().getFirst().funding().stream()
                .mapToLong(allocation -> allocation.amount().minorUnits()).sum());
        assertEquals(0, result.linePayables().get("shipping").minorUnits());
    }

    @Test
    void optimizesActualMarginalDiscountInsteadOfSummingStalePreviews() {
        Cart cart = new Cart("CNY", List.of(line("a", 60, 0), line("b", 40, 0)));
        OfferCandidate x = offer("x", PriceStage.PLATFORM,
                new PromotionFormula(PromotionKind.FIXED_OFF, 0, 60, 0, 60, 1), Set.of("a"), "x",
                HitPolicy.BEST, Set.of("z"), List.of());
        OfferCandidate y = offer("y", PriceStage.PLATFORM,
                new PromotionFormula(PromotionKind.FIXED_OFF, 0, 60, 0, 60, 1), Set.of("a"), "y",
                HitPolicy.BEST, Set.of("z"), List.of());
        OfferCandidate z = offer("z", PriceStage.PLATFORM,
                new PromotionFormula(PromotionKind.FIXED_OFF, 0, 100, 0, 100, 1), Set.of("a", "b"), "z",
                HitPolicy.BEST, Set.of("x", "y"), List.of());

        PricingResult result = new PricingEngine().price(cart, List.of(x, y, z));

        assertEquals(100, result.totalDiscount().minorUnits());
        assertEquals(List.of("z"), result.appliedOffers().stream().map(PricingResult.AppliedOffer::offerId).toList());
    }

    @Test
    void standardSumPolicyHandlesMoreThanTwentyCandidatesWithoutCombinatorialSearch() {
        Cart cart = new Cart("CNY", List.of(line("a", 100, 0)));
        List<OfferCandidate> offers = IntStream.range(0, 30).mapToObj(index -> offer("offer-" + index,
                PriceStage.PLATFORM, new PromotionFormula(PromotionKind.FIXED_OFF, 0, 1, 0, 1, 1),
                Set.of("a"), "group-" + index, HitPolicy.SUM, Set.of(), List.of())).toList();

        PricingResult result = new PricingEngine().price(cart, offers);

        assertEquals(30, result.totalDiscount().minorUnits());
    }

    @Test
    void exactSolverEscapesSingleReplacementGreedyTrap() {
        Cart cart = new Cart("CNY", List.of(line("line", 100, 0)));
        List<OfferCandidate> offers = new java.util.ArrayList<>();
        offers.add(offer("a", PriceStage.PLATFORM,
                new PromotionFormula(PromotionKind.FIXED_OFF, 0, 10, 0, 10, 1), Set.of("line"), "group-a",
                HitPolicy.BEST, Set.of("b", "c"), List.of()));
        offers.add(offer("b", PriceStage.PLATFORM,
                new PromotionFormula(PromotionKind.FIXED_OFF, 0, 6, 0, 6, 1), Set.of("line"), "group-b",
                HitPolicy.BEST, Set.of("a"), List.of()));
        offers.add(offer("c", PriceStage.PLATFORM,
                new PromotionFormula(PromotionKind.FIXED_OFF, 0, 6, 0, 6, 1), Set.of("line"), "group-c",
                HitPolicy.BEST, Set.of("a"), List.of()));
        IntStream.range(0, 11).forEach(index -> offers.add(offer("filler-" + index, PriceStage.PLATFORM,
                new PromotionFormula(PromotionKind.FIXED_OFF, 0, 1, 0, 1, 1), Set.of("line"),
                "filler-group-" + index, HitPolicy.BEST, Set.of(), List.of())));

        PricingResult result = new PricingEngine().price(cart, offers);

        assertEquals(23, result.totalDiscount().minorUnits());
        assertFalse(result.appliedOffers().stream().anyMatch(applied -> applied.offerId().equals("a")));
    }

    @Test
    void rejectsAdvancedGraphBeyondPublishedExactBoundary() {
        Cart cart = new Cart("CNY", List.of(line("line", 100, 0)));
        List<OfferCandidate> offers = IntStream.range(0, OfferSelector.MAX_OPTIONAL_CANDIDATES + 1)
                .mapToObj(index -> offer("advanced-" + index, PriceStage.PLATFORM,
                        new PromotionFormula(PromotionKind.FIXED_OFF, 0, 1, 0, 1, 1), Set.of("line"),
                        "advanced-group-" + index, HitPolicy.BEST, Set.of(), List.of()))
                .toList();

        assertThrows(IllegalArgumentException.class, () -> new PricingEngine().price(cart, offers));
    }

    private static CartLine line(String id, long unitPrice, long floor) {
        return new CartLine(id, "sku-" + id, "shop-1", "cat-1", "brand-1", 1,
                new Money("CNY", unitPrice), new Money("CNY", floor), false);
    }

    private static OfferCandidate offer(
            String id,
            PriceStage stage,
            PromotionFormula formula,
            Set<String> lineIds,
            String group,
            HitPolicy policy,
            Set<String> incompatible,
            List<FundingRule> funding) {
        return new OfferCandidate(id, "benefit@1", stage, formula, lineIds, group, policy, 0, 10,
                incompatible, funding, id.toUpperCase());
    }
}
