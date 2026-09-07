package com.acme.marketing.benefit.infrastructure;

import com.acme.marketing.benefit.application.BenefitSkuCatalog;
import com.acme.marketing.platform.error.ConflictException;
import com.acme.marketing.platform.error.DependencyUnavailableException;
import com.acme.marketing.platform.error.DomainException;
import com.acme.marketing.platform.error.ForbiddenException;
import com.acme.marketing.platform.error.UnauthorizedException;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import tools.jackson.databind.ObjectMapper;

/**
 * 通过权益中台内部只读 API 提供营销 SKU 目录，并隔离每个业务租户的短时列表缓存。
 */
public final class HttpBenefitSkuCatalog implements BenefitSkuCatalog {
    private static final int PAGE_SIZE = 100;
    private static final int MAX_PAGES = 1000;

    private final HttpClient http;
    private final ObjectMapper mapper;
    private final String baseUrl;
    private final String bearerToken;
    private final Duration requestTimeout;
    private final Cache<CacheKey, List<BenefitSkuView>> cache;

    /** 构造带有界 L1 缓存和服务身份透传的权益目录客户端。 */
    public HttpBenefitSkuCatalog(ObjectMapper mapper, String baseUrl, String bearerToken,
            Duration connectTimeout, Duration requestTimeout, Duration cacheTtl) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.baseUrl = normalizeBaseUrl(baseUrl);
        this.bearerToken = bearerToken == null ? "" : bearerToken.trim();
        this.requestTimeout = requirePositive(requestTimeout, "requestTimeout");
        this.http = HttpClient.newBuilder()
                .connectTimeout(requirePositive(connectTimeout, "connectTimeout"))
                .build();
        this.cache = Caffeine.newBuilder()
                .maximumSize(1000)
                .expireAfterWrite(requirePositive(cacheTtl, "cacheTtl"))
                .build();
    }

    @Override
    public List<BenefitSkuView> list(String tenantId, SkuStatus status) {
        String requiredTenant = requireText(tenantId, "tenantId");
        SkuStatus requiredStatus = Objects.requireNonNull(status, "status");
        return cache.get(new CacheKey(requiredTenant, requiredStatus),
                key -> fetchAll(key.tenantId(), key.status()));
    }

    @Override
    public BenefitSkuView requireActiveSku(String tenantId, String skuId) {
        String requiredSkuId = requireText(skuId, "benefitSkuId");
        // 发布门禁故意绕过 L1，避免 ACTIVE→PAUSED 的缓存窗口允许新营销定义发布。
        return fetchAll(requireText(tenantId, "tenantId"), SkuStatus.ACTIVE).stream()
                .filter(BenefitSkuView::enabled)
                .filter(sku -> requiredSkuId.equals(sku.skuId()))
                .findFirst()
                .orElseThrow(() -> new ConflictException("SKU_NOT_ACTIVE",
                        "benefit SKU is missing or not ACTIVE: " + requiredSkuId));
    }

    private List<BenefitSkuView> fetchAll(String tenantId, SkuStatus status) {
        List<BenefitSkuView> result = new ArrayList<>();
        String afterSkuId = null;
        for (int page = 0; page < MAX_PAGES; page++) {
            List<BenefitSkuView> rows = fetchPage(tenantId, status, afterSkuId);
            rows.stream()
                    .filter(row -> row.status() == status)
                    .filter(row -> status != SkuStatus.ACTIVE || row.enabled())
                    .forEach(result::add);
            if (rows.size() < PAGE_SIZE) return List.copyOf(result);
            String next = rows.getLast().skuId();
            if (next == null || next.isBlank() || next.equals(afterSkuId)) {
                throw unavailable("benefit SKU pagination did not advance", null);
            }
            afterSkuId = next;
        }
        throw unavailable("benefit SKU pagination exceeded safety limit", null);
    }

    private List<BenefitSkuView> fetchPage(String tenantId, SkuStatus status, String afterSkuId) {
        StringBuilder path = new StringBuilder("/internal/v1/catalog/skus?status=")
                .append(status.name()).append("&limit=").append(PAGE_SIZE);
        if (afterSkuId != null) {
            path.append("&afterSkuId=").append(URLEncoder.encode(afterSkuId, StandardCharsets.UTF_8));
        }
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(requestTimeout)
                .header("Accept", "application/json")
                // 该值来自营销已验证上下文；机器 Token 只认证调用者，不代表业务租户。
                .header("X-Tenant-Id", tenantId)
                .GET();
        // 目录是 M2M 契约：只使用专用机器身份，不能把当前人类会话 JWT 转发给权益中台。
        if (!bearerToken.isBlank()) request.header("Authorization", "Bearer " + bearerToken);
        try {
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 401) {
                throw new UnauthorizedException("BENEFIT_CATALOG_AUTH_REQUIRED",
                        "benefit catalog machine authentication is missing or invalid");
            }
            if (response.statusCode() == 403 && isTenantProblem(response.body())) {
                throw new ForbiddenException("BENEFIT_CATALOG_TENANT_MISMATCH",
                        "benefit catalog rejected the delegated business tenant");
            }
            if (response.statusCode() == 403) {
                throw new ForbiddenException("BENEFIT_CATALOG_ACCESS_DENIED",
                        "current identity cannot read the tenant benefit catalog");
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw unavailable("benefit center returned HTTP " + response.statusCode(), null);
            }
            BenefitSkuView[] values = mapper.readValue(response.body(), BenefitSkuView[].class);
            return List.of(values);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw unavailable("benefit center request was interrupted", interrupted);
        } catch (IOException | RuntimeException failure) {
            if (failure instanceof DependencyUnavailableException dependencyFailure) throw dependencyFailure;
            if (failure instanceof DomainException domainFailure) throw domainFailure;
            throw unavailable("benefit center request failed", failure);
        }
    }

    /** 下游租户问题有稳定 code；解析失败时按普通权限不足处理，避免把任意 403 误标为串租户。 */
    private boolean isTenantProblem(String body) {
        try {
            String code = mapper.readTree(body).path("code").asString();
            return "BENEFIT_TENANT_REQUIRED".equals(code)
                    || "BENEFIT_TENANT_MISMATCH".equals(code)
                    || "BENEFIT_TENANT_UNMAPPED".equals(code);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static DependencyUnavailableException unavailable(String message, Throwable cause) {
        var exception = new DependencyUnavailableException("BENEFIT_CATALOG_UNAVAILABLE", message);
        if (cause != null) exception.initCause(cause);
        return exception;
    }

    private static String normalizeBaseUrl(String value) {
        String url = requireText(value, "baseUrl");
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static Duration requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value.trim();
    }

    private record CacheKey(String tenantId, SkuStatus status) { }
}
