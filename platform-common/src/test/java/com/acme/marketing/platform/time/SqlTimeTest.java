package com.acme.marketing.platform.time;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.Test;

class SqlTimeTest {
    @Test
    void lexicalOrderIsChronologicalAcrossVariableInstantFractions() {
        List<Instant> instants = List.of(
                Instant.parse("2026-01-01T00:00:00.100001Z"),
                Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2025-12-31T23:59:59.999999999Z"),
                Instant.parse("2026-01-01T00:00:00.100Z"));

        List<String> lexical = instants.stream().map(SqlTime::format).sorted().toList();
        List<String> chronological = new ArrayList<>(instants).stream()
                .sorted(Comparator.naturalOrder()).map(SqlTime::format).toList();

        assertEquals(chronological, lexical);
        assertEquals("2026-01-01T00:00:00.000000000Z", SqlTime.format(instants.get(1)));
    }
}
