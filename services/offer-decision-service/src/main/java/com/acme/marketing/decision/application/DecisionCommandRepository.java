package com.acme.marketing.decision.application;

import java.time.Instant;
import java.util.Optional;

/** 决策请求幂等记录的持久化端口。 */
public interface DecisionCommandRepository {

    /** 删除指定幂等键已经过期的记录。 */
    void deleteExpired(String tenantId, String idempotencyKey, Instant now);

    /** 尝试声明幂等键；唯一键已存在时返回 {@code false}。 */
    boolean tryClaim(String tenantId, String idempotencyKey, String payloadHash,
            Instant createdAt, Instant expiresAt);

    /** 加锁读取幂等记录，等待并发事务完成。 */
    Optional<StoredCommand> lock(String tenantId, String idempotencyKey);

    /** 将当前调用拥有的幂等记录标记为完成。 */
    void complete(String tenantId, String idempotencyKey, String responseJson);

    /** 在当前完成事务中保存脱敏摘要，不能在回放时重新生成另一份事实。 */
    void saveTrace(String tenantId, String idempotencyKey, String traceJson, String requestHash, String orderHash, String sort, String businessNo);

    /** 按受租户隔离的请求或订单索引读取未过期的最新完成结果。 */
    Optional<String> findTrace(String tenantId, String requestHash, String orderHash, Instant now);

    /** 通过运营编号或原报价引用查询保留期内的同一脱敏轨迹。 */
    Optional<String> findQuoteTrace(String tenantId, String number, Instant now);

    /** 数据库存储的幂等请求状态。 */
    record StoredCommand(String payloadHash, String state, String responseJson) { }
}
