package com.acme.marketing.eventgateway.infrastructure;

import static com.acme.marketing.platform.time.SqlTime.format;

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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.kafka.support.SendResult;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
@ConditionalOnProperty(name = "marketing.outbox.enabled", havingValue = "true")
public class EventOutboxPublisher {
    private final JdbcTemplate jdbc;
    private final KafkaTemplate<String, String> kafka;
    private final Clock clock;
    private final long sendTimeoutMillis;
    private final Counter published;
    private final Counter failed;
    private final Counter deadLettered;
    private final int maxAttempts;
    private final ObjectMapper mapper;

    public EventOutboxPublisher(JdbcTemplate jdbc, KafkaTemplate<String, String> kafka, Clock clock,
            MeterRegistry meters, ObjectMapper mapper,
            @Value("${marketing.outbox.send-timeout-ms:10000}") long sendTimeoutMillis,
            @Value("${marketing.outbox.max-attempts:10}") int maxAttempts) {
        if (sendTimeoutMillis < 100 || sendTimeoutMillis > 60_000 || maxAttempts < 1 || maxAttempts > 100) {
            throw new IllegalArgumentException("outbox send timeout is invalid");
        }
        this.jdbc = jdbc;
        this.kafka = kafka;
        this.clock = clock;
        this.sendTimeoutMillis = sendTimeoutMillis;
        this.maxAttempts = maxAttempts;
        this.mapper = mapper;
        this.published = Counter.builder("marketing.outbox.published").tag("outbox", "events").register(meters);
        this.failed = Counter.builder("marketing.outbox.failed").tag("outbox", "events").register(meters);
        this.deadLettered = Counter.builder("marketing.outbox.dead_lettered").tag("outbox", "events").register(meters);
    }

    @Transactional
    public PublishResult publishOne() {
        BatchResult result = publishBatch(1);
        if (result.published() > 0) return PublishResult.PUBLISHED;
        if (result.failed() > 0 || result.deadLettered() > 0) return PublishResult.FAILED;
        return PublishResult.EMPTY;
    }

    @Transactional
    public BatchResult publishBatch(int limit) {
        if (limit < 1 || limit > 2_000) throw new IllegalArgumentException("outbox batch limit is invalid");
        Instant now = clock.instant();
        List<PendingEvent> rows = jdbc.query("select candidate.tenant_id,candidate.outbox_id,candidate.destination_topic,candidate.partition_key,candidate.payload_json,candidate.publish_attempts from mk_event_outbox candidate where candidate.published_at is null and candidate.dead_lettered_at is null and candidate.next_attempt_at<=? and not exists (select 1 from mk_event_outbox predecessor where predecessor.tenant_id=candidate.tenant_id and predecessor.destination_topic=candidate.destination_topic and predecessor.partition_key=candidate.partition_key and predecessor.published_at is null and predecessor.dead_lettered_at is null and predecessor.stream_sequence<candidate.stream_sequence) order by candidate.created_at,candidate.stream_sequence limit ? for update skip locked",
                (rs, rowNum) -> new PendingEvent(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getInt(6)), format(now), limit);
        if (rows.isEmpty()) return new BatchResult(0, 0, 0);
        List<PendingSend> sends = new ArrayList<>(rows.size());
        for (PendingEvent event : rows) {
            sends.add(new PendingSend(event, kafka.send(event.topic(), event.partitionKey(), event.payload())));
        }
        try {
            CompletableFuture.allOf(sends.stream().map(PendingSend::future)
                    .toArray(CompletableFuture[]::new)).get(sendTimeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException ignored) {
            // Each future is classified below so successful sends can advance independently.
        }
        int publishedCount = 0;
        int failedCount = 0;
        int deadLetterCount = 0;
        for (PendingSend send : sends) {
            if (send.future().isDone() && !send.future().isCompletedExceptionally()
                    && !send.future().isCancelled()) {
                jdbc.update("update mk_event_outbox set published_at=?,publish_attempts=?,last_error='' where tenant_id=? and outbox_id=? and published_at is null and dead_lettered_at is null",
                        format(now), send.event().attempts() + 1, send.event().tenantId(), send.event().outboxId());
                published.increment();
                publishedCount++;
            } else {
                Throwable failure = send.future().handle((result, error) -> error).getNow(null);
                if (failure == null) failure = new java.util.concurrent.TimeoutException("Kafka send timed out");
                if (recordFailure(send.event(), failure, now)) deadLetterCount++;
                else failedCount++;
            }
        }
        return new BatchResult(publishedCount, failedCount, deadLetterCount);
    }

    private boolean recordFailure(PendingEvent event, Throwable failure, Instant now) {
        int attempts = event.attempts() + 1;
        String message = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
        if (message.length() > 1_000) message = message.substring(0, 1_000);
        if (attempts >= maxAttempts) {
            jdbc.update("update mk_event_outbox set publish_attempts=?,dead_lettered_at=?,last_error=? where tenant_id=? and outbox_id=? and published_at is null and dead_lettered_at is null",
                    attempts, format(now), message, event.tenantId(), event.outboxId());
            kafka.send(event.topic() + ".dlq", event.partitionKey(), deadLetterPayload(event, message, attempts));
            failed.increment();
            deadLettered.increment();
            return true;
        }
        long delaySeconds = Math.min(60, 1L << Math.min(6, attempts - 1));
        jdbc.update("update mk_event_outbox set publish_attempts=?,next_attempt_at=?,last_error=? where tenant_id=? and outbox_id=? and published_at is null",
                attempts, format(now.plusSeconds(delaySeconds)), message, event.tenantId(), event.outboxId());
        failed.increment();
        return false;
    }

    private String deadLetterPayload(PendingEvent event, String reason, int attempts) {
        try {
            return mapper.writeValueAsString(Map.of("eventType", "EVENT_OUTBOX_DEAD_LETTERED",
                    "tenantId", event.tenantId(), "outboxId", event.outboxId(), "topic", event.topic(),
                    "partitionKey", event.partitionKey(), "attempts", attempts, "reason", reason));
        } catch (JacksonException impossible) {
            throw new IllegalStateException("dead-letter metadata cannot be serialized", impossible);
        }
    }

    public enum PublishResult { PUBLISHED, FAILED, EMPTY }
    public record BatchResult(int published, int failed, int deadLettered) { }

    private record PendingEvent(String tenantId, String outboxId, String topic, String partitionKey,
            String payload, int attempts) { }
    private record PendingSend(PendingEvent event, CompletableFuture<SendResult<String, String>> future) { }
}
