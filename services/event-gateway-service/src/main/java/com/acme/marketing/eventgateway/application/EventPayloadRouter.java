package com.acme.marketing.eventgateway.application;

import com.acme.marketing.contracts.event.MarketingFact;
import java.time.Instant;
import java.util.Collection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.TreeMap;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/** Converts authenticated ingress envelopes into the exact versioned payload expected by each Flink job. */
@Component
public final class EventPayloadRouter {
    public static final String PROFILE_TOPIC = "mk.profile.change.v1";
    public static final String JOURNEY_TOPIC = "mk.journey.signal.v1";
    public static final String MEASUREMENT_TOPIC = "mk.marketing.fact.v1";
    public static final String GENERAL_TOPIC = "mk.platform.events.v1";

    private final ObjectMapper mapper;

    public EventPayloadRouter(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public OutboundEvent route(String tenantId, String receiptId,
            EventIngestionService.InboundEvent event, Instant ingestedAt) {
        return switch (event.eventType()) {
            case "PROFILE_CHANGED" -> profile(tenantId, event);
            case "JOURNEY_SIGNAL" -> journey(tenantId, event);
            case "MARKETING_FACT" -> measurement(tenantId, event, ingestedAt);
            default -> general(tenantId, receiptId, event, ingestedAt);
        };
    }

    private OutboundEvent profile(String tenantId, EventIngestionService.InboundEvent event) {
        long version = positiveLong(event.data().get("profileVersion"), "profileVersion");
        Set<String> segments = stringSet(event.data().get("segmentIds"), "segmentIds");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tenantId", tenantId);
        payload.put("subjectToken", event.subjectToken());
        payload.put("profileVersion", version);
        payload.put("segmentIds", segments);
        payload.put("occurredAtEpochMillis", event.occurredAt().toEpochMilli());
        return new OutboundEvent(PROFILE_TOPIC, tenantId + ':' + event.subjectToken(), json(payload));
    }

    private OutboundEvent journey(String tenantId, EventIngestionService.InboundEvent event) {
        Map<String, Object> sourceSignal = objectMap(event.data().get("signal"), "signal");
        Map<String, Object> signal = new LinkedHashMap<>(sourceSignal);
        String type = requiredString(signal.get("type"), "signal.type");
        if (!Set.of("START", "EVENT").contains(type)) {
            throw new IllegalArgumentException("signal.type is unsupported");
        }
        if (event.data().containsKey("plan")) {
            throw new IllegalArgumentException("inline journey plans are forbidden");
        }
        signal.put("signalId", event.eventId());
        signal.put("occurredAtEpochMillis", event.occurredAt().toEpochMilli());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tenantId", tenantId);
        payload.put("enrollmentId", event.businessKey());
        payload.put("subjectToken", event.subjectToken());
        if ("START".equals(type)) {
            payload.put("planReference", canonical(objectMap(event.data().get("planReference"), "planReference")));
        } else if (event.data().containsKey("planReference")) {
            throw new IllegalArgumentException("planReference is only valid for START");
        }
        payload.put("signal", signal);
        return new OutboundEvent(JOURNEY_TOPIC, tenantId + ':' + event.businessKey(), json(payload));
    }

    private OutboundEvent measurement(String tenantId, EventIngestionService.InboundEvent event, Instant ingestedAt) {
        String factType = requiredString(event.data().get("factType"), "factType");
        try {
            MarketingFact.Type.valueOf(factType);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("factType is unsupported", invalid);
        }
        Map<String, String> attributes = stringMap(event.data().get("attributes"), "attributes");
        String correctionOf = optionalString(event.data().get("correctionOf"));
        String correctionRootId = correctionOf.isBlank() ? event.eventId()
                : requiredString(event.data().get("correctionRootId"), "correctionRootId");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventId", event.eventId());
        payload.put("tenantId", tenantId);
        payload.put("type", factType);
        payload.put("businessKey", event.businessKey());
        payload.put("subjectToken", event.subjectToken());
        payload.put("occurredAt", event.occurredAt().toString());
        payload.put("ingestedAt", ingestedAt.toString());
        payload.put("schemaVersion", event.schemaVersion());
        payload.put("attributes", attributes);
        payload.put("correctionOf", correctionOf);
        payload.put("correctionRootId", correctionRootId);
        return new OutboundEvent(MEASUREMENT_TOPIC, tenantId + ':' + correctionRootId, json(payload));
    }

    private OutboundEvent general(String tenantId, String receiptId,
            EventIngestionService.InboundEvent event, Instant ingestedAt) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tenantId", tenantId);
        payload.put("receiptId", receiptId);
        payload.put("eventId", event.eventId());
        payload.put("sourceId", event.sourceId());
        payload.put("eventType", event.eventType());
        payload.put("businessKey", event.businessKey());
        payload.put("subjectToken", event.subjectToken());
        payload.put("occurredAt", event.occurredAt().toString());
        payload.put("ingestedAt", ingestedAt.toString());
        payload.put("schemaVersion", event.schemaVersion());
        payload.put("aggregateVersion", event.aggregateVersion());
        payload.put("data", canonical(event.data()));
        String key = event.aggregateVersion() == null ? tenantId + ':' + event.subjectToken()
                : String.join(":", tenantId, event.sourceId(), event.businessKey());
        return new OutboundEvent(GENERAL_TOPIC, key, json(payload));
    }

    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (JacksonException failure) { throw new IllegalArgumentException("outbound event is invalid", failure); }
    }

    private static long positiveLong(Object value, String field) {
        try {
            long parsed = value instanceof Number number ? number.longValue() : Long.parseLong(String.valueOf(value));
            if (parsed < 1) throw new IllegalArgumentException();
            return parsed;
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException(field + " must be positive", invalid);
        }
    }

    private static String requiredString(Object value, String field) {
        String parsed = value instanceof String text ? text : "";
        if (parsed.isBlank()) throw new IllegalArgumentException(field + " is required");
        return parsed;
    }

    private static String optionalString(Object value) {
        return value instanceof String text ? text : "";
    }

    private static Set<String> stringSet(Object value, String field) {
        if (!(value instanceof Collection<?> collection)) {
            throw new IllegalArgumentException(field + " must be an array");
        }
        TreeSet<String> result = new TreeSet<>();
        for (Object item : collection) result.add(requiredString(item, field));
        return Collections.unmodifiableSet(result);
    }

    private static Map<String, String> stringMap(Object value, String field) {
        if (value == null) return Map.of();
        Map<String, Object> source = objectMap(value, field);
        Map<String, String> result = new TreeMap<>();
        source.forEach((key, item) -> result.put(key, requiredString(item, field + '.' + key)));
        return Collections.unmodifiableMap(result);
    }

    private static Map<String, Object> objectMap(Object value, String field) {
        if (!(value instanceof Map<?, ?> source)) throw new IllegalArgumentException(field + " must be an object");
        Map<String, Object> result = new TreeMap<>();
        source.forEach((key, item) -> result.put(requiredString(key, field + " key"), item));
        return Collections.unmodifiableMap(result);
    }

    private static Object canonical(Object value) {
        if (value instanceof Map<?, ?> source) {
            Map<String, Object> sorted = new TreeMap<>();
            source.forEach((key, item) -> sorted.put(String.valueOf(key), canonical(item)));
            return Collections.unmodifiableMap(sorted);
        }
        if (value instanceof Collection<?> source) {
            ArrayList<Object> ordered = new ArrayList<>(source.size());
            source.forEach(item -> ordered.add(canonical(item)));
            return List.copyOf(ordered);
        }
        return value;
    }

    public record OutboundEvent(String topic, String partitionKey, String payload) { }
}
