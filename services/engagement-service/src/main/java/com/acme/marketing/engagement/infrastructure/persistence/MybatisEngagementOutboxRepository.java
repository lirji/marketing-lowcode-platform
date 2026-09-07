package com.acme.marketing.engagement.infrastructure.persistence;

import static com.acme.marketing.platform.time.SqlTime.format;

import com.acme.marketing.engagement.application.EngagementOutboxRepository;
import com.acme.marketing.engagement.infrastructure.persistence.mapper.EngagementMapper;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Repository;

/** 基于 MyBatis 的互动 outbox 持久化适配器。 */
@Repository
public class MybatisEngagementOutboxRepository implements EngagementOutboxRepository {
    private final EngagementMapper mapper;

    public MybatisEngagementOutboxRepository(EngagementMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public List<PendingEvent> findPending(Instant now, int limit) {
        return mapper.selectPendingOutbox(format(now), limit).stream()
                .map(row -> new PendingEvent(row.tenantId(), row.eventId(), row.destinationTopic(),
                        row.partitionKey(), row.payloadJson(), row.publishAttempts()))
                .toList();
    }

    @Override
    public void markPublished(PendingEvent event, int attempts, Instant publishedAt) {
        requireOne(mapper.markOutboxPublished(status(event, attempts, publishedAt, "")), "标记互动事件已发布");
    }

    @Override
    public void markDead(PendingEvent event, int attempts, Instant deadAt, String error) {
        requireOne(mapper.markOutboxDead(status(event, attempts, deadAt, error)), "标记互动事件为死信");
    }

    @Override
    public void markRetry(PendingEvent event, int attempts, Instant nextAttemptAt, String error) {
        requireOne(mapper.markOutboxRetry(status(event, attempts, nextAttemptAt, error)), "安排互动事件重试");
    }

    private static EngagementMapper.OutboxStatusWrite status(PendingEvent event, int attempts,
            Instant stateAt, String error) {
        return new EngagementMapper.OutboxStatusWrite(event.tenantId(), event.eventId(), attempts,
                format(stateAt), error);
    }

    private static void requireOne(int affected, String operation) {
        if (affected != 1) throw new IllegalStateException(operation + "的受影响行数不正确");
    }
}
