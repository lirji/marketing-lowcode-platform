package com.acme.marketing.eventgateway.infrastructure;

import com.acme.marketing.eventgateway.application.EventOutboxDepthRepository;
import com.acme.marketing.eventgateway.application.EventOutboxRepository;
import com.acme.marketing.eventgateway.application.EventOutboxRepository.PendingEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * 使用短事务租约发布 Event outbox。claim 和结果回写各自提交，Kafka 等待严格发生在事务外，
 * 避免 broker 抖动占住行锁与数据库连接池；leaseVersion 用于拒绝过期 worker 的回写。
 */
@Service
@ConditionalOnProperty(name = "marketing.outbox.enabled", havingValue = "true")
public class EventOutboxPublisher {
    private static final Logger LOGGER = LoggerFactory.getLogger(EventOutboxPublisher.class);

    private final EventOutboxRepository outbox;
    private final KafkaTemplate<String, String> kafka;
    private final Clock clock;
    private final long sendTimeoutMillis;
    private final Duration leaseDuration;
    private final String workerId;
    private final Counter published;
    private final Counter failed;
    private final Counter deadLettered;
    private final int maxAttempts;
    private final ObjectMapper mapper;
    private final TransactionTemplate transactions;
    private final EventOutboxBackpressure backpressure;
    private final EventOutboxDepthRepository outboxDepth;

    public EventOutboxPublisher(EventOutboxRepository outbox, KafkaTemplate<String, String> kafka, Clock clock,
            MeterRegistry meters, ObjectMapper mapper, PlatformTransactionManager transactionManager,
            EventOutboxBackpressure backpressure, EventOutboxDepthRepository outboxDepth,
            @Value("${marketing.outbox.send-timeout-ms:10000}") long sendTimeoutMillis,
            @Value("${marketing.outbox.max-attempts:10}") int maxAttempts,
            @Value("${marketing.outbox.lease-ms:30000}") long leaseMillis,
            @Value("${marketing.outbox.worker-id:${HOSTNAME:local}}") String workerName) {
        if (sendTimeoutMillis < 100 || sendTimeoutMillis > 60_000 || maxAttempts < 1 || maxAttempts > 100
                || leaseMillis <= sendTimeoutMillis || leaseMillis > 300_000) {
            throw new IllegalArgumentException("outbox send or lease policy is invalid");
        }
        this.outbox = outbox;
        this.kafka = kafka;
        this.clock = clock;
        this.sendTimeoutMillis = sendTimeoutMillis;
        this.maxAttempts = maxAttempts;
        this.leaseDuration = Duration.ofMillis(leaseMillis);
        String prefix = workerName == null || workerName.isBlank() ? "event-outbox" : workerName.trim();
        this.workerId = prefix.substring(0, Math.min(prefix.length(), 80)) + ':' + UUID.randomUUID();
        this.mapper = mapper;
        this.backpressure = backpressure;
        this.outboxDepth = outboxDepth;
        this.transactions = new TransactionTemplate(transactionManager);
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.published = Counter.builder("marketing.outbox.published").tag("outbox", "events").register(meters);
        this.failed = Counter.builder("marketing.outbox.failed").tag("outbox", "events").register(meters);
        this.deadLettered = Counter.builder("marketing.outbox.dead_lettered")
                .tag("outbox", "events").register(meters);
    }

    public PublishResult publishOne() {
        BatchResult result = publishBatch(1);
        if (result.published() > 0) return PublishResult.PUBLISHED;
        if (result.failed() > 0 || result.deadLettered() > 0) return PublishResult.FAILED;
        return PublishResult.EMPTY;
    }

    /** claim 已在进入本方法的 Kafka 阶段前提交，批量等待不会持有任何数据库事务。 */
    public BatchResult publishBatch(int limit) {
        if (limit < 1 || limit > 2_000) throw new IllegalArgumentException("outbox batch limit is invalid");
        List<PendingEvent> rows = transactions.execute(status -> claimBatch(limit));
        if (rows == null || rows.isEmpty()) return new BatchResult(0, 0, 0);

        List<PendingSend> sends = new ArrayList<>(rows.size());
        for (PendingEvent event : rows) {
            CompletableFuture<SendResult<String, String>> future;
            try {
                future = kafka.send(event.topic(), event.partitionKey(), event.payload());
                if (future == null) {
                    future = CompletableFuture.failedFuture(
                            new IllegalStateException("Kafka producer returned no send future"));
                }
            } catch (RuntimeException synchronousFailure) {
                // 序列化或 producer 本地状态错误可能在 send() 内同步抛出，也必须释放租约并计入重试。
                future = CompletableFuture.failedFuture(synchronousFailure);
            }
            sends.add(new PendingSend(event, future));
        }
        awaitBounded(sends);

        int publishedCount = 0;
        int failedCount = 0;
        int deadLetterCount = 0;
        List<DeadLetter> deadLetters = new ArrayList<>();
        for (PendingSend send : sends) {
            if (completedSuccessfully(send.future())) {
                Boolean applied = transactions.execute(status -> markPublished(send.event()));
                if (Boolean.TRUE.equals(applied)) {
                    published.increment();
                    publishedCount++;
                }
                continue;
            }
            Throwable failure = send.future().handle((result, error) -> error).getNow(null);
            if (failure == null) failure = new java.util.concurrent.TimeoutException("Kafka send timed out");
            Throwable classifiedFailure = failure;
            FailureResult result = transactions.execute(status -> recordFailure(send.event(), classifiedFailure));
            if (result == FailureResult.DEAD) {
                deadLetterCount++;
                deadLetters.add(new DeadLetter(send.event(), failure));
            } else if (result == FailureResult.RETRY) {
                failedCount++;
            }
        }
        deadLetters.forEach(this::sendDeadLetterBestEffort);
        if (publishedCount + failedCount + deadLetterCount > 0) backpressure.invalidate();
        return new BatchResult(publishedCount, failedCount, deadLetterCount);
    }

