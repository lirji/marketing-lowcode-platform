package com.acme.marketing.journey;

import java.util.Map;

public record JourneyCommand(String commandId, Type type, String nodeId, Map<String, String> payload) {
    public JourneyCommand {
        payload = Map.copyOf(payload == null ? Map.of() : payload);
    }

    public enum Type {
        SEND, GRANT, WEBHOOK
    }
}
