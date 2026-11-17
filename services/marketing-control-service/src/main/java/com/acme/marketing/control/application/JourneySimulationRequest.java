package com.acme.marketing.control.application;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/** 相对时间只用于本次纯计算，不接受用户提供运行时身份或命令ID。 */
public record JourneySimulationRequest(Map<String, String> initialVariables, List<Event> events, long horizonSeconds) {
    public static final int MAX_EVENTS = 32;
    public static final int MAX_FIELDS = 32;
    public static final int MAX_KEY_LENGTH = 64;
    public static final int MAX_VALUE_LENGTH = 256;
    public static final long MAX_HORIZON_SECONDS = 7 * 24 * 60 * 60;

    public JourneySimulationRequest {
        initialVariables = fields(initialVariables);
        if (events == null || events.size() > MAX_EVENTS || horizonSeconds < 0 || horizonSeconds > MAX_HORIZON_SECONDS)
            throw JourneySimulationErrorCode.LIMIT_EXCEEDED.invalid();
        events = List.copyOf(events);
        long previous = 0;
        for (Event event : events) {
            if (event.offsetSeconds() < previous || event.offsetSeconds() > horizonSeconds)
                throw JourneySimulationErrorCode.INPUT_INVALID.invalid();
            previous = event.offsetSeconds();
        }
    }

    /** 严格检查JSON原始类型，拒绝将数字/布尔静默转换为字符串。 */
    public static JourneySimulationRequest read(JsonNode node) {
        object(node, Set.of("initialVariables", "events", "horizonSeconds"));
        JsonNode events = node.get("events");
        if (!events.isArray()) throw JourneySimulationErrorCode.INPUT_INVALID.invalid();
        if (events.size() > MAX_EVENTS) throw JourneySimulationErrorCode.LIMIT_EXCEEDED.invalid();
        List<Event> parsed = new ArrayList<>();
        for (JsonNode event : events) {
            object(event, Set.of("offsetSeconds", "eventType", "attributes"));
            if (!event.get("eventType").isString()) throw JourneySimulationErrorCode.INPUT_INVALID.invalid();
            parsed.add(new Event(integer(event.get("offsetSeconds")), event.get("eventType").asString(), dictionary(event.get("attributes"))));
        }
        return new JourneySimulationRequest(dictionary(node.get("initialVariables")), parsed, integer(node.get("horizonSeconds")));
    }

    private static long integer(JsonNode node) {
        if (!node.isIntegralNumber() || !node.canConvertToLong()) throw JourneySimulationErrorCode.INPUT_INVALID.invalid();
        return node.asLong();
    }
    private static void object(JsonNode node, Set<String> keys) {
        if (node == null || !node.isObject() || !node.propertyNames().equals(keys)) throw JourneySimulationErrorCode.INPUT_INVALID.invalid();
    }
    private static Map<String, String> dictionary(JsonNode node) {
        if (node == null || !node.isObject()) throw JourneySimulationErrorCode.INPUT_INVALID.invalid();
        if (node.size() > MAX_FIELDS) throw JourneySimulationErrorCode.LIMIT_EXCEEDED.invalid();
        Map<String, String> values = new LinkedHashMap<>();
        for (var entry : node.properties()) {
            if (!entry.getValue().isString()) throw JourneySimulationErrorCode.INPUT_INVALID.invalid();
            values.put(entry.getKey(), entry.getValue().asString());
        }
        return fields(values);
    }
    private static Map<String, String> fields(Map<String, String> values) {
        if (values == null || values.size() > MAX_FIELDS) throw JourneySimulationErrorCode.LIMIT_EXCEEDED.invalid();
        for (var entry : values.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank() || entry.getKey().length() > MAX_KEY_LENGTH
                    || entry.getValue() == null || entry.getValue().length() > MAX_VALUE_LENGTH)
                throw JourneySimulationErrorCode.INPUT_INVALID.invalid();
        }
        return Map.copyOf(values);
    }

    /** 同一时刻的事件严格保留输入顺序，属性只合并进本次内存快照。 */
    public record Event(long offsetSeconds, String eventType, Map<String, String> attributes) {
        public Event {
            if (offsetSeconds < 0 || eventType == null || eventType.isBlank() || eventType.length() > MAX_KEY_LENGTH)
                throw JourneySimulationErrorCode.INPUT_INVALID.invalid();
            attributes = fields(attributes);
        }
    }
}
