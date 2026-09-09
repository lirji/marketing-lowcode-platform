package com.acme.marketing.referral.infrastructure.persistence;

import com.acme.marketing.referral.application.release.ReferralRuntimeRepository;
import com.acme.marketing.referral.application.release.ReferralReleaseRepository.Stored;
import com.acme.marketing.referral.infrastructure.persistence.mapper.ReferralRuntimeMapper;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 只操作本服务关系库；指令追加与游标更新不拆事务。 */
@Repository
public class MybatisReferralRuntimeRepository implements ReferralRuntimeRepository {
    private final ReferralRuntimeMapper mapper;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS").withZone(ZoneOffset.UTC);
    /** 注入本服务 SQL Mapper。 */
    public MybatisReferralRuntimeRepository(ReferralRuntimeMapper mapper) { this.mapper = mapper; }
    /** 恢复所需的原始制品仍走独立验签边界。 */
    @Override public Stored installed(Key key, long generation) { var values = values(key); values.put("generation", generation); return mapper.installed(values); }
    /** 唯一键竞争等待后当前锁读，空流仅保留序号零。 */
    @Override public Cursor lock(Key key) { requireTransaction(); var values = values(key); mapper.reserve(values); return mapper.lock(values); }
    /** 本方法不赋予状态可信性，调用方需重新验签。 */
    @Override public Cursor current(Key key) { return mapper.current(values(key)); }
    /** 无审计成功或 CAS 失败均抛异常，使上层整体回滚。 */
    @Override public void advance(Key key, long expectedSequence, long sequence, String json, Instant now) {
        requireTransaction();
        if (sequence <= expectedSequence) throw new IllegalArgumentException("REFERRAL_SEQUENCE_NOT_ADVANCING");
        var values = values(key); values.put("expected", expectedSequence); values.put("sequence", sequence);
        values.put("json", json); values.put("now", TIME.format(now));
        if (mapper.append(values) != 1 || mapper.advance(values) != 1) throw new IllegalStateException("REFERRAL_RUNTIME_CAS_FAILED");
    }
    private Map<String, Object> values(Key key) {
        var result = new HashMap<String, Object>(); result.put("tenant", key.tenantId()); result.put("kind", key.kind());
        result.put("environment", key.environment()); result.put("cell", key.cell()); result.put("namespace", key.namespace()); return result;
    }
    private static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("REFERRAL_RUNTIME_TRANSACTION_REQUIRED");
    }
}
