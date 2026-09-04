package com.acme.marketing.benefit.infrastructure;

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
@ConditionalOnProperty(name = "marketing.benefit.outbox-enabled", havingValue = "true")
public class BenefitOutboxPublisher {
    private final JdbcTemplate jdbc;
    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final int maxAttempts;
    private final long timeoutMillis;
    private final Counter published;
    private final Counter failed;
    private final Counter dead;

    public BenefitOutboxPublisher(JdbcTemplate jdbc, KafkaTemplate<String, String> kafka, ObjectMapper mapper,
            Clock clock, MeterRegistry meters,
            @Value("${marketing.benefit.outbox-max-attempts:10}") int maxAttempts,
            @Value("${marketing.benefit.outbox-timeout-ms:10000}") long timeoutMillis) {
        if (maxAttempts < 1 || maxAttempts > 100 || timeoutMillis < 100 || timeoutMillis > 60_000) {
            throw new IllegalArgumentException("benefit outbox retry policy is invalid");
        }
        this.jdbc = jdbc;
        this.kafka = kafka;
        this.mapper = mapper;
        this.clock = clock;
        this.maxAttempts = maxAttempts;
        this.timeoutMillis = timeoutMillis;
        this.published = Counter.builder("marketing.outbox.published").tag("outbox", "benefit").register(meters);
        this.failed = Counter.builder("marketing.outbox.failed").tag("outbox", "benefit").register(meters);
        this.dead = Counter.builder("marketing.outbox.dead_lettered").tag("outbox", "benefit").register(meters);
    }

    @Transactional
    public Result publishBatch(int limit) {
        if (limit < 1 || limit > 1_000) throw new IllegalArgumentException("benefit outbox limit is invalid");
        Instant now = clock.instant();
        List<Row> rows = jdbc.query("select candidate.tenant_id,candidate.event_id,candidate.destination_topic,candidate.partition_key,candidate.payload_json,candidate.publish_attempts from mk_benefit_outbox candidate where candidate.published_at is null and candidate.dead_lettered_at is null and candidate.next_attempt_at<=? and not exists (select 1 from mk_benefit_outbox predecessor where predecessor.tenant_id=candidate.tenant_id and predecessor.aggregate_id=candidate.aggregate_id and predecessor.published_at is null and predecessor.dead_lettered_at is null and predecessor.stream_sequence<candidate.stream_sequence) order by candidate.created_at,candidate.stream_sequence limit ? for update skip locked",
                (rs, rowNum) -> new Row(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getInt(6)), format(now), limit);
        if (rows.isEmpty()) return new Result(0, 0, 0);
        List<Send> sends = new ArrayList<>(rows.size());
        rows.forEach(row -> sends.add(new Send(row, kafka.send(row.topic(), row.key(), row.payload()))));
        try {
            CompletableFuture.allOf(sends.stream().map(Send::future).toArray(CompletableFuture[]::new))
                    .get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException ignored) {
            // Individual results are committed independently below.
        }
        int success = 0;
        int retry = 0;
        int deadCount = 0;
        for (Send send : sends) {
            if (send.future().isDone() && !send.future().isCompletedExceptionally()
                    && !send.future().isCancelled()) {
                jdbc.update("update mk_benefit_outbox set published_at=?,publish_attempts=?,last_error='' where tenant_id=? and event_id=? and published_at is null and dead_lettered_at is null",
                        format(now), send.row().attempts() + 1, send.row().tenantId(), send.row().eventId());
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

    private boolean recordFailure(Row row, Throwable cause, Instant now) {
        int attempts = row.attempts() + 1;
        String reason = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
        if (reason.length() > 1_000) reason = reason.substring(0, 1_000);
        failed.increment();
        if (attempts >= maxAttempts) {
            jdbc.update("update mk_benefit_outbox set publish_attempts=?,dead_lettered_at=?,last_error=? where tenant_id=? and event_id=? and published_at is null and dead_lettered_at is null",
                    attempts, format(now), reason, row.tenantId(), row.eventId());
            kafka.send(row.topic() + ".dlq", row.key(), deadLetter(row, reason, attempts));
            dead.increment();
            return true;
        }
        long delay = Math.min(60, 1L << Math.min(6, attempts - 1));
        jdbc.update("update mk_benefit_outbox set publish_attempts=?,next_attempt_at=?,last_error=? where tenant_id=? and event_id=? and published_at is null",
                attempts, format(now.plusSeconds(delay)), reason, row.tenantId(), row.eventId());
        return false;
    }

    private String deadLetter(Row row, String reason, int attempts) {
        try {
            return mapper.writeValueAsString(Map.of("eventType", "BENEFIT_OUTBOX_DEAD_LETTERED",
                    "tenantId", row.tenantId(), "eventId", row.eventId(), "attempts", attempts,
                    "reason", reason));
        } catch (JacksonException failure) {
            throw new IllegalStateException("benefit DLQ metadata cannot be serialized", failure);
        }
    }

    public record Result(int published, int failed, int deadLettered) { }
    private record Row(String tenantId, String eventId, String topic, String key, String payload, int attempts) { }
    private record Send(Row row, CompletableFuture<SendResult<String, String>> future) { }
}
