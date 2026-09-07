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
import java.util.Map;
import java.util.Set;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
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
        Jwt jwt = currentJwt();
        if (jwt != null) {
            // tenant_id 是货主业务租户；owner 只表示 Casdoor 登录组织，二者不能互相回退。
            String tenant = firstNonBlank(claimString(jwt, "tenant_id"), propertyClaim(jwt, "tenant_id"));
            if (tenant == null) {
                return null;
            }
            Set<String> organizations = claimSet(jwt, "org_ids");
            if (organizations.isEmpty()) {
                String owner = claimString(jwt, "owner");
                if (owner != null) {
                    organizations = Set.of(owner);
                }
            }
            return new TenantScope(
                    new TenantId(tenant),
                    organizations,
                    claimSet(jwt, "shop_ids"),
                    jwt.getSubject(),
                    permissionSet(jwt.getClaims().get("permissions")));
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

    private static Jwt currentJwt() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }
        if (authentication instanceof JwtAuthenticationToken jwtAuthentication) {
            return jwtAuthentication.getToken();
        }
        return authentication.getPrincipal() instanceof Jwt jwt ? jwt : null;
    }

    private static Set<String> claimSet(Jwt jwt, String claim) {
        return claimValues(jwt.getClaims().get(claim));
    }

    private static Set<String> permissionSet(Object value) {
        Set<String> names = new LinkedHashSet<>();
        for (String name : claimValues(value)) {
            names.add(toMarketingPermission(name));
        }
        if (names.contains("marketing.admin") || names.contains("*")) {
            return Set.of("*");
        }
        return Set.copyOf(names);
    }

    private static String toMarketingPermission(String name) {
        if ("marketing.admin".equals(name) || "*".equals(name) || name.contains(":")) {
            return name;
        }
        int separator = name.lastIndexOf('.');
        return separator > 0 ? name.substring(0, separator) + ":" + name.substring(separator + 1) : name;
    }

    private static Set<String> claimValues(Object value) {
        if (value instanceof Collection<?> collection) {
            Set<String> result = new LinkedHashSet<>();
            for (Object item : collection) {
                if (item instanceof Map<?, ?> map) {
                    Object name = map.get("name");
                    if (name != null && !String.valueOf(name).isBlank()) {
                        result.add(String.valueOf(name).trim());
                    }
                } else if (item != null && !String.valueOf(item).isBlank()) {
                    result.add(String.valueOf(item).trim());
                }
            }
            return Set.copyOf(result);
        }
        return value instanceof String text ? csv(text) : Set.of();
    }

    private static String claimString(Jwt jwt, String name) {
        String typed = jwt.getClaimAsString(name);
        if (typed != null && !typed.isBlank()) {
            return typed.trim();
        }
        Object raw = jwt.getClaim(name);
        if (raw == null) {
            return null;
        }
        String text = String.valueOf(raw).trim();
        return text.isEmpty() || "null".equals(text) ? null : text;
    }

    /** Casdoor 默认 JWT 会把自定义用户字段放在 properties；在信任边界归一化为 tenant_id。 */
    private static String propertyClaim(Jwt jwt, String name) {
        Object properties = jwt.getClaims().get("properties");
        if (!(properties instanceof Map<?, ?> values)) return null;
        Object value = values.get(name);
        if (value == null || String.valueOf(value).isBlank()) return null;
        return String.valueOf(value).trim();
    }

    private static String firstNonBlank(String... values) {
        if (values == null) return null;
        for (String value : values) {
            if (value != null && !value.isBlank()) return value.trim();
        }
        return null;
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
