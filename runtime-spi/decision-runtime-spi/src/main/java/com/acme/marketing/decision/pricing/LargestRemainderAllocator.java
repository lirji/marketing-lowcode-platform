package com.acme.marketing.decision.pricing;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class LargestRemainderAllocator {
    public Map<String, Long> allocate(long total, Map<String, Long> weights) {
        return allocate(total, weights, true);
    }

    /** Allocates an arbitrary total by relative weights rather than treating weights as capacity. */
    public Map<String, Long> allocateProportionally(long total, Map<String, Long> weights) {
        return allocate(total, weights, false);
    }

    private Map<String, Long> allocate(long total, Map<String, Long> weights, boolean boundedByWeight) {
        if (total < 0 || weights.isEmpty() || weights.values().stream().anyMatch(weight -> weight < 0)) {
            throw new IllegalArgumentException("allocation input is invalid");
        }
        long weightSum = weights.values().stream().reduce(0L, Math::addExact);
        if ((boundedByWeight && total > weightSum) || (total > 0 && weightSum == 0)) {
            throw new IllegalArgumentException("allocation exceeds available weight");
        }
        if (total == 0) {
            Map<String, Long> zeroes = new LinkedHashMap<>();
            weights.keySet().stream().sorted().forEach(key -> zeroes.put(key, 0L));
            return Map.copyOf(zeroes);
        }

        BigInteger denominator = BigInteger.valueOf(weightSum);
        List<Share> shares = new ArrayList<>();
        long allocated = 0;
        for (Map.Entry<String, Long> entry : weights.entrySet()) {
            BigInteger numerator = BigInteger.valueOf(total).multiply(BigInteger.valueOf(entry.getValue()));
            BigInteger[] division = numerator.divideAndRemainder(denominator);
            long base = division[0].longValueExact();
            shares.add(new Share(entry.getKey(), base, division[1]));
            allocated = Math.addExact(allocated, base);
        }
        long remainderUnits = total - allocated;
        shares.sort(Comparator.comparing(Share::remainder).reversed().thenComparing(Share::key));
        for (int index = 0; index < remainderUnits; index++) {
            Share share = shares.get(index);
            shares.set(index, new Share(share.key(), share.base() + 1, share.remainder()));
        }
        shares.sort(Comparator.comparing(Share::key));
        Map<String, Long> result = new LinkedHashMap<>();
        shares.forEach(share -> result.put(share.key(), share.base()));
        return Map.copyOf(result);
    }

    private record Share(String key, long base, BigInteger remainder) {
    }
}
