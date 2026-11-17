package com.acme.marketing.decision.infrastructure.persistence;

import com.acme.marketing.contracts.release.ReleaseBundleProtocolError;
import com.acme.marketing.decision.application.BundleAdmissionRepository;
import com.acme.marketing.decision.infrastructure.persistence.mapper.BundleAdmissionMapper;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

/** 数据库唯一键为最终仲裁者，进程重启或多个namespace不会形成各自的首次参与。 */
@Repository
public class MybatisBundleAdmissionRepository implements BundleAdmissionRepository {
    private final BundleAdmissionMapper mapper;
    /** 与服务短事务共用连接，不在持久层启动线程或嵌套独立提交。 */
    public MybatisBundleAdmissionRepository(BundleAdmissionMapper mapper) { this.mapper = mapper; }
    @Override public Optional<AppliedAuthorization> lockApplied(Key key) { return Optional.ofNullable(mapper.lockApplied(key)); }
    @Override public Optional<AppliedAuthorization> applied(Key key, long sequence) { return Optional.ofNullable(mapper.applied(key, sequence)); }
    @Override public Optional<StoredParticipation> participation(Key key, String participationId) { return Optional.ofNullable(mapper.participation(key, participationId)); }
    @Override public boolean insert(Key key, String participationId, StoredParticipation value) {
        try {
            if (mapper.insert(key, participationId, value) != 1)
                throw new IllegalStateException(ReleaseBundleProtocolError.PARTICIPATION_STORED_INVALID.message());
            return true;
        } catch (DuplicateKeyException raced) { return false; }
    }
}
