package com.acme.marketing.benefit.infrastructure.persistence;

import com.acme.marketing.benefit.application.AwardIntentRelayRepository;
import com.acme.marketing.benefit.infrastructure.persistence.mapper.AwardIntentRelayMapper;
import java.util.List;
import org.springframework.stereotype.Repository;

/** 基于 MyBatis 的发奖 Relay 仓储适配器。 */
@Repository
public class MybatisAwardIntentRelayRepository implements AwardIntentRelayRepository {
    private final AwardIntentRelayMapper mapper;

    public MybatisAwardIntentRelayRepository(AwardIntentRelayMapper mapper) { this.mapper = mapper; }

    @Override public List<PendingIntent> findCandidates(String now, int tenantLimit, int totalLimit) {
        return mapper.selectCandidates(now, tenantLimit, totalLimit);
    }
    @Override public int claim(ClaimWrite write) { return mapper.claim(write); }
    @Override public int markSent(SentWrite write) { return mapper.updateSent(write); }
    @Override public int markFailure(FailureWrite write) { return mapper.updateFailure(write); }
}
