package com.acme.marketing.provider;

import com.acme.marketing.platform.crypto.CanonicalMapCodec;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.ThreadLocalRandom;

/** 签名渠道适配器，以有界重试、限速和可恢复熔断保护外部服务商。 */
public final class SignedHttpProviderConnector implements ProviderConnector {
    private static final int MAX_ROUTES = 10_000;
    private static final Duration MAX_BACKOFF = Duration.ofSeconds(30);
    private static final String INTERRUPTED = "INTERRUPTED";
    private static final String CAPACITY_LIMIT = "PROVIDER_CAPACITY_LIMIT";
    private final HttpClient client;
    private final HmacRequestSigner signer;
    private final Clock clock;
    private final Sleeper sleeper;
    private final Map<Route, RouteState> routes = new HashMap<>();

    /** 复用调用方的 HTTP 连接池、时间源和可中断等待机制。 */
    public SignedHttpProviderConnector(HttpClient client, HmacRequestSigner signer, Clock clock, Sleeper sleeper) {
        this.client = Objects.requireNonNull(client, "client");
        this.signer = Objects.requireNonNull(signer, "signer");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
    }

    /** 每次真实 HTTP 尝试均计入限速；同一幂等键贯穿所有尝试。 */
    @Override
    public ProviderResult send(ProviderRequest request, ProviderPolicy policy) {
        if (request.endpoint() == null) {
            throw new IllegalArgumentException("endpoint is required for HTTP connector");
        }
        RouteState state = routeState(new Route(request.tenantId(), request.channel(), request.endpoint()));
        if (state == null) return result(ProviderResult.Status.RATE_LIMITED, request.contactKey(), CAPACITY_LIMIT, 0);
        byte[] body = body(request);
        int attempts = 0;
        while (attempts < policy.maxAttempts()) {
            if (Thread.currentThread().isInterrupted()) {
                return result(ProviderResult.Status.RETRY_EXHAUSTED, request.contactKey(), INTERRUPTED, attempts);
            }
            Admission admission = state.acquire(clock.instant(), policy);
            if (admission.rejection() != null) {
                return result(admission.rejection(), request.contactKey(), admission.rejection().name(), attempts);
            }
            attempts++;
            boolean resolved = false;
            try {
                Instant timestamp = clock.instant();
                HttpRequest httpRequest = HttpRequest.newBuilder(request.endpoint())
                        .timeout(policy.timeout())
                        .header("Content-Type", "application/vnd.marketing-provider-v1")
                        .header("Idempotency-Key", request.contactKey())
                        .header("X-Marketing-Timestamp", timestamp.toString())
                        .header("X-Marketing-Signature", signer.sign(request.contactKey(), timestamp, body))
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                        .build();
                HttpResponse<String> response = client.send(httpRequest, HttpResponse.BodyHandlers.ofString());
                int status = response.statusCode();
                if (status >= 200 && status < 300) {
                    state.complete(admission, true, clock.instant(), policy);
                    resolved = true;
                    String requestId = response.headers().firstValue("X-Provider-Request-Id")
                            .orElse(request.contactKey());
                    return new ProviderResult(ProviderResult.Status.ACCEPTED, requestId, "HTTP_" + status,
                            attempts, clock.instant(), Map.of());
                }
                if (!policy.retryableStatusCodes().contains(status)) {
                    return result(ProviderResult.Status.PERMANENT_FAILURE, request.contactKey(), "HTTP_" + status, attempts);
                }
            } catch (IOException failure) {
                // 网络失败可能已经送达；继续使用原幂等键，由服务商去重。
                state.complete(admission, false, clock.instant(), policy);
                resolved = true;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return result(ProviderResult.Status.RETRY_EXHAUSTED, request.contactKey(), INTERRUPTED, attempts);
            } finally {
                // 构造请求或发送发生异常也必须释放半开探测，避免永久卡在半开状态。
                if (!resolved) state.complete(admission, false, clock.instant(), policy);
            }
            if (admission.probe()) break;
            if (attempts < policy.maxAttempts()) sleeper.sleep(backoff(policy.initialBackoff(), attempts));
        }
        return result(ProviderResult.Status.RETRY_EXHAUSTED, request.contactKey(), "RETRY_EXHAUSTED", attempts);
    }

