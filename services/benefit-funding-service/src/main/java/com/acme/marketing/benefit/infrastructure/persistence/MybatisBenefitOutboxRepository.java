package com.acme.marketing.benefit.infrastructure.persistence;

import com.acme.marketing.benefit.application.BenefitOutboxRepository;
import com.acme.marketing.benefit.infrastructure.persistence.mapper.BenefitOutboxMapper;
import java.util.List;
import org.springframework.stereotype.Repository;

/** 基于 MyBatis 的权益业务 Outbox 仓储适配器。 */
@Repository
public class MybatisBenefitOutboxRepository implements BenefitOutboxRepository {
    private final BenefitOutboxMapper mapper;

    public MybatisBenefitOutboxRepository(BenefitOutboxMapper mapper) { this.mapper = mapper; }

    @Override public List<PendingEvent> lockPublishable(String now, int limit) {
        return mapper.selectPublishable(now, limit);
    }
    @Override public int markPublished(String tenantId, String eventId, String publishedAt, int attempts) {
        return mapper.updatePublished(tenantId, eventId, publishedAt, attempts);
    }
    @Override public int markDead(String tenantId, String eventId, String deadAt, int attempts, String error) {
        return mapper.updateDead(tenantId, eventId, deadAt, attempts, error);
    }
    @Override public int markRetry(String tenantId, String eventId, String nextAttemptAt, int attempts,
            String error) { return mapper.updateRetry(tenantId, eventId, nextAttemptAt, attempts, error); }
}
