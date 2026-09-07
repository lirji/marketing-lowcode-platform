package com.acme.marketing.platform.web;

import static com.acme.marketing.platform.time.SqlTime.format;

import com.acme.marketing.platform.crypto.Digests;
import com.acme.marketing.platform.error.ConflictException;
import com.acme.marketing.platform.identity.TenantId;
import com.acme.marketing.platform.web.persistence.IdempotencyCommandMapper;
import com.acme.marketing.platform.web.persistence.IdempotencyCommandMapper.CommandWrite;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Supplier;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * 使用各领域库中的 mk_api_command 实现统一写接口幂等语义。
 * payload hash、业务写入和首次响应在同一本地事务中提交，因此进程重启后仍可原样重放。
 */
public final class PersistentIdempotentCommandExecutor {
    private final IdempotencyCommandMapper commands;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final Duration retention;
    private final TransactionTemplate transactions;

    public PersistentIdempotentCommandExecutor(IdempotencyCommandMapper commands, ObjectMapper mapper, Clock clock,
            PlatformTransactionManager transactionManager, Duration retention) {
        if (retention == null || retention.isZero() || retention.isNegative()) {
            throw new IllegalArgumentException("idempotency retention must be positive");
        }
        this.commands = commands;
        this.mapper = mapper;
        this.clock = clock;
        this.retention = retention;
        this.transactions = new TransactionTemplate(transactionManager);
        this.transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    /** 执行首次命令，或在相同 operation/key/payload 时返回首次序列化响应。 */
    public <T> T execute(TenantId tenantId, String operation, String idempotencyKey,
            Object payload, Class<T> responseType, Supplier<T> action) {
        requireToken(operation, "operation", 1);
        requireToken(idempotencyKey, "Idempotency-Key", 8);
        String tenant = tenantId.value();
        String payloadHash = Digests.sha256Hex(operation + '|' + json(canonical(payload)));
        T result = transactions.execute(status -> executeInTransaction(tenant, operation,
                idempotencyKey, payloadHash, responseType, action));
        if (result == null) throw new IllegalStateException("idempotent command transaction returned no result");
        return result;
    }

    private <T> T executeInTransaction(String tenantId, String operation, String key, String payloadHash,
            Class<T> responseType, Supplier<T> action) {
        Instant now = clock.instant();
        CommandWrite command = command(tenantId, operation, key, payloadHash, now);
        int inserted = commands.insertIgnore(command);
        IdempotencyCommandMapper.CommandRow row = commands.selectForUpdate(tenantId, operation, key);
        if (row == null) throw new IllegalStateException("idempotency row was not created");
        StoredCommand stored = stored(row);
        if (inserted == 0 && !stored.expiresAt().isAfter(now)) {
            commands.delete(tenantId, operation, key);
            requireOne(commands.insert(command), "重新创建幂等命令");
            inserted = 1;
            stored = new StoredCommand(payloadHash, "PROCESSING", null, now.plus(retention));
        }
        if (!Digests.constantTimeEquals(stored.payloadHash(), payloadHash)) {
            throw new ConflictException("IDEMPOTENCY_PAYLOAD_CONFLICT",
                    "Idempotency-Key was already used with another payload");
        }
        if (inserted == 0) {
            if (!"COMPLETED".equals(stored.state()) || stored.responseJson() == null) {
                throw new ConflictException("IDEMPOTENCY_COMMAND_IN_PROGRESS",
                        "the first command has not completed");
            }
            return read(stored.responseJson(), responseType);
        }

        T response = action.get();
        requireOne(commands.complete(tenantId, operation, key, json(response), format(clock.instant())),
                "保存幂等响应");
        return response;
    }

    private CommandWrite command(String tenantId, String operation, String key, String payloadHash, Instant now) {
        return new CommandWrite(tenantId, operation, key, payloadHash, "PROCESSING", null,
                format(now), format(now), format(now.plus(retention)));
    }

    private static StoredCommand stored(IdempotencyCommandMapper.CommandRow row) {
        return new StoredCommand(row.payloadHash(), row.stateName(), row.responseJson(), Instant.parse(row.expiresAt()));
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JacksonException failure) {
            throw new IllegalArgumentException("idempotency payload cannot be serialized", failure);
        }
    }

    private <T> T read(String value, Class<T> type) {
        try {
            return mapper.readValue(value, type);
        } catch (JacksonException failure) {
            throw new IllegalStateException("stored idempotency response is invalid", failure);
        }
    }

    /** 对对象字段排序，使 JSON 属性顺序变化不会改变 payload hash。 */
    private Object canonical(Object value) {
        Object tree = mapper.convertValue(value, Object.class);
        return canonicalTree(tree);
    }

    private static Object canonicalTree(Object value) {
        if (value instanceof Map<?, ?> source) {
            TreeMap<String, Object> sorted = new TreeMap<>();
            source.forEach((key, item) -> sorted.put(String.valueOf(key), canonicalTree(item)));
            return Collections.unmodifiableMap(sorted);
        }
        if (value instanceof Collection<?> source) {
            List<Object> values = new ArrayList<>(source.size());
            source.forEach(item -> values.add(canonicalTree(item)));
            return List.copyOf(values);
        }
        return value;
    }

    private static void requireToken(String value, String name, int minLength) {
        if (value == null || value.length() < minLength || value.length() > 128
                || !value.matches("[a-zA-Z0-9_.:-]+")) {
            throw new IllegalArgumentException(name + " is invalid");
        }
    }

    private static void requireOne(int affected, String operation) {
        if (affected != 1) throw new IllegalStateException(operation + "的受影响行数不正确");
    }

    private record StoredCommand(String payloadHash, String state, String responseJson, Instant expiresAt) { }
}
