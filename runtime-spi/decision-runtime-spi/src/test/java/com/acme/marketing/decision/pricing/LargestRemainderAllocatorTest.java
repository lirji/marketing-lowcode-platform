package com.acme.marketing.decision.pricing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

class LargestRemainderAllocatorTest {
    @Property(tries = 300)
    void proportionalAllocationHandlesTotalsLargerThanTheWeightSum(
            @ForAll("positiveWeights") Map<String, Long> weights) {
        long total = Long.MAX_VALUE / 4;
        Map<String, Long> result = new LargestRemainderAllocator().allocateProportionally(total, weights);

        assertEquals(total, result.values().stream().reduce(0L, Math::addExact));
        result.values().forEach(value -> assertTrue(value >= 0));
    }

    @Property(tries = 500)
    void alwaysConservesAndRespectsLineHeadroom(
            @ForAll("positiveTotal") int total, @ForAll("positiveWeights") Map<String, Long> weights) {
        long sum = weights.values().stream().mapToLong(Long::longValue).sum();
        long boundedTotal = Math.min(total, sum);
        Map<String, Long> result = new LargestRemainderAllocator().allocate(boundedTotal, weights);

        assertEquals(boundedTotal, result.values().stream().mapToLong(Long::longValue).sum());
        result.forEach((key, value) -> assertTrue(value >= 0 && value <= weights.get(key)));
    }

    @Provide
    Arbitrary<Integer> positiveTotal() {
        return Arbitraries.integers().between(0, 1_000_000);
    }

    @Provide
    Arbitrary<Map<String, Long>> positiveWeights() {
        return Arbitraries.longs().between(1, 1_000_000).list().ofMinSize(1).ofMaxSize(20).map(values -> {
            Map<String, Long> result = new LinkedHashMap<>();
            for (int index = 0; index < values.size(); index++) {
                result.put("line-" + index, values.get(index));
            }
            return result;
        });
    }
}
