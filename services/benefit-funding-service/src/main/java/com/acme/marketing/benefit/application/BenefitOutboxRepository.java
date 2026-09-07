package com.acme.marketing.benefit.application;

import java.util.List;

/** 权益业务事件 Outbox 的发布持久化端口。 */
public interface BenefitOutboxRepository {
    List<PendingEvent> lockPublishable(String now, int limit);
    int markPublished(String tenantId, String eventId, String publishedAt, int attempts);
    int markDead(String tenantId, String eventId, String deadAt, int attempts, String error);
    int markRetry(String tenantId, String eventId, String nextAttemptAt, int attempts, String error);

    record PendingEvent(String tenantId, String eventId, String topic, String key, String payload,
            int attempts) { }
}
