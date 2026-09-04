package com.acme.marketing.journey;

import java.util.Map;

public record JourneyNode(String id, Type type, Map<String, String> config, Map<String, String> routes) {
    public JourneyNode {
        if (id == null || id.isBlank() || type == null) {
            throw new IllegalArgumentException("node id and type are required");
        }
        config = Map.copyOf(config == null ? Map.of() : config);
        routes = Map.copyOf(routes == null ? Map.of() : routes);
    }

    public String route(String port) {
        String target = routes.get(port);
        if (target == null) {
            throw new IllegalStateException("missing route " + port + " from node " + id);
        }
        return target;
    }

    public enum Type {
        TRIGGER,
        CONDITION,
        WAIT_EVENT,
        WAIT_TIMER,
        SEND,
        GRANT,
        WEBHOOK,
        REPEAT,
        END
    }
}
