package com.acme.marketing.control.infrastructure;

import static com.acme.marketing.platform.time.SqlTime.format;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Durable low-volume coordinator relay for signed runtime activation directives. */
@Service
@ConditionalOnProperty(name = "marketing.release-outbox.enabled", havingValue = "true")
public class ReleaseOutboxPublisher {
    private final JdbcTemplate jdbc;
    private final KafkaTemplate<String, String> kafka;
    private final Clock clock;
    private final long timeoutMillis;

    public ReleaseOutboxPublisher(JdbcTemplate jdbc, KafkaTemplate<String, String> kafka, Clock clock,
            @Value("${marketing.release-outbox.send-timeout-ms:10000}") long timeoutMillis) {
        if (timeoutMillis < 100 || timeoutMillis > 60_000) {
            throw new IllegalArgumentException("release outbox send timeout is invalid");
        }
        this.jdbc = jdbc;
        this.kafka = kafka;
        this.clock = clock;
        this.timeoutMillis = timeoutMillis;
    }

    @Transactional
    public Result publishOne() {
        Instant now = clock.instant();
        List<Pending> rows = jdbc.query("select candidate.tenant_id,candidate.event_id,candidate.destination_topic,candidate.partition_key,candidate.payload_json,candidate.publish_attempts from mk_outbox candidate where candidate.published_at is null and candidate.next_attempt_at<=? and not exists (select 1 from mk_outbox predecessor where predecessor.tenant_id=candidate.tenant_id and predecessor.partition_key=candidate.partition_key and predecessor.published_at is null and predecessor.stream_sequence<candidate.stream_sequence) order by candidate.occurred_at,candidate.stream_sequence limit 1 for update skip locked",
                (rs, rowNum) -> new Pending(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getInt(6)), format(now));
        if (rows.isEmpty()) return Result.EMPTY;
        Pending event = rows.getFirst();
        try {
            kafka.send(event.topic(), event.partitionKey(), event.payload()).get(timeoutMillis, TimeUnit.MILLISECONDS);
            jdbc.update("update mk_outbox set published_at=?,publish_attempts=?,last_error='' where tenant_id=? and event_id=? and published_at is null",
                    format(now), event.attempts() + 1, event.tenantId(), event.eventId());
            return Result.PUBLISHED;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            fail(event, interrupted, now);
            return Result.FAILED;
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException failure) {
            fail(event, failure, now);
            return Result.FAILED;
        }
    }

    private void fail(Pending event, Exception failure, Instant now) {
        int attempts = event.attempts() + 1;
        long delay = Math.min(300, 1L << Math.min(8, attempts - 1));
        String error = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
        if (error.length() > 1_000) error = error.substring(0, 1_000);
        jdbc.update("update mk_outbox set publish_attempts=?,next_attempt_at=?,last_error=? where tenant_id=? and event_id=? and published_at is null",
                attempts, format(now.plusSeconds(delay)), error, event.tenantId(), event.eventId());
    }

    public enum Result { PUBLISHED, FAILED, EMPTY }
    private record Pending(String tenantId, String eventId, String topic, String partitionKey,
            String payload, int attempts) { }
}
