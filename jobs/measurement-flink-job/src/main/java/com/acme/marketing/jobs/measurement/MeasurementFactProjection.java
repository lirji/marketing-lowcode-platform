package com.acme.marketing.jobs.measurement;

import com.acme.marketing.contracts.event.MeasurementProjectionDelta;
import com.acme.marketing.platform.crypto.Digests;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class MeasurementFactProjection {
    private static final int MAX_CORRECTION_CHAIN = 100;

    public Result apply(MeasurementProjectionState current, MeasurementFactMessage message) {
        if (current == null) return initial(message);
        verifyIdentity(current, message);
        if (current.processedEventIds().contains(message.eventId())) {
            return new Result(current, List.of(), true);
        }
        if (message.correctionOf().isBlank()) {
            throw new IllegalArgumentException("event id collision without correctionOf");
        }
        if (!message.correctionRootId().equals(current.rootEventId())) {
            throw new IllegalArgumentException("correctionRootId must reference the immutable root event id");
        }
        if (!message.correctionOf().equals(current.activeEventId())) {
            throw new IllegalArgumentException("correctionOf must reference the currently active event");
        }
        if (current.processedEventIds().size() >= MAX_CORRECTION_CHAIN) {
            throw new IllegalStateException("measurement correction chain limit exceeded");
        }

        long revision = current.revision() + 1;
        FactContribution replacement = contribution(message);
        List<MeasurementProjectionDelta> deltas = List.of(
                delta(current.tenantId(), current.rootEventId(), message.eventId(), revision,
                        MeasurementProjectionDelta.Operation.REVERSE, current.active()),
                delta(current.tenantId(), current.rootEventId(), message.eventId(), revision,
                        MeasurementProjectionDelta.Operation.APPLY, replacement));
        Set<String> processed = new HashSet<>(current.processedEventIds());
        processed.add(message.eventId());
        return new Result(new MeasurementProjectionState(current.tenantId(), current.rootEventId(),
                message.eventId(), revision, replacement, processed, current.expiresAtEpochMillis()),
                deltas, false);
    }

    private static Result initial(MeasurementFactMessage message) {
        if (!message.correctionOf().isBlank()) {
            throw new IllegalArgumentException("correction target has not been observed");
        }
        FactContribution contribution = contribution(message);
        MeasurementProjectionState state = new MeasurementProjectionState(message.tenantId(), message.eventId(),
                message.eventId(), 1, contribution, Set.of(message.eventId()), 1);
        return new Result(state, List.of(delta(message.tenantId(), message.eventId(), message.eventId(),
                1, MeasurementProjectionDelta.Operation.APPLY, contribution)), false);
    }

    private static FactContribution contribution(MeasurementFactMessage message) {
        Map<String, String> attributes = message.attributes();
        long revenue = number(attributes, "revenueMinor");
        if (message.type() == com.acme.marketing.contracts.event.MarketingFact.Type.REFUND) {
            revenue = Math.negateExact(revenue);
        }
        return new FactContribution(message.type(), message.businessKey(), attributes.getOrDefault("campaignId", ""),
                attributes.getOrDefault("experimentId", ""), attributes.getOrDefault("variantId", ""),
                Digests.sha256Hex(message.subjectToken()), revenue,
                number(attributes, "costMinor"), message.occurredAtEpochMillis(), message.ingestedAtEpochMillis());
    }

    private static MeasurementProjectionDelta delta(
            String tenantId,
            String rootEventId,
            String sourceEventId,
            long revision,
            MeasurementProjectionDelta.Operation operation,
            FactContribution contribution) {
        long factor = operation == MeasurementProjectionDelta.Operation.APPLY ? 1 : -1;
        String deltaId = Digests.sha256Hex(tenantId + ':' + rootEventId + ':' + sourceEventId
                + ':' + revision + ':' + operation);
        return new MeasurementProjectionDelta("MEASUREMENT_PROJECTION_DELTA", deltaId, tenantId,
                rootEventId, sourceEventId, revision, operation, contribution.type(), contribution.businessKey(),
                contribution.campaignId(), contribution.experimentId(), contribution.variantId(),
                contribution.subjectHash(), factor,
                Math.multiplyExact(factor, contribution.revenueMinor()),
                Math.multiplyExact(factor, contribution.costMinor()), contribution.occurredAtEpochMillis(),
                contribution.ingestedAtEpochMillis());
    }

    private static long number(Map<String, String> attributes, String name) {
        String value = attributes.get(name);
        return value == null || value.isBlank() ? 0 : Long.parseLong(value);
    }

    private static void verifyIdentity(MeasurementProjectionState current, MeasurementFactMessage message) {
        if (!current.tenantId().equals(message.tenantId())
                || !current.rootEventId().equals(message.rootEventId())) {
            throw new IllegalArgumentException("measurement fact partition identity mismatch");
        }
        FactContribution candidate = contribution(message);
        if (current.active().type() != candidate.type()
                || !current.active().businessKey().equals(candidate.businessKey())
                || !current.active().subjectHash().equals(candidate.subjectHash())) {
            throw new IllegalArgumentException("measurement correction cannot change root identity");
        }
    }

    public record Result(
            MeasurementProjectionState state, List<MeasurementProjectionDelta> deltas, boolean duplicate) {
        public Result {
            deltas = List.copyOf(deltas);
        }
    }
}
