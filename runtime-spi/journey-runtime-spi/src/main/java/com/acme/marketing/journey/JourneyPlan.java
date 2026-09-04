package com.acme.marketing.journey;

import java.time.Duration;
import java.util.Map;

public record JourneyPlan(
        String journeyId,
        long version,
        String startNodeId,
        Map<String, JourneyNode> nodes,
        int maxStepsPerSignal,
        int maxIterations,
        Duration stateTtl) {
    public JourneyPlan {
        if (journeyId == null || journeyId.isBlank() || version < 1 || startNodeId == null || startNodeId.isBlank()) {
            throw new IllegalArgumentException("journey identity and start node are required");
        }
        nodes = Map.copyOf(nodes);
        if (!nodes.containsKey(startNodeId) || maxStepsPerSignal < 1 || maxIterations < 1
                || stateTtl == null || stateTtl.isNegative() || stateTtl.isZero()) {
            throw new IllegalArgumentException("invalid journey runtime limits");
        }
    }
}
