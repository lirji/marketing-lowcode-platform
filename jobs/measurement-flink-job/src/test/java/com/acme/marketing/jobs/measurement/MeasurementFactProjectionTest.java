package com.acme.marketing.jobs.measurement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.acme.marketing.contracts.event.MarketingFact;
import com.acme.marketing.contracts.event.MeasurementProjectionDelta;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MeasurementFactProjectionTest {
    private final MeasurementFactProjection projection = new MeasurementFactProjection();

    @Test
    void appliesDeduplicatesAndExactlyReversesCorrections() {
        MeasurementFactMessage original = fact("fact-1", "", MarketingFact.Type.CONVERSION,
                Map.of("revenueMinor", "1000", "campaignId", "c1"));
        MeasurementFactProjection.Result applied = projection.apply(null, original);
        assertEquals(1, applied.deltas().size());
        assertEquals(1_000, applied.deltas().getFirst().revenueDeltaMinor());

        MeasurementFactProjection.Result duplicate = projection.apply(applied.state(), original);
        assertTrue(duplicate.duplicate());
        assertTrue(duplicate.deltas().isEmpty());

        MeasurementFactMessage correction = fact("fact-2", "fact-1", MarketingFact.Type.CONVERSION,
                Map.of("revenueMinor", "700", "campaignId", "c1"));
        MeasurementFactProjection.Result corrected = projection.apply(applied.state(), correction);
        assertEquals(2, corrected.deltas().size());
        assertEquals(-1_000, corrected.deltas().getFirst().revenueDeltaMinor());
        assertEquals(700, corrected.deltas().getLast().revenueDeltaMinor());
        assertEquals(2, corrected.state().revision());

        MeasurementFactMessage secondCorrection = fact("fact-3", "fact-2", MarketingFact.Type.CONVERSION,
                Map.of("revenueMinor", "900", "campaignId", "c1"));
        MeasurementFactProjection.Result correctedAgain = projection.apply(corrected.state(), secondCorrection);
        assertEquals(-700, correctedAgain.deltas().getFirst().revenueDeltaMinor());
        assertEquals(900, correctedAgain.deltas().getLast().revenueDeltaMinor());
        assertEquals("fact-1", correctedAgain.state().rootEventId());
    }

    @Test
    void rejectsOrphanCorrectionAndFakeExposure() {
        assertThrows(IllegalArgumentException.class,
                () -> projection.apply(null, fact("fix", "missing", MarketingFact.Type.CONVERSION, Map.of())));
        assertThrows(IllegalArgumentException.class,
                () -> fact("exposure", "", MarketingFact.Type.EXPOSURE, Map.of("actualAction", "false")));
    }

    @Test
    void correctionCannotChangeRootTypeBusinessKeyOrSubject() {
        MeasurementProjectionState state = projection.apply(null,
                fact("fact-1", "", MarketingFact.Type.CONVERSION, Map.of())).state();
        MeasurementFactMessage wrongType = fact("fact-2", "fact-1", MarketingFact.Type.COST, Map.of());
        assertThrows(IllegalArgumentException.class, () -> projection.apply(state, wrongType));
        MeasurementFactMessage wrongBusiness = new MeasurementFactMessage("fact-3", "t1",
                MarketingFact.Type.CONVERSION, "order-2", "subject", "2026-09-02T08:00:00Z",
                "2026-09-02T08:00:01Z", "1.0.0", Map.of(), "fact-1", "fact-1");
        assertThrows(IllegalArgumentException.class, () -> projection.apply(state, wrongBusiness));
        MeasurementFactMessage wrongSubject = new MeasurementFactMessage("fact-4", "t1",
                MarketingFact.Type.CONVERSION, "order-1", "other-subject", "2026-09-02T08:00:00Z",
                "2026-09-02T08:00:01Z", "1.0.0", Map.of(), "fact-1", "fact-1");
        assertThrows(IllegalArgumentException.class, () -> projection.apply(state, wrongSubject));
    }

    @Test
    void parksAndAutomaticallyReplaysCorrectionsThatArriveBeforeTheirParents() {
        MeasurementReorderBuffer reorder = new MeasurementReorderBuffer();
        MeasurementFactMessage correction = fact("fact-2", "fact-1", MarketingFact.Type.CONVERSION,
                Map.of("revenueMinor", "700"));
        MeasurementReorderBuffer.Result parked = reorder.apply(null, correction, 1_000, 60_000);
        assertEquals(MeasurementReorderBuffer.Outcome.PARKED, parked.outcome());
        assertEquals(1, parked.state().pending().size());

        MeasurementFactMessage original = fact("fact-1", "", MarketingFact.Type.CONVERSION,
                Map.of("revenueMinor", "1000"));
        MeasurementReorderBuffer.Result replayed = reorder.apply(parked.state(), original, 2_000, 60_000);

        assertEquals(3, replayed.deltas().size());
        assertEquals("fact-2", replayed.state().projection().activeEventId());
        assertTrue(replayed.state().pending().isEmpty());
        assertEquals(700, replayed.state().projection().active().revenueMinor());
    }

    private static MeasurementFactMessage fact(
            String eventId, String correctionOf, MarketingFact.Type type, Map<String, String> attributes) {
        return new MeasurementFactMessage(eventId, "t1", type, "order-1", "subject",
                "2026-09-02T08:00:00Z", "2026-09-02T08:00:01Z", "1.0.0", attributes, correctionOf,
                correctionOf.isBlank() ? eventId : "fact-1");
    }
}
