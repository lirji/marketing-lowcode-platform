package com.acme.marketing.benefit.application;

import java.util.List;
import java.util.Optional;

/** 本地履约观察与查询租约；不修改发奖投递状态。 */
public interface AwardFulfillmentRepository {
    /** 读取本租户已持久化的原始意图，不能接收客户端覆盖来源或主体。 */
    Optional<Intent> findIntent(String tenantId, String intentId);
    /** 以唯一键创建观察任务；调用者事务可与首次发奖受理一起提交。 */
    void initialize(String tenantId, String intentId, String now);
    /** 明确人工重试时唤醒等待/暂停记录，不能抢占仍在执行的租约。 */
    void requestRefresh(String tenantId, String intentId, String now);
    /** 获取按租户限额的到期任务。 */
    List<Candidate> due(String now, int tenantLimit, int limit);
    /** 当前租约代次成功前不能进行远程查询。 */
    boolean claim(Lease lease);
    /** 成功与错误都以租约代次条件提交，旧执行者不能覆盖新观察。 */
    boolean complete(Completion completion);
    /** 查询保留的最近成功结果及独立同步状态。 */
    Optional<Stored> find(String tenantId, String intentId);

    record Intent(String tenantId, String intentId, String sourceSystem, String sourceRequestId,
            String deliveryMode, String benefitOrderNo, String payload) { }
    record Candidate(String tenantId, String intentId, long leaseVersion, int failures) { }
    record Lease(String tenantId, String intentId, long expectedVersion, String owner, String now, String until) { }
    record Completion(String tenantId, String intentId, long leaseVersion, String owner, String state,
            String orderNo, String centerStatus, String itemsJson, String checkedAt, int failures,
            String errorCode, String nextAttemptAt, String now) { }
    record Stored(String intentId, String state, String orderNo, String centerStatus, String itemsJson,
            String checkedAt, int failures, String errorCode, long leaseVersion, String nextAttemptAt) { }
}
