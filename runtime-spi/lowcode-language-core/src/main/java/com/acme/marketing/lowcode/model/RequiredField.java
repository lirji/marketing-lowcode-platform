package com.acme.marketing.lowcode.model;

import java.time.Duration;

public record RequiredField(String path, Duration maxAge, String trust, String sensitivity) {
    public RequiredField {
        path = requireText(path, "path");
        if (maxAge == null || maxAge.isNegative()) {
            throw new IllegalArgumentException("maxAge must not be negative");
        }
        trust = requireText(trust, "trust");
        sensitivity = requireText(sensitivity, "sensitivity");
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }
}
