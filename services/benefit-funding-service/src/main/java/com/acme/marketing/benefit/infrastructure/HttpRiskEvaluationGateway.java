package com.acme.marketing.benefit.infrastructure;

import com.acme.marketing.benefit.application.RiskEvaluationGateway;
import com.acme.marketing.benefit.application.RiskEvaluationGateway.RiskDecision;
import com.acme.marketing.benefit.application.RiskEvaluationGateway.RiskEvaluationRequest;
import com.acme.marketing.benefit.application.RiskEvaluationGateway.RiskEvaluationUnavailableException;
import com.acme.marketing.platform.error.DomainException;
import com.acme.marketing.platform.isolation.TenantBulkhead;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapPropagator;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * risk-platform HTTP 适配器。专用有界线程池、租户 bulkhead 和断路器共同隔离风控调用，
 * 避免风控抖动占满 AwardIntent 请求或发奖 Relay 的执行资源。
 */
public final class HttpRiskEvaluationGateway implements RiskEvaluationGateway {
    private static final String SOURCE_ID = "MARKETING_AWARD";
    private static final String CHANNEL = "API";
    private static final String BIZ_TYPE = "PAYMENT";

    private final ObjectMapper mapper;
    private final Clock clock;
    private final String baseUrl;
    private final String bearerToken;
    private final Duration requestTimeout;
    private final int circuitFailureThreshold;
    private final Duration circuitOpenDuration;
    private final TenantBulkhead bulkhead;
    private final ThreadPoolExecutor executor;
    private final HttpClient http;
    private final Tracer tracer;
    private final TextMapPropagator propagator;
    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private final AtomicReference<Instant> circuitOpenUntil = new AtomicReference<>();

