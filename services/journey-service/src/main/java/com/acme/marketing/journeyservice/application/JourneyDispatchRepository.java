package com.acme.marketing.journeyservice.application;

import java.util.List;

/** 旅程副作用 dispatch outbox 的持久化端口。 */
public interface JourneyDispatchRepository {

    /** 锁定一批符合聚合顺序约束的待发送记录。 */
    List<PendingDispatch> lockPending(String now, int limit);

    /** 标记发送成功，并同步推进 effect intent 状态。 */
    void markPublished(PendingDispatch row, int attempts, String publishedAt);

    /** 标记为死信，并同步推进 effect intent 状态。 */
    void markDeadLettered(PendingDispatch row, int attempts, String deadLetteredAt, String error);

    /** 记录可重试失败及下次执行时间。 */
    void markRetry(PendingDispatch row, int attempts, String nextAttemptAt, String error);

    record PendingDispatch(String tenantId, String outboxId, String commandId, String topic,
            String partitionKey, String payload, int attempts) { }
}
