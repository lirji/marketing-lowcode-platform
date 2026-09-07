package com.acme.marketing.decision.runtime;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 决策紧急开关的持久化端口。 */
public interface DecisionKillSwitchStore {

    /** 加锁读取租户在指定命名空间的当前开关状态。 */
    Optional<StoredState> lock(String tenantId, String namespace);

    /** 尝试插入首条开关状态；并发唯一键冲突时返回 {@code false}。 */
    boolean tryInsert(String tenantId, String namespace, long sequence, boolean enabled, String reason,
            String signature, String directiveJson, Instant updatedAt);

    /** 更新已存在的开关状态。 */
    void update(String tenantId, String namespace, long sequence, boolean enabled, String reason,
            String signature, String directiveJson, Instant updatedAt);

    /** 查询命名空间中的全部已持久化指令，用于重建热缓存。 */
    List<StoredDirective> findDirectives(String namespace);

    /** 加锁读取所需的精简状态。 */
    record StoredState(long sequence, String signature, boolean enabled, String reason) { }

    /** 缓存重建所需的租户和指令 JSON。 */
    record StoredDirective(String tenantId, String directiveJson) { }
}