    private synchronized RouteState routeState(Route route) {
        RouteState existing = routes.get(route);
        if (existing != null) return existing;
        // 不驱逐仍承担熔断/限速语义的记录；容量耗尽明确拒绝，避免路由抖动绕过保护。
        if (routes.size() >= MAX_ROUTES) return null;
        RouteState created = new RouteState();
        routes.put(route, created);
        return created;
    }

    private ProviderResult result(ProviderResult.Status status, String requestId, String code, int attempts) {
        return new ProviderResult(status, requestId, code, attempts, clock.instant(), Map.of());
    }

    private static Duration backoff(Duration initial, int attempt) {
        double millis = Math.min(MAX_BACKOFF.toMillis(), initial.toMillis() * Math.pow(2, Math.min(attempt - 1, 20)));
        return Duration.ofMillis((long) (millis * ThreadLocalRandom.current().nextDouble(0.5, 1.0)));
    }

    private static byte[] body(ProviderRequest request) {
        Map<String, String> payload = new TreeMap<>();
        payload.put("tenantId", request.tenantId());
        payload.put("contactKey", request.contactKey());
        payload.put("channel", request.channel());
        payload.put("recipientToken", request.recipientToken());
        payload.put("templateVersion", request.templateVersion());
        request.variables().forEach((key, value) -> payload.put("variable." + key, String.valueOf(value)));
        return CanonicalMapCodec.encode(payload);
    }

    private record Route(String tenantId, String channel, URI endpoint) { }
    private record Admission(ProviderResult.Status rejection, long generation, boolean probe) { }

    /** 所有复合状态变化在一个锁内完成；锁不跨越网络调用或退避。 */
    private static final class RouteState {
        private int failures;
        private long generation;
        private Instant openUntil;
        private boolean probing;
        private Instant refilledAt;
        private double tokens;

        synchronized Admission acquire(Instant now, ProviderPolicy policy) {
            if (openUntil != null && (now.isBefore(openUntil) || probing)) {
                return new Admission(ProviderResult.Status.CIRCUIT_OPEN, generation, false);
            }
            if (refilledAt == null) {
                tokens = policy.requestsPerSecond();
                refilledAt = now;
            } else if (now.isAfter(refilledAt)) {
                Duration elapsed = Duration.between(refilledAt, now);
                double elapsedSeconds = elapsed.getSeconds() + elapsed.getNano() / 1_000_000_000.0;
                tokens = Math.min(policy.requestsPerSecond(), tokens + elapsedSeconds * policy.requestsPerSecond());
                refilledAt = now;
            }
            tokens = Math.min(tokens, policy.requestsPerSecond());
            if (tokens < 1) return new Admission(ProviderResult.Status.RATE_LIMITED, generation, false);
            tokens--;
            boolean probe = openUntil != null;
            if (probe) probing = true;
            return new Admission(null, generation, probe);
        }

        synchronized void complete(Admission admission, boolean success, Instant now, ProviderPolicy policy) {
            // 熔断前已经在途的成功不能关闭后来打开的熔断器。
            if (admission.generation() != generation) return;
            if (success) {
                failures = 0;
                if (admission.probe()) {
                    openUntil = null;
                    probing = false;
                    generation++;
                }
            } else if (admission.probe() || ++failures >= policy.circuitFailureThreshold()) {
                openUntil = now.plus(policy.circuitOpenDuration());
                probing = false;
                generation++;
            }
        }
    }

    /** 可注入等待实现以验证时间预算，不在测试中真实长时间休眠。 */
    @FunctionalInterface
    public interface Sleeper {
        void sleep(Duration duration);
    }
}
