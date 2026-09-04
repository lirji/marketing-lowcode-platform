package com.acme.marketing.contracts.event;

import java.util.Map;
import java.util.Set;

/** Stable cross-service command emitted from a pinned journey artifact. */
public record JourneyEffectCommand(
        String eventType,
        String tenantId,
        String enrollmentId,
        String subjectToken,
        String journeyId,
        long journeyVersion,
        String commandId,
        String effectType,
        String nodeId,
        Map<String, String> payload,
        long createdAtEpochMillis) {
    private static final Set<String> EFFECT_TYPES = Set.of("SEND", "GRANT", "WEBHOOK");

    public JourneyEffectCommand {
        payload = Map.copyOf(payload == null ? Map.of() : payload);
        if (!"JOURNEY_EFFECT_COMMAND".equals(eventType)
                || blank(tenantId) || blank(enrollmentId) || blank(subjectToken) || blank(journeyId)
                || journeyVersion < 1 || blank(commandId) || !EFFECT_TYPES.contains(effectType)
                || blank(nodeId) || createdAtEpochMillis < 1) {
            throw new IllegalArgumentException("journey effect command is invalid");
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
