package com.acme.marketing.platform.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class TenantContextFilterTest {
    @Test
    void bindsDevTenantOnlyForDurationOfRequest() throws Exception {
        TenantContextFilter filter = new TenantContextFilter(
                new MarketingSecurityProperties(MarketingSecurityProperties.Mode.DEV, true));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/campaigns");
        request.addHeader("X-Dev-Tenant-Id", "tenant-a");
        request.addHeader("X-Dev-Actor-Id", "operator-a");
        request.addHeader("X-Dev-Organization-Ids", "org-a,org-b");
        request.addHeader("X-Dev-Permissions", "campaign:read,campaign:write");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean invoked = new AtomicBoolean();

        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> {
            invoked.set(true);
            assertEquals("tenant-a", TenantContextHolder.requireCurrent().tenantId().value());
            assertEquals(2, TenantContextHolder.requireCurrent().organizations().size());
            assertEquals(Set.of("campaign:read", "campaign:write"),
                    TenantContextHolder.requireCurrent().permissions());
        });

        assertEquals(200, response.getStatus());
        assertFalse(TenantContextHolder.current().isPresent());
        assertTrue(invoked.get());
    }

    @Test
    void rejectsMissingTenant() throws Exception {
        TenantContextFilter filter = new TenantContextFilter(
                new MarketingSecurityProperties(MarketingSecurityProperties.Mode.DEV, true));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(new MockHttpServletRequest("GET", "/api/v1/campaigns"), response,
                (ignoredRequest, ignoredResponse) -> {
                    throw new AssertionError("chain must not be called");
                });

        assertEquals(401, response.getStatus());
        assertTrue(response.getContentType().startsWith("application/problem+json"));
    }
}
