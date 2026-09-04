package com.acme.marketing.decision.rule;

import java.util.Map;

public final class RuleFacts {
    private final Map<String, Object> values;

    public RuleFacts(Map<String, Object> values) {
        this.values = Map.copyOf(values);
    }

    public boolean has(String name) {
        return values.containsKey(name);
    }

    public long longValue(String name) {
        Object value = values.get(name);
        if (value instanceof Number number) {
            return number.longValue();
        }
        throw new IllegalArgumentException("fact is not numeric: " + name);
    }

    public String stringValue(String name) {
        Object value = values.get(name);
        return value == null ? null : String.valueOf(value);
    }

    public boolean booleanValue(String name) {
        Object value = values.get(name);
        if (value instanceof Boolean bool) {
            return bool;
        }
        throw new IllegalArgumentException("fact is not boolean: " + name);
    }
}
