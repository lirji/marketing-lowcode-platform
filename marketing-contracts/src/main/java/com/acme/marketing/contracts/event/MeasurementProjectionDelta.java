package com.acme.marketing.contracts.event;

/**
 * Additive dashboard projection contract. Corrections are represented by an exact REVERSE followed by APPLY,
 * allowing an at-least-once consumer to materialize the stream with a unique delta id.
 */
public record MeasurementProjectionDelta(
        String eventType,
        String deltaId,
        String tenantId,
        String rootEventId,
        String sourceEventId,
        long revision,
        Operation operation,
        MarketingFact.Type factType,
        String businessKey,
        String campaignId,
        String experimentId,
        String variantId,
        String subjectHash,
        long countDelta,
        long revenueDeltaMinor,
        long costDeltaMinor,
        long occurredAtEpochMillis,
        long ingestedAtEpochMillis) {
    public MeasurementProjectionDelta {
        if (!"MEASUREMENT_PROJECTION_DELTA".equals(eventType)
                || blank(deltaId) || blank(tenantId) || blank(rootEventId) || blank(sourceEventId)
                || revision < 1 || operation == null || factType == null || blank(businessKey)
                || blank(subjectHash) || Math.abs(countDelta) != 1
                || occurredAtEpochMillis < 1 || ingestedAtEpochMillis < 1) {
            throw new IllegalArgumentException("measurement projection delta is invalid");
        }
        campaignId = value(campaignId);
        experimentId = value(experimentId);
        variantId = value(variantId);
        if (operation == Operation.APPLY && countDelta != 1
                || operation == Operation.REVERSE && countDelta != -1) {
            throw new IllegalArgumentException("measurement projection operation and sign disagree");
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String value(String value) {
        return value == null ? "" : value;
    }

    public enum Operation { APPLY, REVERSE }
}
