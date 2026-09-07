package com.acme.marketing.control.infrastructure.persistence;

import com.acme.marketing.control.application.ReleaseOutboxRepository;
import com.acme.marketing.control.infrastructure.persistence.mapper.ControlMapper;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/** 基于 MyBatis 的发布 outbox 适配器。 */
@Repository
public class MybatisReleaseOutboxRepository implements ReleaseOutboxRepository {
    private final ControlMapper mapper;

    public MybatisReleaseOutboxRepository(ControlMapper mapper) { this.mapper = mapper; }

    @Override public Optional<PendingEvent> lockNext(String now) {
        return Optional.ofNullable(mapper.selectNextOutbox(now));
    }
    @Override public void markPublished(PendingEvent event, String publishedAt, int attempts) {
        one(mapper.updateOutboxPublished(event.tenantId(), event.eventId(), publishedAt, attempts));
    }
    @Override public void markRetry(PendingEvent event, String nextAttemptAt, int attempts, String error) {
        one(mapper.updateOutboxRetry(event.tenantId(), event.eventId(), nextAttemptAt, attempts, error));
    }
    private static void one(int affected) {
        if (affected != 1) throw new IllegalStateException("发布 outbox 状态未按预期更新");
    }
}
