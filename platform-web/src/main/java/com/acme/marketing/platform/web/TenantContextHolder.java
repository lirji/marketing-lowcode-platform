package com.acme.marketing.platform.web;

import com.acme.marketing.platform.error.ForbiddenException;
import com.acme.marketing.platform.identity.TenantScope;
import java.util.Optional;

public final class TenantContextHolder {
    private static final ThreadLocal<TenantScope> CURRENT = new ThreadLocal<>();

    private TenantContextHolder() {
    }

    public static Optional<TenantScope> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    public static TenantScope requireCurrent() {
        return current().orElseThrow(() ->
                new ForbiddenException("TENANT_CONTEXT_REQUIRED", "verified tenant context is required"));
    }

    static void set(TenantScope scope) {
        if (CURRENT.get() != null) {
            throw new IllegalStateException("tenant context is already bound to this thread");
        }
        CURRENT.set(scope);
    }

    static void clear() {
        CURRENT.remove();
    }
}
