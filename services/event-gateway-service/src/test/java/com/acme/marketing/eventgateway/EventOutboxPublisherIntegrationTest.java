package com.acme.marketing.eventgateway;

import static com.acme.marketing.platform.time.SqlTime.format;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.acme.marketing.eventgateway.infrastructure.EventOutboxPublisher;
import com.acme.marketing.eventgateway.application.EventOutboxDepthRepository;
import com.acme.marketing.testsupport.MySqlIntegrationTest;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(properties = {
        "marketing.outbox.enabled=true",
        "marketing.outbox.fixed-delay-ms=3600000",
        "marketing.outbox.send-timeout-ms=2000",
        "marketing.outbox.lease-ms=5000",
        "marketing.outbox.max-attempts=2"
})
class EventOutboxPublisherIntegrationTest extends MySqlIntegrationTest {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EventOutboxPublisher publisher;
    @Autowired private EventOutboxDepthRepository outboxDepth;
    @MockitoBean private KafkaTemplate<String, String> kafka;

    @BeforeEach
    void clearOutbox() {
        jdbc.update("delete from mk_event_outbox");
        jdbc.update("delete from mk_event_outbox_depth");
    }

    @Test
    void kafkaWaitDoesNotHoldTheClaimTransactionOrRowLock() throws Exception {
        String outboxId = insertPending("transaction-boundary");
        CompletableFuture<SendResult<String, String>> kafkaResult = new CompletableFuture<>();
        when(kafka.send(anyString(), anyString(), anyString())).thenReturn(kafkaResult);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        CompletableFuture<EventOutboxPublisher.BatchResult> publishing;
        try {
            publishing = CompletableFuture.supplyAsync(() -> publisher.publishBatch(1), executor);
            awaitState(outboxId, "SENDING");
            long started = System.nanoTime();
            assertEquals(1, jdbc.update(
                    "update mk_event_outbox set last_error='lock-probe' where tenant_id=? and outbox_id=?",
                    "tenant-publisher", outboxId));
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            assertTrue(elapsedMillis < 500,
                    "Kafka wait must not retain the row lock; update took " + elapsedMillis + "ms");
            kafkaResult.completeExceptionally(new IllegalStateException("broker unavailable"));
            assertEquals(1, publishing.get(3, TimeUnit.SECONDS).failed());
        } finally {
            executor.shutdownNow();
        }
        assertEquals("PENDING", state(outboxId));
        assertEquals(1, attempts(outboxId));
        assertEquals(1L, outboxDepth.globalPending());
        assertEquals(1L, outboxDepth.tenantPending("tenant-publisher"));
    }

    @Test
    void expiredLeaseIsFencedAndPermanentFailureRemainsDurable() {
        String outboxId = insertPending("lease-recovery");
        jdbc.update("update mk_event_outbox set publish_state='SENDING',lease_owner='dead-worker',lease_until=?,lease_version=7 where tenant_id=? and outbox_id=?",
                format(Instant.now().minusSeconds(10)), "tenant-publisher", outboxId);
        CompletableFuture<SendResult<String, String>> failure =
                CompletableFuture.failedFuture(new IllegalStateException("broker unavailable"));
        when(kafka.send(anyString(), anyString(), anyString())).thenReturn(failure);

        EventOutboxPublisher.BatchResult first = publisher.publishBatch(1);
        assertEquals(1, first.failed());
        assertEquals("PENDING", state(outboxId));
        assertEquals(8L, leaseVersion(outboxId));
        jdbc.update("update mk_event_outbox set next_attempt_at=? where tenant_id=? and outbox_id=?",
                format(Instant.now().minusSeconds(1)), "tenant-publisher", outboxId);

        EventOutboxPublisher.BatchResult second = publisher.publishBatch(1);
        assertEquals(1, second.deadLettered());
        assertEquals("DEAD", state(outboxId));
        assertEquals(2, attempts(outboxId));
        assertEquals(0L, outboxDepth.globalPending());
        assertEquals(0L, outboxDepth.tenantPending("tenant-publisher"));
    }

    @Test
    void synchronousKafkaFailureReleasesLeaseForRetry() {
        String outboxId = insertPending("synchronous-failure");
        when(kafka.send(anyString(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("producer is closed"));

        EventOutboxPublisher.BatchResult result = publisher.publishBatch(1);

        assertEquals(1, result.failed());
        assertEquals("PENDING", state(outboxId));
        assertEquals(1, attempts(outboxId));
        assertEquals(1L, outboxDepth.globalPending());
        assertEquals(1L, outboxDepth.tenantPending("tenant-publisher"));
    }

    @Test
    void publishedStateAndDepthCountersCommitTogether() {
        String outboxId = insertPending("published-depth");
        when(kafka.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));

        EventOutboxPublisher.BatchResult result = publisher.publishBatch(1);

        assertEquals(1, result.published());
        assertEquals("PUBLISHED", state(outboxId));
        assertEquals(0L, outboxDepth.globalPending());
        assertEquals(0L, outboxDepth.tenantPending("tenant-publisher"));
    }

    @Test
    void existingDepthBucketCanBeIncrementedAgain() {
        Instant now = Instant.now();

        outboxDepth.increment("tenant-publisher", 3, now);
        outboxDepth.increment("tenant-publisher", 3, now.plusMillis(1));

        assertEquals(2L, outboxDepth.globalPending());
        assertEquals(2L, outboxDepth.tenantPending("tenant-publisher"));
        outboxDepth.decrement("tenant-publisher", 3, now.plusMillis(2));
        outboxDepth.decrement("tenant-publisher", 3, now.plusMillis(3));
        assertEquals(0L, outboxDepth.globalPending());
        assertEquals(0L, outboxDepth.tenantPending("tenant-publisher"));
    }

    private String insertPending(String suffix) {
        String outboxId = UUID.randomUUID().toString();
        String now = format(Instant.now());
        int depthBucketId = outboxDepth.bucketId(outboxId);
        jdbc.update("insert into mk_event_outbox(tenant_id,outbox_id,receipt_id,event_type,destination_topic,partition_key,stream_sequence,payload_json,created_at,next_attempt_at,depth_bucket_id) values(?,?,?,?,?,?,?,?,?,?,?)",
                "tenant-publisher", outboxId, UUID.randomUUID().toString(), "TEST_EVENT", "mk.test.v1",
                "tenant-publisher:" + suffix, 1L, "{}", now, now, depthBucketId);
        outboxDepth.increment("tenant-publisher", depthBucketId, Instant.now());
        return outboxId;
    }

    private void awaitState(String outboxId, String expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            if (expected.equals(state(outboxId))) return;
            Thread.sleep(10);
        }
        assertEquals(expected, state(outboxId));
    }

    private String state(String outboxId) {
        return jdbc.queryForObject(
                "select publish_state from mk_event_outbox where tenant_id=? and outbox_id=?",
                String.class, "tenant-publisher", outboxId);
    }

    private int attempts(String outboxId) {
        Integer attempts = jdbc.queryForObject(
                "select publish_attempts from mk_event_outbox where tenant_id=? and outbox_id=?",
                Integer.class, "tenant-publisher", outboxId);
        return attempts == null ? -1 : attempts;
    }

    private long leaseVersion(String outboxId) {
        Long version = jdbc.queryForObject(
                "select lease_version from mk_event_outbox where tenant_id=? and outbox_id=?",
                Long.class, "tenant-publisher", outboxId);
        return version == null ? -1 : version;
    }
}
