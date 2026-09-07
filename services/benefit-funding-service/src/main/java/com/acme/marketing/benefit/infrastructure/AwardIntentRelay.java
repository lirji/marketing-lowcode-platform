package com.acme.marketing.benefit.infrastructure;

import static com.acme.marketing.platform.time.SqlTime.format;

import com.acme.marketing.benefit.application.AwardIntentRelayRepository;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * 以租约/CAS 投递 CENTER 模式 AwardIntent。进程在 HTTP 成功后崩溃时会以同一幂等键重试，
 * 由权益中台返回原订单而不会重复履约。
 */
public final class AwardIntentRelay {
    private final AwardIntentRelayRepository repository;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final TransactionTemplate transactions;
    private final HttpClient http;
    private final String baseUrl;
    private final String bearerToken;
    private final boolean enabled;
    private final int batchSize;
    private final int tenantBatchSize;
    private final int maxAttempts;
    private final int circuitFailureThreshold;
    private final Duration requestTimeout;
    private final Duration leaseDuration;
    private final Duration circuitOpenDuration;
    private final AtomicInteger consecutiveTransientFailures = new AtomicInteger();
    private final AtomicReference<Instant> circuitOpenUntil = new AtomicReference<>();
    private final String workerId = "marketing-award-relay-" + UUID.randomUUID();

    /** 构造带批量隔离、租约 fencing、重试和熔断保护的权益中台 Relay。 */
    public AwardIntentRelay(AwardIntentRelayRepository repository, ObjectMapper mapper, Clock clock,
            PlatformTransactionManager transactionManager, String baseUrl, String bearerToken,
            boolean enabled, int batchSize, int tenantBatchSize, int maxAttempts,
            int circuitFailureThreshold, Duration connectTimeout, Duration requestTimeout,
            Duration leaseDuration, Duration circuitOpenDuration) {
        if (batchSize < 1 || batchSize > 1_000 || tenantBatchSize < 1
                || tenantBatchSize > batchSize || maxAttempts < 1 || maxAttempts > 100
                || circuitFailureThreshold < 1 || circuitFailureThreshold > 100) {
            throw new IllegalArgumentException("award relay batch or retry policy is invalid");
        }
        if (connectTimeout.isZero() || connectTimeout.isNegative()
                || requestTimeout.isZero() || requestTimeout.isNegative()
                || leaseDuration.compareTo(requestTimeout) <= 0
                || circuitOpenDuration.isZero() || circuitOpenDuration.isNegative()) {
            throw new IllegalArgumentException("award relay timeouts are invalid");
        }
        this.repository = repository;
        this.mapper = mapper;
        this.clock = clock;
        this.transactions = new TransactionTemplate(transactionManager);
        this.http = HttpClient.newBuilder().connectTimeout(connectTimeout).build();
        this.baseUrl = normalize(baseUrl);
        this.bearerToken = bearerToken == null ? "" : bearerToken.trim();
        this.enabled = enabled;
        this.batchSize = batchSize;
        this.tenantBatchSize = tenantBatchSize;
        this.maxAttempts = maxAttempts;
        this.circuitFailureThreshold = circuitFailureThreshold;
        this.requestTimeout = requestTimeout;
        this.leaseDuration = leaseDuration;
        this.circuitOpenDuration = circuitOpenDuration;
    }

