package com.acme.marketing.jobs.measurement;

import com.acme.marketing.contracts.event.MeasurementProjectionDelta;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Bounded keyed parking/replay for corrections that overtake their parent. */
public final class MeasurementReorderBuffer {
    private static final int MAX_PENDING = 100;
    private final MeasurementFactProjection projection = new MeasurementFactProjection();

    public Result apply(MeasurementRuntimeState current, MeasurementFactMessage message,
            long now, long stateTtlMillis) {
        MeasurementProjectionState active = current == null ? null : current.projection();
        Map<String, MeasurementFactMessage> pending = new LinkedHashMap<>(
                current == null ? Map.of() : current.pending());
        verifyPartition(active, pending, message);
        if ((active != null && active.processedEventIds().contains(message.eventId()))
                || pending.containsKey(message.eventId())) {
            return new Result(state(active, pending, now, stateTtlMillis), List.of(), List.of(), Outcome.DUPLICATE);
        }

        List<MeasurementProjectionDelta> deltas = new ArrayList<>();
        List<RejectedCorrection> rejected = new ArrayList<>();
        if (!ready(active, message)) {
            if (message.correctionOf().isBlank()) {
                throw new IllegalArgumentException("a second original event cannot share a correction root");
            }
            if (active != null && active.processedEventIds().contains(message.correctionOf())) {
                throw new IllegalArgumentException("correctionOf references a superseded event");
            }
            if (pending.size() >= MAX_PENDING) {
                throw new IllegalStateException("measurement correction parking limit exceeded");
            }
            pending.put(message.eventId(), message);
            return new Result(state(active, pending, now, stateTtlMillis), List.of(), List.of(), Outcome.PARKED);
        }

        MeasurementFactProjection.Result applied = projection.apply(active, message);
        active = applied.state();
        deltas.addAll(applied.deltas());
        boolean progressed;
        do {
            progressed = false;
            MeasurementProjectionState currentActive = active;
            MeasurementFactMessage next = pending.values().stream()
                    .filter(candidate -> candidate.correctionOf().equals(currentActive.activeEventId()))
                    .sorted(Comparator.comparingLong(MeasurementFactMessage::ingestedAtEpochMillis)
                            .thenComparing(MeasurementFactMessage::eventId))
                    .findFirst().orElse(null);
            if (next != null) {
                pending.remove(next.eventId());
                try {
                    MeasurementFactProjection.Result replayed = projection.apply(active, next);
                    active = replayed.state();
                    deltas.addAll(replayed.deltas());
                } catch (RuntimeException invalidChild) {
                    String reason = invalidChild.getMessage() == null
                            ? "parked correction is invalid" : invalidChild.getMessage();
                    rejected.add(new RejectedCorrection(next, reason));
                }
                progressed = true;
            }
            MeasurementProjectionState advanced = active;
            List<MeasurementFactMessage> stale = pending.values().stream()
                    .filter(candidate -> advanced.processedEventIds().contains(candidate.correctionOf())
                            && !candidate.correctionOf().equals(advanced.activeEventId()))
                    .toList();
            stale.forEach(candidate -> {
                pending.remove(candidate.eventId());
                rejected.add(new RejectedCorrection(candidate, "correction fork references a superseded event"));
            });
        } while (progressed);
        return new Result(state(active, pending, now, stateTtlMillis), deltas, rejected, Outcome.APPLIED);
    }

    private static boolean ready(MeasurementProjectionState active, MeasurementFactMessage message) {
        return active == null ? message.correctionOf().isBlank()
                : message.correctionOf().equals(active.activeEventId());
    }

    private static void verifyPartition(MeasurementProjectionState active,
            Map<String, MeasurementFactMessage> pending, MeasurementFactMessage message) {
        if (active != null && (!active.tenantId().equals(message.tenantId())
                || !active.rootEventId().equals(message.rootEventId()))) {
            throw new IllegalArgumentException("measurement fact partition identity mismatch");
        }
        if (active == null && !pending.isEmpty()) {
            MeasurementFactMessage existing = pending.values().iterator().next();
            if (!existing.tenantId().equals(message.tenantId())
                    || !existing.rootEventId().equals(message.rootEventId())) {
                throw new IllegalArgumentException("measurement parking partition identity mismatch");
            }
        }
    }

    private static MeasurementRuntimeState state(MeasurementProjectionState active,
            Map<String, MeasurementFactMessage> pending, long now, long ttl) {
        long expiresAt = Math.addExact(now, ttl);
        MeasurementProjectionState expiring = active == null ? null : active.expiringAt(expiresAt);
        return new MeasurementRuntimeState(expiring, pending, expiresAt);
    }

    public enum Outcome { APPLIED, PARKED, DUPLICATE }

    public record RejectedCorrection(MeasurementFactMessage message, String reason) { }

    public record Result(MeasurementRuntimeState state, List<MeasurementProjectionDelta> deltas,
            List<RejectedCorrection> rejected, Outcome outcome) {
        public Result {
            deltas = List.copyOf(deltas);
            rejected = List.copyOf(rejected);
        }
    }
}
