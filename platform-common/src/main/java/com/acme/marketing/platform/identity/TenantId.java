package com.acme.marketing.platform.identity;

import java.util.Objects;
import java.util.regex.Pattern;

public record TenantId(String value) implements Comparable<TenantId> {
    private static final Pattern SAFE = Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9_-]{0,63}");

    public TenantId {
        Objects.requireNonNull(value, "tenantId");
        if (!SAFE.matcher(value).matches()) {
            throw new IllegalArgumentException("tenantId must match " + SAFE.pattern());
        }
    }

    @Override
    public int compareTo(TenantId other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value;
    }
}