    /** 构造完全独立于发奖 Relay 的有界风控客户端资源。 */
    public HttpRiskEvaluationGateway(ObjectMapper mapper, Clock clock, String baseUrl, String bearerToken,
            Duration connectTimeout, Duration requestTimeout, int permitsPerTenant, Duration bulkheadWait,
            int threadCount, int queueCapacity, int circuitFailureThreshold, Duration circuitOpenDuration,
            OpenTelemetry openTelemetry) {
        if (requestTimeout.isZero() || requestTimeout.isNegative() || threadCount < 1 || queueCapacity < 1
                || circuitFailureThreshold < 1 || circuitOpenDuration.isNegative()) {
            throw new IllegalArgumentException("invalid risk client configuration");
        }
        this.mapper = mapper;
        this.clock = clock;
        this.baseUrl = normalize(baseUrl);
        this.bearerToken = bearerToken == null ? "" : bearerToken.trim();
        this.requestTimeout = requestTimeout;
        this.circuitFailureThreshold = circuitFailureThreshold;
        this.circuitOpenDuration = circuitOpenDuration;
        this.tracer = openTelemetry.getTracer("marketing-benefit-risk-client");
        this.propagator = openTelemetry.getPropagators().getTextMapPropagator();
        this.bulkhead = new TenantBulkhead(permitsPerTenant, bulkheadWait);
        AtomicInteger sequence = new AtomicInteger();
        ThreadFactory factory = task -> {
            Thread thread = new Thread(task, "risk-evaluation-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        this.executor = new ThreadPoolExecutor(threadCount, threadCount, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity), factory, new ThreadPoolExecutor.AbortPolicy());
        // 阻塞 send 只在该专用池运行；HttpClient 的内部执行器由该客户端实例自行管理。
        this.http = HttpClient.newBuilder().connectTimeout(connectTimeout).build();
    }

    @Override
    public RiskDecision evaluate(RiskEvaluationRequest request) {
        try {
            return bulkhead.execute(request.tenantId(), () -> evaluateWithinBulkhead(request));
        } catch (RiskEvaluationUnavailableException unavailable) {
            throw unavailable;
        } catch (DomainException capacityFailure) {
            recordFailure(clock.instant());
            throw unavailable("RISK_CLIENT_CAPACITY_EXCEEDED", capacityFailure);
        } catch (RuntimeException clientFailure) {
            recordFailure(clock.instant());
            throw unavailable("RISK_CLIENT_FAILURE", clientFailure);
        }
    }

    private RiskDecision evaluateWithinBulkhead(RiskEvaluationRequest request) {
        Instant now = clock.instant();
        if (!circuitAllows(now)) throw unavailable("RISK_CIRCUIT_OPEN", null);
        Future<RiskDecision> future;
        Context callerContext = Context.current();
        try {
            future = executor.submit(() -> sendTraced(request, callerContext));
        } catch (RuntimeException rejected) {
            recordFailure(now);
            throw unavailable("RISK_CLIENT_CAPACITY_EXCEEDED", rejected);
        }
        try {
            RiskDecision decision = future.get(requestTimeout.toMillis() + 100L, TimeUnit.MILLISECONDS);
            consecutiveFailures.set(0);
            circuitOpenUntil.set(null);
            return decision;
        } catch (TimeoutException timeout) {
            future.cancel(true);
            recordFailure(clock.instant());
            throw unavailable("RISK_TIMEOUT", timeout);
        } catch (InterruptedException interrupted) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            recordFailure(clock.instant());
            throw unavailable("RISK_INTERRUPTED", interrupted);
        } catch (ExecutionException failed) {
            recordFailure(clock.instant());
            Throwable cause = failed.getCause();
            if (cause instanceof RiskEvaluationUnavailableException unavailable) throw unavailable;
            throw unavailable("RISK_REQUEST_FAILED", cause);
        }
    }

    private RiskDecision sendTraced(RiskEvaluationRequest request, Context parent) {
        Span span = tracer.spanBuilder("POST /api/v1/risk/evaluations")
                .setParent(parent)
                .setSpanKind(SpanKind.CLIENT)
                .startSpan();
        span.setAttribute("http.request.method", "POST");
        span.setAttribute("server.address", URI.create(baseUrl).getHost());
        span.setAttribute("marketing.tenant.id", request.tenantId().value());
        Context context = parent.with(span);
        Scope scope = context.makeCurrent();
        try {
            RiskDecision decision = send(request, context);
            span.setStatus(StatusCode.OK);
            return decision;
        } catch (RuntimeException failure) {
            span.recordException(failure);
            span.setStatus(StatusCode.ERROR, failure.getMessage() == null ? "risk request failed" : failure.getMessage());
            throw failure;
        } finally {
            scope.close();
            span.end();
        }
    }

    private RiskDecision send(RiskEvaluationRequest request, Context context) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sourceId", SOURCE_ID);
        body.put("txnId", request.transactionId());
        body.put("channel", CHANNEL);
        body.put("bizType", BIZ_TYPE);
        body.put("accountNo", request.accountNo());
        body.put("amount", request.amount());
        body.put("currency", request.currency());
        body.put("eventTime", request.eventTime().toString());
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + "/api/v1/risk/evaluations"))
                .timeout(requestTimeout)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                // 服务身份必须在 risk-platform 被授予 risk.evaluate；tenant 仅来自已认证上下文。
                .header("X-Tenant-Id", request.tenantId().value())
                .POST(HttpRequest.BodyPublishers.ofString(json(body)));
        if (!bearerToken.isBlank()) builder.header("Authorization", "Bearer " + bearerToken);
        propagator.inject(context, builder, HttpRequest.Builder::header);
        try {
            HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                String reason = response.statusCode() >= 500 ? "RISK_HTTP_5XX" : "RISK_HTTP_NON_SUCCESS";
                throw unavailable(reason, null);
            }
            return parse(response.body(), request.transactionId());
        } catch (IOException failure) {
            throw unavailable("RISK_IO_FAILURE", failure);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw unavailable("RISK_INTERRUPTED", interrupted);
        }
    }

    private RiskDecision parse(String body, String expectedTransactionId) {
        try {
            JsonNode root = mapper.readTree(body);
            String transactionId = text(root, "txnId");
            String action = text(root, "action");
            if (!expectedTransactionId.equals(transactionId) || action == null || action.isBlank()) {
                throw unavailable("RISK_RESPONSE_INVALID", null);
            }
            List<String> hits = new ArrayList<>();
            JsonNode rules = root.get("hitRules");
            if (rules != null && rules.isArray()) rules.forEach(rule -> hits.add(rule.asString()));
            return new RiskDecision(text(root, "decisionId"), action, hits);
        } catch (JacksonException invalid) {
            throw unavailable("RISK_RESPONSE_INVALID", invalid);
        }
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JacksonException impossible) {
            throw unavailable("RISK_REQUEST_SERIALIZATION_FAILED", impossible);
        }
    }

    private static String text(JsonNode root, String field) {
        JsonNode value = root.get(field);
        return value == null || value.isNull() ? null : value.asString();
    }

    private boolean circuitAllows(Instant now) {
        Instant until = circuitOpenUntil.get();
        if (until == null) return true;
        if (now.isBefore(until)) return false;
        return circuitOpenUntil.compareAndSet(until, null);
    }

    private void recordFailure(Instant now) {
        if (consecutiveFailures.incrementAndGet() < circuitFailureThreshold) return;
        consecutiveFailures.set(0);
        circuitOpenUntil.set(now.plus(circuitOpenDuration));
    }

    private static RiskEvaluationUnavailableException unavailable(String reason, Throwable cause) {
        return new RiskEvaluationUnavailableException(reason, cause);
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("risk base URL is required");
        String result = value.trim();
        return result.endsWith("/") ? result.substring(0, result.length() - 1) : result;
    }

    /** 服务关闭时终止专用线程池，避免应用重载泄漏线程。 */
    @PreDestroy
    public void close() {
        executor.shutdownNow();
    }
}
