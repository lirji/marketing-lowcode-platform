package com.acme.marketing.control.application;

import java.util.Optional;

/** 发布事件 outbox 的持久化端口。 */
public interface ReleaseOutboxRepository {
    Optional<PendingEvent> lockNext(String now);
    void markPublished(PendingEvent event, String publishedAt, int attempts);
    void markRetry(PendingEvent event, String nextAttemptAt, int attempts, String error);

    record PendingEvent(String tenantId, String eventId, String topic, String partitionKey,
            String payload, int attempts) { }
}
