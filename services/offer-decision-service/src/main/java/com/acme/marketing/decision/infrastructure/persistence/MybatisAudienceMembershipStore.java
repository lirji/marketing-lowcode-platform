package com.acme.marketing.decision.infrastructure.persistence;

import static com.acme.marketing.platform.time.SqlTime.format;

import com.acme.marketing.decision.infrastructure.persistence.mapper.DecisionMapper;
import com.acme.marketing.decision.infrastructure.persistence.mapper.DecisionMapper.AudienceMembershipRow;
import com.acme.marketing.decision.infrastructure.persistence.mapper.DecisionMapper.AudienceMembershipWrite;
import com.acme.marketing.decision.runtime.AudienceMembershipStore;
import java.time.Instant;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

/** 基于 MyBatis 的决策侧受众成员投影适配器。 */
@Repository
public class MybatisAudienceMembershipStore implements AudienceMembershipStore {
    private final DecisionMapper mapper;

    public MybatisAudienceMembershipStore(DecisionMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 使用“条件更新—插入—并发冲突后重试条件更新”处理乱序和并发首写。
     *
     * <p>最终是否覆盖完全由数据库中的 membership_version 决定。
     */
    @Override
    public void saveIfNewer(String tenantId, String audienceId, String subjectHash, boolean member,
            long version, Instant expiresAt, Instant updatedAt) {
        AudienceMembershipWrite write = new AudienceMembershipWrite(tenantId, audienceId, subjectHash, member,
                version, format(expiresAt), format(updatedAt));
        if (mapper.updateAudienceMembershipIfNewer(write) != 0) return;
        try {
            mapper.insertAudienceMembership(write);
        } catch (DuplicateKeyException concurrentOrStale) {
            mapper.updateAudienceMembershipIfNewer(write);
        }
    }

    @Override
    public List<StoredMembership> findChangedSince(Instant updatedSince) {
        return mapper.selectAudienceMembershipsChangedSince(format(updatedSince)).stream()
                .map(MybatisAudienceMembershipStore::toStored).toList();
    }

    private static StoredMembership toStored(AudienceMembershipRow row) {
        return new StoredMembership(row.tenantId(), row.audienceId(), row.subjectHash(), row.memberValue(),
                row.membershipVersion(), Instant.parse(row.expiresAt()), Instant.parse(row.updatedAt()));
    }
}
