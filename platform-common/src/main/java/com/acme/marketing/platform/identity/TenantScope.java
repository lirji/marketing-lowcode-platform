package com.acme.marketing.platform.identity;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public record TenantScope(
        TenantId tenantId,
        Set<String> organizations,
        Set<String> shops,
        String actorId,
        Set<String> permissions) {
    public TenantScope {
        Objects.requireNonNull(tenantId, "tenantId");
        organizations = Set.copyOf(organizations == null ? Set.of() : organizations);
        shops = Set.copyOf(shops == null ? Set.of() : shops);
        actorId = requireText(actorId, "actorId");
        permissions = Set.copyOf(permissions == null ? Set.of() : permissions);
    }

    public TenantScope(TenantId tenantId, Set<String> organizations, Set<String> shops, String actorId) {
        this(tenantId, organizations, shops, actorId, Set.of());
    }

    public void requireOrganization(String organizationId) {
        if (!organizations.contains("*") && !organizations.contains(organizationId)) {
            throw new IllegalArgumentException("organization is outside authenticated scope");
        }
    }

    public void requireShop(String shopId) {
        if (!shops.contains("*") && !shops.contains(shopId)) {
            throw new IllegalArgumentException("shop is outside authenticated scope");
        }
    }

    public Optional<String> soleOrganization() {
        return organizations.size() == 1 ? organizations.stream().findFirst() : Optional.empty();
    }

    public boolean permits(String permission) {
        return permissions.contains("*") || permissions.contains(permission);
    }

    public void requirePermission(String permission) {
        if (!permits(permission)) {
            throw new com.acme.marketing.platform.error.ForbiddenException(
                    "PERMISSION_DENIED", "permission is required: " + permission);
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }
}
