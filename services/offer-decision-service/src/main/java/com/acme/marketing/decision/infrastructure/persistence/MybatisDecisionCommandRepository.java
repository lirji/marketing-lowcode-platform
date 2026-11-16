package com.acme.marketing.decision.infrastructure.persistence;

import static com.acme.marketing.platform.time.SqlTime.format;

import com.acme.marketing.decision.application.DecisionCommandRepository;
import com.acme.marketing.decision.infrastructure.persistence.mapper.DecisionMapper;
import com.acme.marketing.decision.infrastructure.persistence.mapper.DecisionMapper.CommandRow;
import com.acme.marketing.decision.infrastructure.persistence.mapper.DecisionMapper.CommandWrite;
import java.time.Instant;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

/** 基于 MyBatis 的决策请求幂等持久化适配器。 */
@Repository
public class MybatisDecisionCommandRepository implements DecisionCommandRepository {
    private final DecisionMapper mapper;

    public MybatisDecisionCommandRepository(DecisionMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void deleteExpired(String tenantId, String idempotencyKey, Instant now) {
        mapper.deleteExpiredCommand(tenantId, idempotencyKey, format(now));
    }

    @Override
    public boolean tryClaim(String tenantId, String idempotencyKey, String payloadHash,
            Instant createdAt, Instant expiresAt) {
        try {
            return mapper.insertCommand(new CommandWrite(tenantId, idempotencyKey, payloadHash, "PROCESSING", null,
                    format(createdAt), format(expiresAt))) == 1;
        } catch (DuplicateKeyException duplicate) {
            return false;
        }
    }

    @Override
    public Optional<StoredCommand> lock(String tenantId, String idempotencyKey) {
        CommandRow row = mapper.selectCommandForUpdate(tenantId, idempotencyKey);
        return row == null ? Optional.empty()
                : Optional.of(new StoredCommand(row.payloadHash(), row.stateName(), row.responseJson()));
    }

    /** 与完成命令共享事务，任何持久化失败都回滚整个首次决策。 */
    @Override public void saveTrace(String tenantId, String idempotencyKey, String traceJson, String requestHash, String orderHash, String sort) {
        if (mapper.saveTrace(tenantId, idempotencyKey, traceJson, requestHash, orderHash, sort) != 1)
            throw new IllegalStateException("自动轨迹未写入已完成的决策");
    }

    /** 空结果包括过期和旧版本未生成摘要，不能从外部拼造轨迹。 */
    @Override public Optional<String> findTrace(String tenantId, String requestHash, String orderHash, Instant now) {
        return Optional.ofNullable(mapper.findTrace(tenantId, requestHash, orderHash, format(now)));
    }

    @Override
    public void complete(String tenantId, String idempotencyKey, String responseJson) {
        int updated = mapper.completeCommand(tenantId, idempotencyKey, responseJson);
        if (updated != 1) throw new IllegalStateException("完成决策幂等请求的受影响行数不正确");
    }
}
