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

    @Override
    public void complete(String tenantId, String idempotencyKey, String responseJson) {
        int updated = mapper.completeCommand(tenantId, idempotencyKey, responseJson);
        if (updated != 1) throw new IllegalStateException("完成决策幂等请求的受影响行数不正确");
    }
}
