package com.acme.marketing.platform.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

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

    @Test
    void bindsCasdoorOwnerAndPermissionObjects() throws Exception {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("casdoor-user")
                .claim("owner", "marketing-platform")
                .claim("permissions", List.of(
                        Map.of("name", "campaign.read"),
                        Map.of("name", "marketing.admin")))
                .issuedAt(Instant.parse("2026-09-04T00:00:00Z"))
                .expiresAt(Instant.parse("2026-09-04T01:00:00Z"))
                .build();
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
        SecurityContextHolder.setContext(context);
        try {
            TenantContextFilter filter = new TenantContextFilter(
                    new MarketingSecurityProperties(MarketingSecurityProperties.Mode.OIDC, false));
            MockHttpServletResponse response = new MockHttpServletResponse();
            AtomicBoolean invoked = new AtomicBoolean();
            filter.doFilter(new MockHttpServletRequest("GET", "/api/v1/campaigns"), response,
                    (ignoredRequest, ignoredResponse) -> {
                        invoked.set(true);
                        assertEquals("marketing-platform", TenantContextHolder.requireCurrent().tenantId().value());
                        assertEquals(Set.of("marketing-platform"), TenantContextHolder.requireCurrent().organizations());
                        assertEquals(Set.of("*"), TenantContextHolder.requireCurrent().permissions());
                        assertEquals("casdoor-user", TenantContextHolder.requireCurrent().actorId());
                    });
            assertEquals(200, response.getStatus());
            assertTrue(invoked.get());
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void mapsCasdoorDottedPermissionsToResourceActions() throws Exception {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("casdoor-operator")
                .claim("owner", "marketing-platform")
                .claim("permissions", List.of(
                        Map.of("name", "campaign.read"),
                        Map.of("name", "audience-field.write")))
                .issuedAt(Instant.parse("2026-09-04T00:00:00Z"))
                .expiresAt(Instant.parse("2026-09-04T01:00:00Z"))
                .build();
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
        SecurityContextHolder.setContext(context);
        try {
            TenantContextFilter filter = new TenantContextFilter(
                    new MarketingSecurityProperties(MarketingSecurityProperties.Mode.OIDC, false));
            MockHttpServletResponse response = new MockHttpServletResponse();
            AtomicBoolean invoked = new AtomicBoolean();
            filter.doFilter(new MockHttpServletRequest("GET", "/api/v1/campaigns"), response,
                    (ignoredRequest, ignoredResponse) -> {
                        invoked.set(true);
                        assertEquals(Set.of("campaign:read", "audience-field:write"),
                                TenantContextHolder.requireCurrent().permissions());
                    });
            assertEquals(200, response.getStatus());
            assertTrue(invoked.get());
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void rejectsJwtWithoutTenantOrOwner() throws Exception {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("casdoor-user")
                .issuedAt(Instant.parse("2026-09-04T00:00:00Z"))
                .expiresAt(Instant.parse("2026-09-04T01:00:00Z"))
                .build();
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
        SecurityContextHolder.setContext(context);
        try {
            TenantContextFilter filter = new TenantContextFilter(
                    new MarketingSecurityProperties(MarketingSecurityProperties.Mode.OIDC, false));
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(new MockHttpServletRequest("GET", "/api/v1/campaigns"), response,
                    (ignoredRequest, ignoredResponse) -> {
                        throw new AssertionError("chain must not be called");
                    });
            assertEquals(401, response.getStatus());
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
