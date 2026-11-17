package com.acme.marketing.eventgateway;

import static org.junit.jupiter.api.Assertions.*;

import com.acme.marketing.eventgateway.application.EventIngestionService;
import com.acme.marketing.eventgateway.application.EventPayloadRouter;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** 路由器只转发来源证明，外层身份始终取已验证事件入口；签名核验归Flink消费边界。 */
class BundleJourneyRoutingTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final EventPayloadRouter router = new EventPayloadRouter(mapper);
    private final Instant now = Instant.parse("2026-11-18T00:00:00Z");
    private final Map<String, Object> reference = Map.of("artifactId", "artifact", "generation", 2,
            "activationSequence", 1, "journeyId", "journey", "journeyVersion", 2);

    @Test void startForwardsOpaqueProofWithoutTrustingItsIdentityFields() {
        var proof = Map.of("tenantId", Map.of("value", "untrusted-tenant"), "signature", "opaque-signature");
        var event = event(Map.of("tenantId", "forged", "subjectToken", "forged", "enrollmentId", "forged",
                "signal", Map.of("type", "START", "signalId", "forged", "occurredAtEpochMillis", 1),
                "planReference", reference, "bundleParticipation", proof));
        var outgoing = router.route("trusted-tenant", "receipt", event, now);
        var body = mapper.readTree(outgoing.payload());
        assertEquals("trusted-tenant:trusted-enrollment", outgoing.partitionKey());
        assertEquals("trusted-tenant", body.path("tenantId").asString());
        assertEquals("trusted-subject", body.path("subjectToken").asString());
        assertEquals("trusted-enrollment", body.path("enrollmentId").asString());
        assertEquals("trusted-event", body.path("signal").path("signalId").asString());
        assertEquals(now.toEpochMilli(), body.path("signal").path("occurredAtEpochMillis").asLong());
        assertEquals(mapper.readTree(mapper.writeValueAsString(proof)), body.path("bundleParticipation"));
    }

    @Test void laterEventsCannotReplaceTheSavedParticipationProof() {
        assertThrows(IllegalArgumentException.class, () -> router.route("tenant", "receipt",
                event(Map.of("signal", Map.of("type", "EVENT"), "bundleParticipation", Map.of("signature", "replacement"))), now));
        assertThrows(IllegalArgumentException.class, () -> router.route("tenant", "receipt",
                event(Map.of("signal", Map.of("type", "START"), "planReference", reference, "bundleParticipation", "invalid-shape")), now));
    }

    @Test void oldStartHasNoNewFieldAndStillForbidsInlinePlans() {
        var outgoing = router.route("tenant", "receipt", event(Map.of("signal", Map.of("type", "START"), "planReference", reference)), now);
        assertFalse(mapper.readTree(outgoing.payload()).has("bundleParticipation"));
        assertThrows(IllegalArgumentException.class, () -> router.route("tenant", "receipt",
                event(Map.of("signal", Map.of("type", "START"), "planReference", reference, "plan", Map.of())), now));
    }

    private EventIngestionService.InboundEvent event(Map<String, Object> data) {
        return new EventIngestionService.InboundEvent("trusted-event", "source", "JOURNEY_SIGNAL", "trusted-enrollment",
                "trusted-subject", now, "1-0-0", 1L, data);
    }
}
