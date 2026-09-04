package com.acme.marketing.platform.identity;

import java.util.UUID;

public final class Identifiers {
    private Identifiers() {
    }

    public static String newId(String prefix) {
        if (prefix == null || !prefix.matches("[a-z][a-z0-9_]{0,15}")) {
            throw new IllegalArgumentException("invalid id prefix");
        }
        return prefix + "_" + UUID.randomUUID().toString().replace("-", "");
    }
}
