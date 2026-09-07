package com.acme.marketing.eventgateway.infrastructure.persistence;

import static com.acme.marketing.platform.time.SqlTime.format;

import com.acme.marketing.eventgateway.application.EventOutboxRepository;
import com.acme.marketing.eventgateway.infrastructure.persistence.mapper.EventGatewayMapper;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Repository;

/** 基于 MyBatis 的 Event outbox 租约与状态持久化适配器。 */
@Repository
public class MybatisEventOutboxRepository implements EventOutboxRepository {
    private final EventGatewayMapper mapper;

    public MybatisEventOutboxRepository(EventGatewayMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public List<PendingEvent> findClaimCandidates(Instant now, int limit) {
        return mapper.selectClaimCandidates(format(now), limit).stream()
                .map(row -> new PendingEvent(row.tenantId(), row.outboxId(), row.destinationTopic(),
                        row.partitionKey(), row.payloadJson(), row.publishAttempts(), row.leaseVersion() + 1,
                        row.depthBucketId()))
                .toList();
    }

    @Override
    public boolean claim(PendingEvent event, String workerId, Instant now, Instant leaseUntil) {
        return mapper.claimOutbox(new EventGatewayMapper.OutboxLeaseWrite(event.tenantId(), event.outboxId(),
                workerId, format(leaseUntil), event.leaseVersion(), event.leaseVersion() - 1,
                format(now))) == 1;
    }

    @Override
    public boolean markPublished(PendingEvent event, String workerId, Instant publishedAt) {
        return mapper.markOutboxPublished(new EventGatewayMapper.OutboxTerminalWrite(event.tenantId(),
                event.outboxId(), workerId, event.leaseVersion(), event.attempts() + 1,
                format(publishedAt))) == 1;
    }

    @Override
    public boolean markDead(PendingEvent event, String workerId, int attempts, Instant deadLetteredAt,
            String error) {
        return mapper.markOutboxDead(failure(event, workerId, attempts, deadLetteredAt, error)) == 1;
    }

    @Override
    public boolean markRetry(PendingEvent event, String workerId, int attempts, Instant nextAttemptAt,
            String error) {
        return mapper.markOutboxRetry(failure(event, workerId, attempts, nextAttemptAt, error)) == 1;
    }

    @Override
    public Instant findOldestPendingCreatedAt() {
        String value = mapper.selectOldestPendingCreatedAt();
        return value == null ? null : Instant.parse(value);
    }

    private static EventGatewayMapper.OutboxFailureWrite failure(PendingEvent event, String workerId,
            int attempts, Instant stateAt, String error) {
        return new EventGatewayMapper.OutboxFailureWrite(event.tenantId(), event.outboxId(), workerId,
                event.leaseVersion(), attempts, format(stateAt), error);
    }
}
