package com.acme.marketing.eventgateway.application;

import java.time.Instant;

/** Event outbox 分片深度计数的持久化端口。 */
public interface EventOutboxDepthRepository {

    /** 根据 outbox 标识计算稳定的计数分桶。 */
    int bucketId(String outboxId);

    /** 在 outbox 插入事务内同时增加全局与租户计数。 */
    void increment(String tenantId, int bucketId, Instant now);

    /** 在发布终态回写事务内同时减少全局与租户计数。 */
    void decrement(String tenantId, int bucketId, Instant now);

    /** 查询全局待处理深度。 */
    long globalPending();

    /** 查询租户待处理深度。 */
    long tenantPending(String tenantId);
}
