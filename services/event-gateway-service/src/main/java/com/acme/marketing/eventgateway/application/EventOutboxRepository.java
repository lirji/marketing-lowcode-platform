package com.acme.marketing.eventgateway.application;

import java.time.Instant;
import java.util.List;

/** Event outbox 发布器使用的持久化端口。 */
public interface EventOutboxRepository {

    /** 加锁读取当前可领取且没有未完成前序事件的候选行。 */
    List<PendingEvent> findClaimCandidates(Instant now, int limit);

    /** 使用租约版本进行条件领取，失败表示候选已被其他 worker 抢占。 */
    boolean claim(PendingEvent event, String workerId, Instant now, Instant leaseUntil);

    /** 使用租约 fencing 条件标记发布成功。 */
    boolean markPublished(PendingEvent event, String workerId, Instant publishedAt);

    /** 使用租约 fencing 条件标记永久失败。 */
    boolean markDead(PendingEvent event, String workerId, int attempts, Instant deadLetteredAt, String error);

    /** 使用租约 fencing 条件释放租约并安排重试。 */
    boolean markRetry(PendingEvent event, String workerId, int attempts, Instant nextAttemptAt, String error);

    /** 查询所有未完成事件中最早的创建时间，用于计算积压年龄。 */
    Instant findOldestPendingCreatedAt();

    /** 已领取的 outbox 事件；leaseVersion 是本次待写入的新 fencing 世代。 */
    record PendingEvent(String tenantId, String outboxId, String topic, String partitionKey,
            String payload, int attempts, long leaseVersion, int depthBucketId) { }
}
