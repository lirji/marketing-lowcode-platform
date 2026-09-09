package com.acme.marketing.referral.infrastructure.persistence;

import com.acme.marketing.referral.application.release.ReferralReleaseRepository;
import com.acme.marketing.referral.infrastructure.persistence.mapper.ReferralReleaseMapper;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Objects;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 制品和清单同事务写入；不跨服务数据库，也不建立未提交的 ACK。 */
@Repository
public class MybatisReferralReleaseRepository implements ReferralReleaseRepository {
    private static final DateTimeFormatter SQL_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS").withZone(ZoneOffset.UTC);
    private final ReferralReleaseMapper mapper;
    /** 注入本服务 Mapper，保持已有 MyBatis 和事务约定。 */
    public MybatisReferralReleaseRepository(ReferralReleaseMapper mapper) { this.mapper = mapper; }
    /** 真实行锁后返回原事实；调用方比较完整内容而非依赖 affectedRows。 */
    @Override public Stored reserveAndLock(Stored proposed) {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("REFERRAL_RELEASE_TRANSACTION_REQUIRED");
        var values = new HashMap<String, Object>();
        values.put("tenant", proposed.tenantId()); values.put("environment", proposed.environment());
        values.put("cell", proposed.cell()); values.put("namespace", proposed.namespace());
        values.put("generation", proposed.generation()); values.put("manifestId", proposed.manifestId());
        values.put("releaseKeyId", proposed.releaseKeyId()); values.put("manifestJson", proposed.manifestJson());
        values.put("payload", proposed.payload()); values.put("verifiedAt", SQL_TIME.format(proposed.verifiedAt()));
        mapper.reserve(values);
        return Objects.requireNonNull(mapper.lock(values), "reserved release disappeared");
    }
}