    private List<PendingEvent> claimBatch(int limit) {
        Instant now = clock.instant();
        List<PendingEvent> candidates = outbox.findClaimCandidates(now, limit);
        if (candidates.isEmpty()) return List.of();
        Instant leaseUntil = now.plus(leaseDuration);
        List<PendingEvent> claimed = new ArrayList<>(candidates.size());
        for (PendingEvent event : candidates) {
            if (outbox.claim(event, workerId, now, leaseUntil)) claimed.add(event);
        }
        return List.copyOf(claimed);
    }

    private void awaitBounded(List<PendingSend> sends) {
        try {
            CompletableFuture.allOf(sends.stream().map(PendingSend::future)
                    .toArray(CompletableFuture[]::new)).get(sendTimeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException ignored) {
            // 每个 future 在事务外单独分类，已成功的发送可以独立推进。
        }
    }

    private static boolean completedSuccessfully(CompletableFuture<SendResult<String, String>> future) {
        return future.isDone() && !future.isCompletedExceptionally() && !future.isCancelled();
    }

    private boolean markPublished(PendingEvent event) {
        Instant now = clock.instant();
        boolean applied = outbox.markPublished(event, workerId, now);
        if (applied) outboxDepth.decrement(event.tenantId(), event.depthBucketId(), now);
        return applied;
    }

    private FailureResult recordFailure(PendingEvent event, Throwable failure) {
        Instant now = clock.instant();
        int attempts = event.attempts() + 1;
        String message = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
        if (message.length() > 1_000) message = message.substring(0, 1_000);
        if (attempts >= maxAttempts) {
            if (!outbox.markDead(event, workerId, attempts, now, message)) return FailureResult.STALE;
            outboxDepth.decrement(event.tenantId(), event.depthBucketId(), now);
            failed.increment();
            deadLettered.increment();
            return FailureResult.DEAD;
        }
        long delaySeconds = Math.min(60, 1L << Math.min(6, attempts - 1));
        if (!outbox.markRetry(event, workerId, attempts, now.plusSeconds(delaySeconds), message)) {
            return FailureResult.STALE;
        }
        failed.increment();
        return FailureResult.RETRY;
    }

    /** DLQ 通知不参与状态事务；数据库 DEAD 行始终保留为可靠的重放与审计来源。 */
    private void sendDeadLetterBestEffort(DeadLetter deadLetter) {
        PendingEvent event = deadLetter.event();
        String reason = deadLetter.failure().getMessage() == null
                ? deadLetter.failure().getClass().getSimpleName() : deadLetter.failure().getMessage();
        try {
            kafka.send(event.topic() + ".dlq", event.partitionKey(),
                    deadLetterPayload(event, reason, event.attempts() + 1));
        } catch (RuntimeException failure) {
            LOGGER.warn("failed to emit event outbox DLQ notification for {}; DEAD row remains authoritative",
                    event.outboxId(), failure);
        }
    }

    private String deadLetterPayload(PendingEvent event, String reason, int attempts) {
        String safeReason = reason.length() <= 1_000 ? reason : reason.substring(0, 1_000);
        try {
            return mapper.writeValueAsString(Map.of("eventType", "EVENT_OUTBOX_DEAD_LETTERED",
                    "tenantId", event.tenantId(), "outboxId", event.outboxId(), "topic", event.topic(),
                    "partitionKey", event.partitionKey(), "attempts", attempts, "reason", safeReason));
        } catch (JacksonException impossible) {
            throw new IllegalStateException("dead-letter metadata cannot be serialized", impossible);
        }
    }

    public enum PublishResult { PUBLISHED, FAILED, EMPTY }
    public record BatchResult(int published, int failed, int deadLettered) { }

    private enum FailureResult { RETRY, DEAD, STALE }
    private record PendingSend(PendingEvent event, CompletableFuture<SendResult<String, String>> future) { }
    private record DeadLetter(PendingEvent event, Throwable failure) { }
}
