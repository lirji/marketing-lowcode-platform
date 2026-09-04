package com.acme.marketing.platform.identity;

import java.util.Set;

public record ServiceIdentity(String serviceName, String workloadId, Set<String> audiences) {
    public ServiceIdentity {
        if (serviceName == null || serviceName.isBlank() || workloadId == null || workloadId.isBlank()) {
            throw new IllegalArgumentException("service identity is incomplete");
        }
        audiences = Set.copyOf(audiences == null ? Set.of() : audiences);
    }

    public void requireAudience(String audience) {
        if (!audiences.contains(audience)) {
            throw new IllegalArgumentException("service token is not intended for " + audience);
        }
    }
}
