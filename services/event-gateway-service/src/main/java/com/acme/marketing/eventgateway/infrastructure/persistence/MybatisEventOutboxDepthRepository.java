package com.acme.marketing.eventgateway.infrastructure.persistence;

import static com.acme.marketing.platform.time.SqlTime.format;

import com.acme.marketing.eventgateway.application.EventOutboxDepthRepository;
import com.acme.marketing.eventgateway.infrastructure.persistence.mapper.EventGatewayMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.zip.CRC32;
import org.springframework.stereotype.Repository;

/**
 * 基于 MyBatis 的 outbox 分片深度计数适配器。
 *
 * <p>全局和热点租户均拆为固定 64 行，接入路径不争用单一计数行。
 */
@Repository
public class MybatisEventOutboxDepthRepository implements EventOutboxDepthRepository {
    static final int BUCKET_COUNT = 64;
    private static final String GLOBAL_SCOPE = "GLOBAL";
    private static final String TENANT_SCOPE = "TENANT";
    private static final String GLOBAL_ID = "*";

    private final EventGatewayMapper mapper;

    public MybatisEventOutboxDepthRepository(EventGatewayMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public int bucketId(String outboxId) {
        CRC32 crc = new CRC32();
        crc.update(outboxId.getBytes(StandardCharsets.UTF_8));
        return (int) (crc.getValue() % BUCKET_COUNT);
    }

    @Override
    public void increment(String tenantId, int bucketId, Instant now) {
        validateBucket(bucketId);
        requireChanged(mapper.incrementDepth(depth(GLOBAL_SCOPE, GLOBAL_ID, bucketId, now)), "增加全局 outbox 深度");
        requireChanged(mapper.incrementDepth(depth(TENANT_SCOPE, tenantId, bucketId, now)), "增加租户 outbox 深度");
    }

    @Override
    public void decrement(String tenantId, int bucketId, Instant now) {
        validateBucket(bucketId);
        int global = mapper.decrementDepth(depth(GLOBAL_SCOPE, GLOBAL_ID, bucketId, now));
        int tenant = mapper.decrementDepth(depth(TENANT_SCOPE, tenantId, bucketId, now));
        if (global != 1 || tenant != 1) {
            throw new IllegalStateException("event outbox depth counter is missing or would underflow");
        }
    }

    @Override
    public long globalPending() {
        return pending(GLOBAL_SCOPE, GLOBAL_ID);
    }

    @Override
    public long tenantPending(String tenantId) {
        return pending(TENANT_SCOPE, tenantId);
    }

    private long pending(String scopeType, String scopeId) {
        Long value = mapper.sumDepth(scopeType, scopeId);
        return value == null ? 0 : value;
    }

    private static EventGatewayMapper.DepthWrite depth(String scopeType, String scopeId, int bucketId,
            Instant now) {
        return new EventGatewayMapper.DepthWrite(scopeType, scopeId, bucketId, format(now));
    }

    private static void validateBucket(int bucketId) {
        if (bucketId < 0 || bucketId >= BUCKET_COUNT) {
            throw new IllegalArgumentException("event outbox depth bucket is invalid");
        }
    }

    /**
     * MySQL 对 {@code INSERT ... ON DUPLICATE KEY UPDATE} 的返回值约定为：插入新行时为 1，
     * 更新已有行时通常为 2。因此这里只要求确实发生了变更，不能按普通 UPDATE 强制等于 1。
     */
    private static void requireChanged(int affected, String operation) {
        if (affected < 1) throw new IllegalStateException(operation + "未更新任何深度计数");
    }
}