    /** 投递一个有界批次；关闭开关时绝不读取或调用权益中台。 */
    public Result relayOnce() {
        if (!enabled) return new Result(0, 0, 0);
        Instant now = clock.instant();
        if (!circuitAllows(now)) return new Result(0, 0, 0);
        // 窗口排名限制单租户占用的候选数，避免热点租户耗尽整个中继批次。
        List<AwardIntentRelayRepository.PendingIntent> candidates = repository.findCandidates(
                format(now), tenantBatchSize, batchSize);
        int sent = 0;
        int retried = 0;
        int dead = 0;
        for (AwardIntentRelayRepository.PendingIntent row : candidates) {
            AwardIntentRelayRepository.PendingIntent claimed = claim(row, now);
            if (claimed == null) continue;
            try {
                String orderNo = send(claimed);
                if (markSent(claimed, orderNo, clock.instant())) {
                    recordCircuitSuccess();
                    sent++;
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                if (recordFailure(claimed, interrupted, clock.instant())) dead++;
                else retried++;
                break;
            } catch (IOException | RuntimeException failure) {
                if (recordFailure(claimed, failure, clock.instant())) dead++;
                else retried++;
                if (recordCircuitFailure(failure, clock.instant())) break;
            }
        }
        return new Result(sent, retried, dead);
    }

    private AwardIntentRelayRepository.PendingIntent claim(AwardIntentRelayRepository.PendingIntent row,
            Instant now) {
        Boolean claimed = transactions.execute(status -> repository.claim(
                new AwardIntentRelayRepository.ClaimWrite(row.tenantId(), row.intentId(), workerId,
                        row.leaseVersion(), format(now.plus(leaseDuration)), format(now))) == 1);
        return Boolean.TRUE.equals(claimed)
                ? new AwardIntentRelayRepository.PendingIntent(row.tenantId(), row.intentId(),
                        row.sourceRequestId(), row.payload(), row.attempts(), row.leaseVersion() + 1)
                : null;
    }

    private String send(AwardIntentRelayRepository.PendingIntent row) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + "/openapi/v1/award-orders"))
                .timeout(requestTimeout)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", row.sourceRequestId())
                // 租户取自持久化 outbox 行；机器 Token 只认证 Relay，不能替代业务租户路由。
                .header("X-Tenant-Id", row.tenantId())
                .POST(HttpRequest.BodyPublishers.ofString(row.payload()));
        if (!bearerToken.isBlank()) request.header("Authorization", "Bearer " + bearerToken);
        HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 202) {
            throw new AwardDeliveryException(response.statusCode(),
                    "benefit center returned HTTP " + response.statusCode());
        }
        try {
            String orderNo = mapper.readTree(response.body()).path("awardOrderNo").asString();
            if (orderNo == null || orderNo.isBlank() || orderNo.length() > 64) {
                throw new AwardDeliveryException(502, "benefit center response has no valid awardOrderNo");
            }
            return orderNo;
        } catch (JacksonException invalid) {
            throw new AwardDeliveryException(502, "benefit center response is invalid", invalid);
        }
    }

    private boolean markSent(AwardIntentRelayRepository.PendingIntent row, String orderNo, Instant now) {
        Boolean marked = transactions.execute(status -> repository.markSent(
                new AwardIntentRelayRepository.SentWrite(row.tenantId(), row.intentId(), workerId,
                        row.leaseVersion(), orderNo, format(now))) == 1);
        return Boolean.TRUE.equals(marked);
    }

    private boolean recordFailure(AwardIntentRelayRepository.PendingIntent row, Throwable failure, Instant now) {
        int attempts = row.attempts() + 1;
        String reason = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
        if (reason.length() > 1_000) reason = reason.substring(0, 1_000);
        boolean permanent = isPermanent(failure);
        boolean dead = permanent || attempts >= maxAttempts;
        String next = format(now.plusSeconds(Math.min(300, 1L << Math.min(8, Math.max(0, attempts - 1)))));
        String finalReason = reason;
        transactions.executeWithoutResult(status -> repository.markFailure(
                new AwardIntentRelayRepository.FailureWrite(row.tenantId(), row.intentId(), workerId,
                        row.leaseVersion(), dead ? "DEAD" : "PENDING", next, finalReason, format(now))));
        return dead;
    }

    private boolean circuitAllows(Instant now) {
        Instant until = circuitOpenUntil.get();
        if (until == null) return true;
        if (now.isBefore(until)) return false;
        return circuitOpenUntil.compareAndSet(until, null);
    }

    private void recordCircuitSuccess() {
        consecutiveTransientFailures.set(0);
        circuitOpenUntil.set(null);
    }

    private boolean recordCircuitFailure(Throwable failure, Instant now) {
        if (isPermanent(failure)) {
            consecutiveTransientFailures.set(0);
            return false;
        }
        if (consecutiveTransientFailures.incrementAndGet() < circuitFailureThreshold) return false;
        consecutiveTransientFailures.set(0);
        circuitOpenUntil.set(now.plus(circuitOpenDuration));
        return true;
    }

    private static boolean isPermanent(Throwable failure) {
        return failure instanceof AwardDeliveryException delivery
                && (delivery.statusCode == 400 || delivery.statusCode == 409 || delivery.statusCode == 422);
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("benefit center base URL is required");
        String result = value.trim();
        return result.endsWith("/") ? result.substring(0, result.length() - 1) : result;
    }

    /** 单批投递结果，供调度器和监控统计使用。 */
    public record Result(int sent, int retried, int dead) { }
    private static final class AwardDeliveryException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final int statusCode;

        private AwardDeliveryException(int statusCode, String message) {
            super(message);
            this.statusCode = statusCode;
        }

        private AwardDeliveryException(int statusCode, String message, Throwable cause) {
            super(message, cause);
            this.statusCode = statusCode;
        }
    }
}
