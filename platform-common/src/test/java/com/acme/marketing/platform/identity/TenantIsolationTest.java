package com.acme.marketing.platform.identity;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.acme.marketing.platform.error.ForbiddenException;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TenantIsolationTest {
    @Test
    void hierarchicalScopeAndPermissionAreFailClosed() {
        TenantScope scope = new TenantScope(new TenantId("tenant-a"), Set.of("org-a"), Set.of("shop-a"),
                "operator-1", Set.of("campaign:read"));

        scope.requireOrganization("org-a");
        scope.requireShop("shop-a");
        assertTrue(scope.permits("campaign:read"));
        assertThrows(IllegalArgumentException.class, () -> scope.requireShop("shop-b"));
        assertThrows(ForbiddenException.class, () -> scope.requirePermission("campaign:publish"));
        TenantScope missingHierarchy = new TenantScope(new TenantId("tenant-a"), Set.of(), Set.of(),
                "operator-2", Set.of("campaign:read"));
        assertThrows(IllegalArgumentException.class, () -> missingHierarchy.requireOrganization("org-a"));
        TenantScope tenantAdministrator = new TenantScope(new TenantId("tenant-a"), Set.of("*"), Set.of("*"),
                "operator-3", Set.of("*"));
        tenantAdministrator.requireOrganization("org-any");
        tenantAdministrator.requireShop("shop-any");
    }

    @Test
    void namesAlwaysContainTenantAndCell() {
        String key = TenantResourceNames.cacheKey(new TenantId("tenant-a"), new CellId("cell-a"),
                "offers", "offer-1");
        assertTrue(key.contains("tenant-a"));
        assertTrue(key.contains("cell-a"));
    }
}
