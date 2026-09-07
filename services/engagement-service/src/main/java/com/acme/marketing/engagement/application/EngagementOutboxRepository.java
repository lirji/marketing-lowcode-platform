package com.acme.marketing.engagement.application;

import java.time.Instant;
import java.util.List;

/** 互动 outbox 发布器使用的持久化端口。 */
public interface EngagementOutboxRepository {

    /** 加锁读取当前可发布且不存在未完成前序事件的行。 */
    List<PendingEvent> findPending(Instant now, int limit);

    /** 标记事件发布成功。 */
    void markPublished(PendingEvent event, int attempts, Instant publishedAt);

    /** 标记事件永久失败。 */
    void markDead(PendingEvent event, int attempts, Instant deadAt, String error);

    /** 记录失败并安排下次重试。 */
    void markRetry(PendingEvent event, int attempts, Instant nextAttemptAt, String error);

    /** 待发布互动事件。 */
    record PendingEvent(String tenantId, String eventId, String topic, String key,
            String payload, int attempts) { }
}
