package com.acme.marketing.decision.infrastructure.persistence;

import com.acme.marketing.decision.application.BundleDirectiveRepository;
import com.acme.marketing.decision.infrastructure.persistence.mapper.BundleDirectiveMapper;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/** 由应用层短事务同时提交历史与head，不设置跨线程事务或内存状态。 */
@Repository
public class MybatisBundleDirectiveRepository implements BundleDirectiveRepository {
    private final BundleDirectiveMapper mapper;
    /** 注入真实持久Mapper，重启后仍使用同一权威投影。 */
    public MybatisBundleDirectiveRepository(BundleDirectiveMapper mapper) { this.mapper = mapper; }
    @Override public Long lockHead(Key key, String now) { mapper.ensureHead(key, now); return mapper.lockHead(key); }
    @Override public Optional<StoredDirective> history(Key key, long sequence) { return Optional.ofNullable(mapper.history(key, sequence)); }
    @Override public Optional<Long> sequenceForId(Key key, String directiveId) { return Optional.ofNullable(mapper.sequenceForId(key, directiveId)); }
    @Override public void save(Key key, StoredDirective value, String recordedBy) {
        if (mapper.save(key, value, recordedBy) != 1) throw new IllegalStateException("组合授权历史写入行数不正确");
    }
    @Override public int advance(Key key, Long expected, long sequence, String now) { return mapper.advance(key, expected, sequence, now); }
    @Override public Optional<StoredDirective> latest(Key key) { return Optional.ofNullable(mapper.latest(key)); }
}
