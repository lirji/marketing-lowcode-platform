package com.acme.marketing.journeyservice.infrastructure;

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
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
@ConditionalOnProperty(name = "marketing.journey.dispatch-enabled", havingValue = "true")
public class JourneyDispatchPublisher {
    private final JdbcTemplate jdbc;
    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final long timeoutMillis;
    private final int maxAttempts;
    private final Counter published;
    private final Counter failed;
    private final Counter deadLettered;

    public JourneyDispatchPublisher(JdbcTemplate jdbc, KafkaTemplate<String, String> kafka, ObjectMapper mapper,
            Clock clock, MeterRegistry meters,
            @Value("${marketing.journey.dispatch-timeout-ms:10000}") long timeoutMillis,
            @Value("${marketing.journey.dispatch-max-attempts:10}") int maxAttempts) {
        if (timeoutMillis < 100 || timeoutMillis > 60_000 || maxAttempts < 1 || maxAttempts > 100) {
            throw new IllegalArgumentException("journey dispatch retry policy is invalid");
        }
        this.jdbc = jdbc;
        this.kafka = kafka;
        this.mapper = mapper;
        this.clock = clock;
        this.timeoutMillis = timeoutMillis;
        this.maxAttempts = maxAttempts;
        this.published = Counter.builder("marketing.outbox.published").tag("outbox", "journey-dispatch")
                .register(meters);
        this.failed = Counter.builder("marketing.outbox.failed").tag("outbox", "journey-dispatch")
                .register(meters);
        this.deadLettered = Counter.builder("marketing.outbox.dead_lettered")
                .tag("outbox", "journey-dispatch").register(meters);
    }

    @Transactional
    public BatchResult publishBatch(int limit) {
        if (limit < 1 || limit > 1_000) throw new IllegalArgumentException("journey dispatch limit is invalid");
        Instant now = clock.instant();
        List<Pending> rows = jdbc.query("select candidate.tenant_id,candidate.outbox_id,candidate.command_id,candidate.destination_topic,candidate.partition_key,candidate.payload_json,candidate.publish_attempts from mk_journey_dispatch_outbox candidate where candidate.published_at is null and candidate.dead_lettered_at is null and candidate.next_attempt_at<=? and not exists (select 1 from mk_journey_dispatch_outbox predecessor where predecessor.tenant_id=candidate.tenant_id and predecessor.enrollment_id=candidate.enrollment_id and predecessor.published_at is null and predecessor.dead_lettered_at is null and predecessor.stream_sequence<candidate.stream_sequence) order by candidate.created_at,candidate.stream_sequence limit ? for update skip locked",
                (rs, rowNum) -> new Pending(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getString(6), rs.getInt(7)),
                format(now), limit);
        if (rows.isEmpty()) return new BatchResult(0, 0, 0);
        List<Send> sends = new ArrayList<>(rows.size());
        rows.forEach(row -> sends.add(new Send(row,
                kafka.send(row.topic(), row.partitionKey(), row.payload()))));
        try {
            CompletableFuture.allOf(sends.stream().map(Send::future).toArray(CompletableFuture[]::new))
                    .get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException ignored) {
            // Results are classified independently below.
        }
        int publishedCount = 0;
        int failedCount = 0;
        int deadCount = 0;
        for (Send send : sends) {
            if (send.future().isDone() && !send.future().isCompletedExceptionally()
                    && !send.future().isCancelled()) {
                jdbc.update("update mk_journey_dispatch_outbox set published_at=?,publish_attempts=?,last_error='' where tenant_id=? and outbox_id=? and published_at is null and dead_lettered_at is null",
                        format(now), send.row().attempts() + 1, send.row().tenantId(), send.row().outboxId());
                jdbc.update("update mk_node_effect_intent set state_name='DISPATCHED' where tenant_id=? and command_id=?",
                        send.row().tenantId(), send.row().commandId());
                published.increment();
                publishedCount++;
            } else {
                Throwable cause = send.future().handle((result, error) -> error).getNow(null);
                if (cause == null) cause = new java.util.concurrent.TimeoutException("Kafka send timed out");
                if (recordFailure(send.row(), cause, now)) deadCount++;
                else failedCount++;
            }
        }
        return new BatchResult(publishedCount, failedCount, deadCount);
    }

    private boolean recordFailure(Pending row, Throwable failure, Instant now) {
        int attempts = row.attempts() + 1;
        String message = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
        if (message.length() > 1_000) message = message.substring(0, 1_000);
        failed.increment();
        if (attempts >= maxAttempts) {
            jdbc.update("update mk_journey_dispatch_outbox set publish_attempts=?,dead_lettered_at=?,last_error=? where tenant_id=? and outbox_id=? and published_at is null and dead_lettered_at is null",
                    attempts, format(now), message, row.tenantId(), row.outboxId());
            jdbc.update("update mk_node_effect_intent set state_name='DISPATCH_FAILED' where tenant_id=? and command_id=?",
                    row.tenantId(), row.commandId());
            kafka.send(row.topic() + ".dlq", row.partitionKey(), deadLetter(row, message, attempts));
            deadLettered.increment();
            return true;
        }
        long delay = Math.min(60, 1L << Math.min(6, attempts - 1));
        jdbc.update("update mk_journey_dispatch_outbox set publish_attempts=?,next_attempt_at=?,last_error=? where tenant_id=? and outbox_id=? and published_at is null",
                attempts, format(now.plusSeconds(delay)), message, row.tenantId(), row.outboxId());
        return false;
    }

    private String deadLetter(Pending row, String reason, int attempts) {
        try {
            return mapper.writeValueAsString(Map.of("eventType", "JOURNEY_DISPATCH_DEAD_LETTERED",
                    "tenantId", row.tenantId(), "outboxId", row.outboxId(), "commandId", row.commandId(),
                    "destinationTopic", row.topic(), "attempts", attempts, "reason", reason));
        } catch (JacksonException failure) {
            throw new IllegalStateException("journey dispatch DLQ metadata cannot be serialized", failure);
        }
    }

    public record BatchResult(int published, int failed, int deadLettered) { }
    private record Pending(String tenantId, String outboxId, String commandId, String topic,
            String partitionKey, String payload, int attempts) { }
    private record Send(Pending row, CompletableFuture<SendResult<String, String>> future) { }
}
