package com.acme.marketing.decision.pricing;

import com.acme.marketing.decision.model.HitPolicy;
import com.acme.marketing.decision.model.OfferCandidate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.ToLongFunction;

/** Exact compatibility solver with a compiler-enforced upper bound on combinatorial choices. */
public final class OfferSelector {
    public static final int MAX_OPTIONAL_CANDIDATES = 14;
    private static final int MAX_TOTAL_CANDIDATES = 1_000;
    private static final Comparator<OfferCandidate> APPLICATION_ORDER =
            Comparator.comparingInt(OfferCandidate::priority).reversed().thenComparing(OfferCandidate::offerId);

    public List<OfferCandidate> select(List<OfferCandidate> input,
            ToLongFunction<List<OfferCandidate>> objective) {
        if (input.size() > MAX_TOTAL_CANDIDATES) {
            throw new IllegalArgumentException("pricing candidate limit exceeds 1000");
        }
        CandidatePool pool = candidatePool(input);
        if (pool.optional().size() > MAX_OPTIONAL_CANDIDATES) {
            throw new IllegalArgumentException("exact pricing optional-candidate limit exceeds "
                    + MAX_OPTIONAL_CANDIDATES);
        }
        return exactCompatibleSubset(pool, objective).stream().sorted(APPLICATION_ORDER).toList();
    }

    private static CandidatePool candidatePool(List<OfferCandidate> input) {
        if (input.stream().map(OfferCandidate::offerId).distinct().count() != input.size()) {
            throw new IllegalArgumentException("pricing offer ids must be unique");
        }
        Map<String, List<OfferCandidate>> groups = new HashMap<>();
        input.forEach(candidate -> groups.computeIfAbsent(candidate.stackGroup(), ignored -> new ArrayList<>())
                .add(candidate));
        List<OfferCandidate> forced = new ArrayList<>();
        List<OfferCandidate> optional = new ArrayList<>();
        Map<String, Integer> groupLimits = new HashMap<>();
        groups.keySet().stream().sorted().forEach(group -> {
            List<OfferCandidate> ordered = groups.get(group).stream().sorted(APPLICATION_ORDER).toList();
            HitPolicy policy = ordered.getFirst().hitPolicy();
            if (ordered.stream().anyMatch(candidate -> candidate.hitPolicy() != policy)) {
                throw new IllegalArgumentException("all offers in a stack group must use the same hit policy");
            }
            switch (policy) {
                case FIRST -> {
                    forced.add(ordered.getFirst());
                    groupLimits.put(group, 1);
                }
                case SUM -> {
                    forced.addAll(ordered);
                    groupLimits.put(group, ordered.size());
                }
                case BEST -> {
                    optional.addAll(ordered);
                    groupLimits.put(group, 1);
                }
                case MAX_N -> {
                    int limit = ordered.getFirst().maxGroupHits();
                    if (ordered.stream().anyMatch(candidate -> candidate.maxGroupHits() != limit)) {
                        throw new IllegalArgumentException("MAX_N group must use one consistent limit");
                    }
                    optional.addAll(ordered);
                    groupLimits.put(group, limit);
                }
            }
        });
        forced.sort(Comparator.comparing(OfferCandidate::offerId));
        optional.sort(Comparator.comparing(OfferCandidate::offerId));
        if (!feasible(forced, groupLimits)) {
            throw new IllegalArgumentException("forced FIRST/SUM offers contain an unsatisfiable conflict");
        }
        return new CandidatePool(List.copyOf(forced), List.copyOf(optional), Map.copyOf(groupLimits));
    }

    private static List<OfferCandidate> exactCompatibleSubset(CandidatePool pool,
            ToLongFunction<List<OfferCandidate>> objective) {
        long subsetCount = 1L << pool.optional().size();
        ScoredSelection best = null;
        for (long bits = 0; bits < subsetCount; bits++) {
            List<OfferCandidate> selected = new ArrayList<>(pool.forced());
            for (int index = 0; index < pool.optional().size(); index++) {
                if ((bits & (1L << index)) != 0) selected.add(pool.optional().get(index));
            }
            if (!feasible(selected, pool.groupLimits())) continue;
            List<OfferCandidate> immutable = List.copyOf(selected);
            ScoredSelection candidate = new ScoredSelection(immutable, objective.applyAsLong(immutable));
            if (best == null || candidate.betterThan(best)) best = candidate;
        }
        if (best == null) {
            throw new IllegalArgumentException("pricing conflict graph has no feasible selection");
        }
        return best.offers();
    }

    private static boolean feasible(List<OfferCandidate> selected, Map<String, Integer> groupLimits) {
        Map<String, Integer> counts = new HashMap<>();
        Set<String> ids = new HashSet<>();
        for (OfferCandidate candidate : selected) {
            if (!ids.add(candidate.offerId())) return false;
            if (counts.merge(candidate.stackGroup(), 1, Integer::sum) > groupLimits.get(candidate.stackGroup())) {
                return false;
            }
        }
        for (int left = 0; left < selected.size(); left++) {
            for (int right = left + 1; right < selected.size(); right++) {
                if (incompatible(selected.get(left), selected.get(right))) return false;
            }
        }
        return true;
    }

    private static boolean incompatible(OfferCandidate left, OfferCandidate right) {
        return left.incompatibleOfferIds().contains(right.offerId())
                || right.incompatibleOfferIds().contains(left.offerId());
    }

    private record CandidatePool(List<OfferCandidate> forced, List<OfferCandidate> optional,
            Map<String, Integer> groupLimits) { }

    private record ScoredSelection(List<OfferCandidate> offers, long value) {
        private boolean betterThan(ScoredSelection other) {
            if (value != other.value) return value > other.value;
            return key(offers).compareTo(key(other.offers)) < 0;
        }

        private static String key(List<OfferCandidate> offers) {
            return offers.stream().map(OfferCandidate::offerId).sorted()
                    .reduce((left, right) -> left + ',' + right).orElse("");
        }
    }
}
