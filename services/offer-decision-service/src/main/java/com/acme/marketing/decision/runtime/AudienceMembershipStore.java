package com.acme.marketing.decision.runtime;

import java.time.Instant;
import java.util.Optional;

/** 决策侧受众成员投影的持久化端口。 */
public interface AudienceMembershipStore {

    /** 仅当事件版本更新时写入投影，保证乱序事件不会覆盖新状态。 */
    void saveIfNewer(String tenantId, String audienceId, String subjectHash, boolean member,
            long version, Instant expiresAt, Instant updatedAt);

    /** 按主键查询一个成员，避免时间水位遗漏迟提交事务以及启动全量扫描。 */
    Optional<StoredMembership> find(String tenantId, String audienceId, String subjectHash);

    /** 决策侧持久化的受众成员投影。 */
    record StoredMembership(String tenantId, String audienceId, String subjectHash,
            boolean member, long version, Instant expiresAt, Instant updatedAt) { }
}
