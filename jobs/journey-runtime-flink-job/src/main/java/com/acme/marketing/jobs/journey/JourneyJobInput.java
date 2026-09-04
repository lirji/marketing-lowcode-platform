package com.acme.marketing.jobs.journey;

import com.acme.marketing.journey.JourneySignal;
import java.time.Instant;
import java.util.Map;

public record JourneyJobInput(
        String tenantId,
        String enrollmentId,
        String subjectToken,
        PlanReference planReference,
        SignalPayload signal) {
    public JourneyJobInput {
        if (tenantId == null || tenantId.isBlank() || enrollmentId == null || enrollmentId.isBlank()
                || signal == null) {
            throw new IllegalArgumentException("journey job identity and signal are required");
        }
        subjectToken = subjectToken == null ? "" : subjectToken;
        if (signal.type() == SignalPayload.Type.START && planReference == null) {
            throw new IllegalArgumentException("START requires an active journey plan reference");
        }
        if (signal.type() != SignalPayload.Type.START && planReference != null) {
            throw new IllegalArgumentException("only START may carry a journey plan reference");
        }
    }

    public String partitionKey() {
        return tenantId + ':' + enrollmentId;
    }

    public record PlanReference(String artifactId, long generation, long activationSequence,
            String journeyId, long journeyVersion) {
        public PlanReference {
            if (artifactId == null || artifactId.isBlank() || generation < 1 || activationSequence < 1
                    || journeyId == null || journeyId.isBlank() || journeyVersion < 1) {
                throw new IllegalArgumentException("journey plan reference is invalid");
            }
        }
    }

    public record SignalPayload(
            Type type,
            String signalId,
            String eventType,
            String timerKey,
            long occurredAtEpochMillis,
            Map<String, String> attributes) {
        public SignalPayload {
            if (type == null || signalId == null || signalId.isBlank() || occurredAtEpochMillis < 1) {
                throw new IllegalArgumentException("journey signal is invalid");
            }
            eventType = eventType == null ? "" : eventType;
            timerKey = timerKey == null ? "" : timerKey;
            attributes = Map.copyOf(attributes == null ? Map.of() : attributes);
        }

        JourneySignal toDomain() {
            Instant occurredAt = Instant.ofEpochMilli(occurredAtEpochMillis);
            return switch (type) {
                case START -> new JourneySignal.Start(signalId, occurredAt);
                case EVENT -> new JourneySignal.Event(signalId, eventType, occurredAt, attributes);
                case TIMER -> new JourneySignal.Timer(signalId, timerKey, occurredAt);
            };
        }

        public enum Type { START, EVENT, TIMER }
    }
}
