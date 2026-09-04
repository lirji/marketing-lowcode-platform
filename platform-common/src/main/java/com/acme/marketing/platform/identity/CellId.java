package com.acme.marketing.platform.identity;

public record CellId(String value) {
    public CellId {
        if (value == null || !value.matches("[a-z][a-z0-9-]{1,31}")) {
            throw new IllegalArgumentException("cell id is invalid");
        }
    }
}
