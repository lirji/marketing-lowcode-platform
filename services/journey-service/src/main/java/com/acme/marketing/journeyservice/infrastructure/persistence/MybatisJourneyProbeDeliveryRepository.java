package com.acme.marketing.journeyservice.infrastructure.persistence;

import com.acme.marketing.journeyservice.application.JourneyExecutionProbeRepository.Key;
import com.acme.marketing.journeyservice.application.JourneyProbeDeliveryRepository;
import com.acme.marketing.journeyservice.infrastructure.persistence.mapper.JourneyProbeDeliveryMapper;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/** 由应用层分隔领取/发送/提交三段；此适配器不私自扩大事务或重试。 */
@Repository
public class MybatisJourneyProbeDeliveryRepository implements JourneyProbeDeliveryRepository {
    private final JourneyProbeDeliveryMapper mapper;
    /** 注入真实持久Mapper，任务状态只以数据库为准。 */
    public MybatisJourneyProbeDeliveryRepository(JourneyProbeDeliveryMapper mapper) { this.mapper = mapper; }
    @Override public void ensure(Key key, NewDelivery value) { mapper.ensure(key, value); }
    @Override public Optional<Delivery> find(Key key) { return Optional.ofNullable(mapper.find(key)); }
    @Override public Optional<Delivery> lockDue(Slot slot, String now) { return Optional.ofNullable(mapper.lockDue(slot, now)); }
    @Override public int claim(Key key, long expectedEpoch, String owner, String leaseUntil, String now) { return mapper.claim(key, expectedEpoch, owner, leaseUntil, now); }
    @Override public int finish(Key key, Lease lease, Completion completion, String now) { return mapper.finish(key, lease, completion, now); }
    @Override public int expireDue(Slot slot, String now, int limit, String errorCode) { return mapper.expireDue(slot, now, limit, errorCode); }
}
