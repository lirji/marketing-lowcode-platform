package com.acme.marketing.control.infrastructure;

import com.acme.marketing.control.application.BenefitReleaseGate;
import com.acme.marketing.platform.error.ConflictException;
import com.acme.marketing.platform.error.DependencyUnavailableException;
import com.acme.marketing.platform.error.DomainException;
import com.acme.marketing.platform.error.ForbiddenException;
import com.acme.marketing.platform.error.UnauthorizedException;
import com.acme.marketing.platform.identity.TenantScope;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 同平台同步读校验：OIDC 转发当前用户 JWT；DEV 只转发已验证的 TenantScope。 */
@Component
public final class HttpBenefitReleaseGate implements BenefitReleaseGate {
    private final HttpClient http;
    private final ObjectMapper mapper;
    private final String endpoint;
    private final Duration requestTimeout;

    public HttpBenefitReleaseGate(ObjectMapper mapper,
            @Value("${marketing.benefit-funding.base-url:http://127.0.0.1:8085}") String baseUrl,
            @Value("${marketing.benefit-funding.connect-timeout-ms:1000}") long connectTimeoutMs,
            @Value("${marketing.benefit-funding.request-timeout-ms:2000}") long requestTimeoutMs) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        if (baseUrl == null || baseUrl.isBlank() || connectTimeoutMs < 1 || requestTimeoutMs < 1) {
            throw new IllegalArgumentException("benefit release gate configuration is invalid");
        }
        String normalized = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.endpoint = normalized + "/internal/v1/benefits:assert-releasable";
        this.requestTimeout = Duration.ofMillis(requestTimeoutMs);
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(connectTimeoutMs)).build();
    }

    @Override
    public void assertReleasable(TenantScope scope, Set<String> benefitDefinitionVersions) {
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(requestTimeout)
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(
                            Map.of("references", benefitDefinitionVersions))));
            String authorization = currentAuthorization();
            if (authorization != null) {
                request.header("Authorization", authorization);
            } else {
                request.header("X-Dev-Tenant-Id", scope.tenantId().value())
                        .header("X-Dev-Actor-Id", scope.actorId())
                        .header("X-Dev-Permissions", "release:write");
            }
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) return;
            Problem problem = problem(response.body());
            if (response.statusCode() == 401) {
                throw new UnauthorizedException("BENEFIT_RELEASE_GATE_AUTH_REQUIRED",
                        "benefit release gate authentication is missing or invalid");
            }
            if (response.statusCode() == 403) {
                throw new ForbiddenException("BENEFIT_RELEASE_GATE_FORBIDDEN",
                        "current identity cannot validate benefit release eligibility");
            }
            if (response.statusCode() == 400 || response.statusCode() == 404 || response.statusCode() == 409) {
                throw new ConflictException(problem.code(), problem.detail());
            }
            throw unavailable("benefit release gate returned HTTP " + response.statusCode(), null);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw unavailable("benefit release gate request was interrupted", interrupted);
        } catch (IOException | RuntimeException failure) {
            if (failure instanceof DomainException domainFailure) throw domainFailure;
            throw unavailable("benefit release gate request failed", failure);
        }
    }

    private Problem problem(String body) {
        try {
            JsonNode node = mapper.readTree(body);
            String code = node.path("code").asString();
            String detail = node.path("detail").asString();
            if (!code.isBlank() && !detail.isBlank()) return new Problem(code, detail);
        } catch (RuntimeException ignored) {
            // 下游未返回契约 problem 时按依赖异常处理。
        }
        return new Problem("BENEFIT_RELEASE_GATE_REJECTED", "benefit release gate rejected the artifact closure");
    }

    private static String currentAuthorization() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        Jwt jwt = authentication instanceof JwtAuthenticationToken token
                ? token.getToken()
                : authentication != null && authentication.getPrincipal() instanceof Jwt value ? value : null;
        return jwt == null ? null : "Bearer " + jwt.getTokenValue();
    }

    private static DependencyUnavailableException unavailable(String message, Throwable cause) {
        var exception = new DependencyUnavailableException("BENEFIT_RELEASE_GATE_UNAVAILABLE", message);
        if (cause != null) exception.initCause(cause);
        return exception;
    }

    private record Problem(String code, String detail) { }
}
