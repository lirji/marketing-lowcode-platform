package com.acme.marketing.journeyservice.infrastructure.persistence;

import com.acme.marketing.contracts.release.ReleaseBundleProtocolError;
import com.acme.marketing.journeyservice.application.JourneyExecutionProbeRepository;
import com.acme.marketing.journeyservice.infrastructure.persistence.mapper.JourneyExecutionProbeMapper;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/** 使用原Journey事务管理器提交证据，进程重建不丢失覆盖集合。 */
@Repository
public class MybatisJourneyExecutionProbeRepository implements JourneyExecutionProbeRepository {
    private final JourneyExecutionProbeMapper mapper;
    /** 注入持久Mapper，不维护第二套内存权威状态。 */
    public MybatisJourneyExecutionProbeRepository(JourneyExecutionProbeMapper mapper) { this.mapper = mapper; }
    @Override public void ensure(Key key, StoredProbe value) { mapper.ensure(key, value); }
    @Override public Optional<StoredProbe> lock(Key key) { return Optional.ofNullable(mapper.lock(key)); }
    @Override public List<StoredAck> acknowledgements(Key key) { return mapper.acknowledgements(key); }
    @Override public void saveAck(Key key, StoredAck value) {
        if (mapper.saveAck(key, value) != 1) throw ReleaseBundleProtocolError.EXECUTION_STORED_INVALID.conflict();
    }
}
