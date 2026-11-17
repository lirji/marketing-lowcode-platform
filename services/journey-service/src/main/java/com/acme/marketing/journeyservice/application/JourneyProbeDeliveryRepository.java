package com.acme.marketing.journeyservice.application;

import com.acme.marketing.journeyservice.application.JourneyExecutionProbeRepository.Key;
import java.util.Optional;

/** 本库投递任务及租约代次；网络发送必须发生在这些短事务之外。 */
public interface JourneyProbeDeliveryRepository {
    /** 与派发校验同事务创建，重复不能重置nonce、失败终态或租约。 */
    void ensure(Key key, NewDelivery value);
    /** 精确任务读取，不允许客户端选择其他租户或位置。 */
    Optional<Delivery> find(Key key);
    /** 只锁工作行、SKIP LOCKED取一条；本事务不能再等待父挑战行。 */
    Optional<Delivery> lockDue(Slot slot, String now);
    /** 每次成功领取递增代次，即使上一进程已消失也不复用旧令牌。 */
    int claim(Key key, long expectedEpoch, String owner, String leaseUntil, String now);
    /** 按owner/epoch/有效租约提交进度；0行表示旧执行者，无权覆盖新进度。 */
    int finish(Key key, Lease lease, Completion completion, String now);
    /** 有界关闭已到期且无有效租约的任务，避免陈旧积压阻塞新挑战。 */
    int expireDue(Slot slot, String now, int limit, String errorCode);

    record Slot(String environment, String cell, String namespace) { }
    record NewDelivery(String destinationTopic, int totalRoutes, String nextAttemptAt, String expiresAt, String createdBy, String createdAt) { }
    record Delivery(String tenantId, String environment, String cell, String namespace, String campaignId, String probeId,
            String destinationTopic, int totalRoutes, int nextNonce, String deliveryState, int failureCount,
            String nextAttemptAt, String expiresAt, String leaseOwner, long leaseEpoch, String leaseUntil,
            String lastErrorCode, String createdBy, String createdAt, String updatedAt) {
        /** 只从已持久任务恢复完整作用域，不从Kafka回执推导授权。 */
        public Key key() { return new Key(tenantId, environment, cell, namespace, campaignId, probeId); }
    }
    record Lease(String owner, long epoch) { }
    record Completion(int nextNonce, String state, int failureCount, String nextAttemptAt, String errorCode) { }
}
