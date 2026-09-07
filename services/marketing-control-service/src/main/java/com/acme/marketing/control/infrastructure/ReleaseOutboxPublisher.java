package com.acme.marketing.control.infrastructure;

import static com.acme.marketing.platform.time.SqlTime.format;

import com.acme.marketing.control.application.ReleaseOutboxRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 签名运行时激活指令的低吞吐、持久化协调器 relay。 */
@Service
@ConditionalOnProperty(name = "marketing.release-outbox.enabled", havingValue = "true")
public class ReleaseOutboxPublisher {
    private final ReleaseOutboxRepository repository;
    private final KafkaTemplate<String, String> kafka;
    private final Clock clock;
    private final long timeoutMillis;

    public ReleaseOutboxPublisher(ReleaseOutboxRepository repository, KafkaTemplate<String, String> kafka, Clock clock,
            @Value("${marketing.release-outbox.send-timeout-ms:10000}") long timeoutMillis) {
        if (timeoutMillis < 100 || timeoutMillis > 60_000) {
            throw new IllegalArgumentException("release outbox send timeout is invalid");
        }
        this.repository = repository;
        this.kafka = kafka;
        this.clock = clock;
        this.timeoutMillis = timeoutMillis;
    }

    @Transactional
    public Result publishOne() {
        Instant now = clock.instant();
        var pending = repository.lockNext(format(now));
        if (pending.isEmpty()) return Result.EMPTY;
        ReleaseOutboxRepository.PendingEvent event = pending.orElseThrow();
        try {
            kafka.send(event.topic(), event.partitionKey(), event.payload()).get(timeoutMillis, TimeUnit.MILLISECONDS);
            repository.markPublished(event, format(now), event.attempts() + 1);
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

    private void fail(ReleaseOutboxRepository.PendingEvent event, Exception failure, Instant now) {
        int attempts = event.attempts() + 1;
        long delay = Math.min(300, 1L << Math.min(8, attempts - 1));
        String error = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
        if (error.length() > 1_000) error = error.substring(0, 1_000);
        repository.markRetry(event, format(now.plusSeconds(delay)), attempts, error);
    }

    public enum Result { PUBLISHED, FAILED, EMPTY }
}
