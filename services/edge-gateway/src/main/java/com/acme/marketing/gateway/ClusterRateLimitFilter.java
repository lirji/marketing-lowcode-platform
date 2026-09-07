package com.acme.marketing.gateway;

import com.acme.marketing.platform.web.TenantContextHolder;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 以 Redis server time + Lua token bucket 实现所有 Gateway 副本共享的租户配额。
 * 限流基础设施不可用时 fail closed，避免退化成各 JVM 独立配额后静默放大流量。
 */
public final class ClusterRateLimitFilter extends OncePerRequestFilter {
    private static final DefaultRedisScript<Long> TOKEN_BUCKET = new DefaultRedisScript<>("""
            local rate = tonumber(ARGV[1])
            local burst = tonumber(ARGV[2])
            local cost = tonumber(ARGV[3])
            local time = redis.call('TIME')
            local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
            local state = redis.call('HMGET', KEYS[1], 'tokens', 'updated')
            local tokens = tonumber(state[1])
            local updated = tonumber(state[2])
            if tokens == nil then tokens = burst end
            if updated == nil then updated = now end
            if now > updated then
              tokens = math.min(burst, tokens + ((now - updated) * rate / 1000))
            end
            local allowed = 0
            if tokens >= cost then
              tokens = tokens - cost
              allowed = 1
            end
            redis.call('HSET', KEYS[1], 'tokens', tostring(tokens), 'updated', tostring(now))
            local ttl = math.max(1000, math.ceil((burst / rate) * 2000))
            redis.call('PEXPIRE', KEYS[1], ttl)
            return allowed
            """, Long.class);

    private final StringRedisTemplate redis;
    private final boolean enabled;
    private final Limit events;
    private final Limit decisions;
    private final Limit writes;
    private final Limit reads;
    private final Counter allowed;
    private final Counter throttled;
    private final Counter unavailable;

    public ClusterRateLimitFilter(StringRedisTemplate redis, MeterRegistry meters,
            @Value("${marketing.gateway.rate-limit.enabled:true}") boolean enabled,
            @Value("${marketing.gateway.rate-limit.events-per-second:10000}") long eventRate,
            @Value("${marketing.gateway.rate-limit.events-burst:10000}") long eventBurst,
            @Value("${marketing.gateway.rate-limit.decisions-per-second:30000}") long decisionRate,
            @Value("${marketing.gateway.rate-limit.decisions-burst:30000}") long decisionBurst,
            @Value("${marketing.gateway.rate-limit.writes-per-second:2000}") long writeRate,
            @Value("${marketing.gateway.rate-limit.writes-burst:4000}") long writeBurst,
            @Value("${marketing.gateway.rate-limit.reads-per-second:5000}") long readRate,
            @Value("${marketing.gateway.rate-limit.reads-burst:10000}") long readBurst) {
        this.redis = redis;
        this.enabled = enabled;
        this.events = new Limit("events", eventRate, eventBurst);
        this.decisions = new Limit("decisions", decisionRate, decisionBurst);
        this.writes = new Limit("writes", writeRate, writeBurst);
        this.reads = new Limit("reads", readRate, readBurst);
        this.allowed = Counter.builder("marketing.gateway.rate_limit.requests")
                .tag("outcome", "allowed").register(meters);
        this.throttled = Counter.builder("marketing.gateway.rate_limit.requests")
                .tag("outcome", "throttled").register(meters);
        this.unavailable = Counter.builder("marketing.gateway.rate_limit.requests")
                .tag("outcome", "unavailable").register(meters);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !enabled || "OPTIONS".equals(request.getMethod())
                || request.getRequestURI().startsWith("/actuator/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        var scope = TenantContextHolder.current();
        if (scope.isEmpty()) {
            // 身份过滤器负责稳定的 401；本过滤器不从未经验证的 header 自行构造租户。
            chain.doFilter(request, response);
            return;
        }
        Limit limit = classify(request);
        String tenantId = scope.get().tenantId().value();
        Long accepted;
        try {
            accepted = redis.execute(TOKEN_BUCKET,
                    List.of("marketing:gateway:rate:{" + tenantId + "}:" + limit.name()),
                    Long.toString(limit.rate()), Long.toString(limit.burst()), "1");
        } catch (RuntimeException failure) {
            unavailable.increment();
            writeProblem(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    "GATEWAY_RATE_LIMITER_UNAVAILABLE",
                    "cluster rate limiter is unavailable; retry later", true);
            return;
        }
        if (accepted == null) {
            // RedisTemplate 在连接中断或脚本结果无法反序列化时可能返回 null；此时不能当作配额耗尽。
            unavailable.increment();
            writeProblem(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    "GATEWAY_RATE_LIMITER_UNAVAILABLE",
                    "cluster rate limiter returned no decision; retry later", true);
            return;
        }
        if (!Long.valueOf(1L).equals(accepted)) {
            throttled.increment();
            response.setHeader("Retry-After", "1");
            writeProblem(response, 429, "TENANT_RATE_LIMITED",
                    "tenant request quota is exhausted", true);
            return;
        }
        allowed.increment();
        response.setHeader("X-RateLimit-Limit", Long.toString(limit.rate()));
        chain.doFilter(request, response);
    }

    private Limit classify(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (path.equals("/api/v1/events")) return events;
        if (path.startsWith("/api/v1/decisions")) return decisions;
        return List.of("POST", "PUT", "PATCH", "DELETE").contains(request.getMethod()) ? writes : reads;
    }

    private static void writeProblem(HttpServletResponse response, int status, String code,
            String detail, boolean retryable) throws IOException {
        response.setStatus(status);
        response.setContentType("application/problem+json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"type\":\"urn:marketing:problem:" + code.toLowerCase().replace('_', '-')
                + "\",\"title\":\"" + (status == 429 ? "Too Many Requests" : "Service Unavailable")
                + "\",\"status\":" + status + ",\"code\":\"" + code + "\",\"detail\":\"" + detail
                + "\",\"retryable\":" + retryable + "}");
    }

    private record Limit(String name, long rate, long burst) {
        private Limit {
            if (rate < 1 || burst < 1 || burst < rate || rate > 1_000_000 || burst > 2_000_000) {
                throw new IllegalArgumentException("gateway rate limit is invalid for " + name);
            }
        }
    }
}
