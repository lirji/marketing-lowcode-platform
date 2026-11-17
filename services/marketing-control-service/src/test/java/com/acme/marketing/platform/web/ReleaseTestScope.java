package com.acme.marketing.platform.web;

import com.acme.marketing.platform.identity.TenantId;
import com.acme.marketing.platform.identity.TenantScope;
import java.util.Set;
import java.util.function.Supplier;

/** 仅测试可设置可信租户；不放宽生产TenantContextHolder的包内写权限。 */
public final class ReleaseTestScope {
    private ReleaseTestScope() { }

    public static <T> T run(String tenant, Supplier<T> action) {
        TenantContextHolder.set(new TenantScope(new TenantId(tenant), Set.of("*"), Set.of("*"), "test", Set.of("*")));
        try { return action.get(); }
        finally { TenantContextHolder.clear(); }
    }
}
