package com.acme.marketing.platform.web;

import com.acme.marketing.platform.identity.TenantId;
import com.acme.marketing.platform.identity.TenantScope;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.filter.OncePerRequestFilter;

public final class TenantContextFilter extends OncePerRequestFilter {
    private final MarketingSecurityProperties properties;

    public TenantContextFilter(MarketingSecurityProperties properties) {
        this.properties = properties;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/actuator/")
                || request.getRequestURI().equals("/error")
                || request.getMethod().equals("OPTIONS");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            TenantScope scope = resolveScope(request);
            if (scope == null) {
                writeUnauthorized(response, "TENANT_CONTEXT_REQUIRED", "verified tenant context is required");
                return;
            }
            TenantContextHolder.set(scope);
            chain.doFilter(request, response);
        } catch (IllegalArgumentException invalid) {
            writeUnauthorized(response, "TENANT_CONTEXT_INVALID", invalid.getMessage());
        } finally {
            TenantContextHolder.clear();
        }
    }

    private TenantScope resolveScope(HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated() && authentication.getPrincipal() instanceof Jwt jwt) {
            return new TenantScope(
                    new TenantId(jwt.getClaimAsString("tenant_id")),
                    claimSet(jwt, "org_ids"),
                    claimSet(jwt, "shop_ids"),
                    jwt.getSubject(),
                    claimSet(jwt, "permissions"));
        }
        if (properties.mode() == MarketingSecurityProperties.Mode.DEV && properties.devHeadersEnabled()) {
            String tenant = request.getHeader("X-Dev-Tenant-Id");
            String actor = request.getHeader("X-Dev-Actor-Id");
            if (tenant == null || actor == null) {
                return null;
            }
            Set<String> permissions = csv(request.getHeader("X-Dev-Permissions"));
            if (permissions.isEmpty()) {
                permissions = Set.of("*");
            }
            return new TenantScope(new TenantId(tenant), csv(request.getHeader("X-Dev-Organization-Ids")),
                    csv(request.getHeader("X-Dev-Shop-Ids")), actor, permissions);
        }
        return null;
    }

    private static Set<String> claimSet(Jwt jwt, String claim) {
        Object value = jwt.getClaims().get(claim);
        if (value instanceof Collection<?> collection) {
            Set<String> result = new LinkedHashSet<>();
            collection.forEach(item -> result.add(String.valueOf(item)));
            return Set.copyOf(result);
        }
        return value instanceof String text ? csv(text) : Set.of();
    }

    private static Set<String> csv(String value) {
        if (value == null || value.isBlank()) {
            return Set.of();
        }
        Set<String> result = new LinkedHashSet<>();
        for (String item : value.split(",")) {
            if (!item.isBlank()) {
                result.add(item.trim());
            }
        }
        return Set.copyOf(result);
    }

    private static void writeUnauthorized(HttpServletResponse response, String code, String detail) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/problem+json");
        response.setCharacterEncoding("UTF-8");
        String safeDetail = detail == null ? "invalid identity" : detail.replace("\\", "\\\\").replace("\"", "\\\"");
        response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"Unauthorized\","
                + "\"status\":401,\"code\":\"" + code + "\",\"detail\":\"" + safeDetail
                + "\",\"retryable\":false}");
    }
}
