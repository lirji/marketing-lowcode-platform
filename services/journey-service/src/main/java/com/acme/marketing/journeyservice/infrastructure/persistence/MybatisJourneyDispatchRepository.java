package com.acme.marketing.journeyservice.infrastructure.persistence;

import com.acme.marketing.journeyservice.application.JourneyDispatchRepository;
import com.acme.marketing.journeyservice.infrastructure.persistence.mapper.JourneyMapper;
import java.util.List;
import org.springframework.stereotype.Repository;

/** 基于 MyBatis 的旅程 dispatch outbox 适配器。 */
@Repository
public class MybatisJourneyDispatchRepository implements JourneyDispatchRepository {
    private final JourneyMapper mapper;

    public MybatisJourneyDispatchRepository(JourneyMapper mapper) { this.mapper = mapper; }

    @Override public List<PendingDispatch> lockPending(String now, int limit) {
        return mapper.selectPendingDispatches(now, limit);
    }

    @Override public void markPublished(PendingDispatch row, int attempts, String publishedAt) {
        one(mapper.updateDispatchPublished(row.tenantId(), row.outboxId(), attempts, publishedAt), "完成 dispatch outbox");
        one(mapper.updateEffectState(row.tenantId(), row.commandId(), "DISPATCHED"), "完成节点副作用");
    }

    @Override public void markDeadLettered(PendingDispatch row, int attempts, String deadLetteredAt, String error) {
        one(mapper.updateDispatchDeadLettered(row.tenantId(), row.outboxId(), attempts, deadLetteredAt, error),
                "死信 dispatch outbox");
        one(mapper.updateEffectState(row.tenantId(), row.commandId(), "DISPATCH_FAILED"), "标记节点副作用失败");
    }

    @Override public void markRetry(PendingDispatch row, int attempts, String nextAttemptAt, String error) {
        one(mapper.updateDispatchRetry(row.tenantId(), row.outboxId(), attempts, nextAttemptAt, error),
                "重试 dispatch outbox");
    }

    private static void one(int affected, String operation) {
        if (affected != 1) throw new IllegalStateException(operation + "的受影响行数不是 1");
    }
}
