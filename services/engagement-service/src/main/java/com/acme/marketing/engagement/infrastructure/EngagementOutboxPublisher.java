package com.acme.marketing.engagement.infrastructure;

import com.acme.marketing.engagement.application.EngagementOutboxRepository;
import com.acme.marketing.engagement.application.EngagementOutboxRepository.PendingEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
@ConditionalOnProperty(name = "marketing.engagement.outbox-enabled", havingValue = "true")
public class EngagementOutboxPublisher {
    private final EngagementOutboxRepository outbox;
    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final int maxAttempts;
    private final long timeoutMillis;
    private final Counter published;
    private final Counter failed;
    private final Counter dead;

    public EngagementOutboxPublisher(EngagementOutboxRepository outbox, KafkaTemplate<String, String> kafka,
            ObjectMapper mapper,
            Clock clock, MeterRegistry meters,
            @Value("${marketing.engagement.outbox-max-attempts:10}") int maxAttempts,
            @Value("${marketing.engagement.outbox-timeout-ms:10000}") long timeoutMillis) {
        if (maxAttempts < 1 || maxAttempts > 100 || timeoutMillis < 100 || timeoutMillis > 60_000) {
            throw new IllegalArgumentException("engagement outbox retry policy is invalid");
        }
        this.outbox = outbox;
        this.kafka = kafka;
        this.mapper = mapper;
        this.clock = clock;
        this.maxAttempts = maxAttempts;
        this.timeoutMillis = timeoutMillis;
        this.published = Counter.builder("marketing.outbox.published").tag("outbox", "engagement")
                .register(meters);
        this.failed = Counter.builder("marketing.outbox.failed").tag("outbox", "engagement").register(meters);
        this.dead = Counter.builder("marketing.outbox.dead_lettered").tag("outbox", "engagement").register(meters);
    }

    @Transactional
    public Result publishBatch(int limit) {
        if (limit < 1 || limit > 1_000) throw new IllegalArgumentException("engagement outbox limit is invalid");
        Instant now = clock.instant();
        List<PendingEvent> rows = outbox.findPending(now, limit);
        if (rows.isEmpty()) return new Result(0, 0, 0);
        List<Send> sends = new ArrayList<>(rows.size());
        rows.forEach(row -> sends.add(new Send(row, kafka.send(row.topic(), row.key(), row.payload()))));
        try {
            CompletableFuture.allOf(sends.stream().map(Send::future).toArray(CompletableFuture[]::new))
                    .get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException ignored) {
            // Every future is classified below.
        }
        int success = 0;
        int retry = 0;
        int deadCount = 0;
        for (Send send : sends) {
            if (send.future().isDone() && !send.future().isCompletedExceptionally()
                    && !send.future().isCancelled()) {
                outbox.markPublished(send.row(), send.row().attempts() + 1, now);
                published.increment();
                success++;
            } else {
                Throwable cause = send.future().handle((result, error) -> error).getNow(null);
                if (cause == null) cause = new java.util.concurrent.TimeoutException("Kafka send timed out");
                if (recordFailure(send.row(), cause, now)) deadCount++;
                else retry++;
            }
        }
        return new Result(success, retry, deadCount);
    }

    private boolean recordFailure(PendingEvent row, Throwable cause, Instant now) {
        int attempts = row.attempts() + 1;
        String reason = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
        if (reason.length() > 1_000) reason = reason.substring(0, 1_000);
        failed.increment();
        if (attempts >= maxAttempts) {
            outbox.markDead(row, attempts, now, reason);
            kafka.send(row.topic() + ".dlq", row.key(), deadLetter(row, reason, attempts));
            dead.increment();
            return true;
        }
        long delay = Math.min(60, 1L << Math.min(6, attempts - 1));
        outbox.markRetry(row, attempts, now.plusSeconds(delay), reason);
        return false;
    }

    private String deadLetter(PendingEvent row, String reason, int attempts) {
        try {
            return mapper.writeValueAsString(Map.of("eventType", "ENGAGEMENT_OUTBOX_DEAD_LETTERED",
                    "tenantId", row.tenantId(), "eventId", row.eventId(), "attempts", attempts,
                    "reason", reason));
        } catch (JacksonException failure) {
            throw new IllegalStateException("engagement DLQ metadata cannot be serialized", failure);
        }
    }

    public record Result(int published, int failed, int deadLettered) { }
    private record Send(PendingEvent row, CompletableFuture<SendResult<String, String>> future) { }
}
