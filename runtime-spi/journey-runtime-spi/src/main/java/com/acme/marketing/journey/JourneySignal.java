package com.acme.marketing.journey;

import java.time.Instant;
import java.util.Map;

public sealed interface JourneySignal permits JourneySignal.Start, JourneySignal.Event, JourneySignal.Timer {
    String signalId();

    Instant occurredAt();

    record Start(String signalId, Instant occurredAt) implements JourneySignal {
    }

    record Event(String signalId, String eventType, Instant occurredAt, Map<String, String> attributes)
            implements JourneySignal {
        public Event {
            attributes = Map.copyOf(attributes == null ? Map.of() : attributes);
        }
    }

    record Timer(String signalId, String timerKey, Instant occurredAt) implements JourneySignal {
    }
}
