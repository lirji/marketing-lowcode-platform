package com.acme.marketing.provider;

import com.acme.marketing.platform.crypto.CanonicalMapCodec;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public final class SignedHttpProviderConnector implements ProviderConnector {
    private final HttpClient client;
    private final HmacRequestSigner signer;
    private final Clock clock;
    private final Sleeper sleeper;
    private final ConcurrentHashMap<String, AtomicInteger> consecutiveFailures = new ConcurrentHashMap<>();

    public SignedHttpProviderConnector(HttpClient client, HmacRequestSigner signer, Clock clock, Sleeper sleeper) {
        this.client = Objects.requireNonNull(client, "client");
        this.signer = Objects.requireNonNull(signer, "signer");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
    }

    @Override
    public ProviderResult send(ProviderRequest request, ProviderPolicy policy) {
        if (request.endpoint() == null) {
            throw new IllegalArgumentException("endpoint is required for HTTP connector");
        }
        AtomicInteger failures = consecutiveFailures.computeIfAbsent(request.tenantId(), ignored -> new AtomicInteger());
        if (failures.get() >= policy.circuitFailureThreshold()) {
            return result(ProviderResult.Status.CIRCUIT_OPEN, request.contactKey(), "CIRCUIT_OPEN", 0);
        }
        byte[] body = body(request);
        for (int attempt = 1; attempt <= policy.maxAttempts(); attempt++) {
            Instant timestamp = clock.instant();
            HttpRequest httpRequest = HttpRequest.newBuilder(request.endpoint())
                    .timeout(policy.timeout())
                    .header("Content-Type", "application/vnd.marketing-provider-v1")
                    .header("Idempotency-Key", request.contactKey())
                    .header("X-Marketing-Timestamp", timestamp.toString())
                    .header("X-Marketing-Signature", signer.sign(request.contactKey(), timestamp, body))
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();
            try {
                HttpResponse<String> response = client.send(httpRequest, HttpResponse.BodyHandlers.ofString());
                int status = response.statusCode();
                if (status >= 200 && status < 300) {
                    failures.set(0);
                    String requestId = response.headers().firstValue("X-Provider-Request-Id")
                            .orElse(request.contactKey());
                    return new ProviderResult(ProviderResult.Status.ACCEPTED, requestId, "HTTP_" + status,
                            attempt, clock.instant(), Map.of());
                }
                if (!policy.retryableStatusCodes().contains(status)) {
                    failures.incrementAndGet();
                    return result(ProviderResult.Status.PERMANENT_FAILURE, request.contactKey(), "HTTP_" + status, attempt);
                }
            } catch (IOException | InterruptedException failure) {
                if (failure instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
            }
            failures.incrementAndGet();
            if (attempt < policy.maxAttempts()) {
                sleeper.sleep(backoff(policy.initialBackoff(), attempt));
            }
        }
        return result(ProviderResult.Status.RETRY_EXHAUSTED, request.contactKey(), "RETRY_EXHAUSTED",
                policy.maxAttempts());
    }

    private ProviderResult result(ProviderResult.Status status, String requestId, String code, int attempts) {
        return new ProviderResult(status, requestId, code, attempts, clock.instant(), Map.of());
    }

    private static Duration backoff(Duration initial, int attempt) {
        long multiplier = 1L << Math.min(attempt - 1, 20);
        return initial.multipliedBy(multiplier);
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

    @FunctionalInterface
    public interface Sleeper {
        void sleep(Duration duration);
    }
}
