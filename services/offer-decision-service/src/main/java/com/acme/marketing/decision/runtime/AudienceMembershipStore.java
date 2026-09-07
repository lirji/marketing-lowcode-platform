package com.acme.marketing.decision.runtime;

import java.time.Instant;
import java.util.List;

/** 决策侧受众成员投影的持久化端口。 */
public interface AudienceMembershipStore {

    /** 仅当事件版本更新时写入投影，保证乱序事件不会覆盖新状态。 */
    void saveIfNewer(String tenantId, String audienceId, String subjectHash, boolean member,
            long version, Instant expiresAt, Instant updatedAt);

    /** 查询指定水位之后发生变化的投影记录。 */
    List<StoredMembership> findChangedSince(Instant updatedSince);

    /** 决策侧持久化的受众成员投影。 */
    record StoredMembership(String tenantId, String audienceId, String subjectHash,
            boolean member, long version, Instant expiresAt, Instant updatedAt) { }
}
