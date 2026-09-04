package com.acme.marketing.platform.identity;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

public final class TenantResourceNames {
    private TenantResourceNames() {
    }

    public static String cacheKey(TenantId tenantId, CellId cellId, String namespace, String key) {
        return "mk:" + safe(tenantId.value()) + ':' + safe(cellId.value()) + ':' + safe(namespace) + ':' + safe(key);
    }

    public static String topic(String environment, CellId cellId, String family, int version) {
        if (version < 1) {
            throw new IllegalArgumentException("topic version must be positive");
        }
        return String.join(".", safe(environment), safe(cellId.value()), safe(family), "v" + version);
    }

    public static String objectKey(TenantId tenantId, CellId cellId, String category, String digest) {
        if (digest == null || !digest.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException("object digest is invalid");
        }
        return "tenant=" + encode(tenantId.value()) + "/cell=" + encode(cellId.value())
                + "/" + safe(category) + "/sha256=" + digest;
    }

    private static String safe(String value) {
        if (value == null || !value.matches("[a-zA-Z0-9][a-zA-Z0-9_-]{0,127}")) {
            throw new IllegalArgumentException("resource name component is invalid");
        }
        return value;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
