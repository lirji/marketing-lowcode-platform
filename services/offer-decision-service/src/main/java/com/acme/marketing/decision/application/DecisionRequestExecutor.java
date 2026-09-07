package com.acme.marketing.decision.application;

import com.acme.marketing.decision.application.DecisionCommandRepository.StoredCommand;
import com.acme.marketing.platform.crypto.Digests;
import com.acme.marketing.platform.error.ConflictException;
import com.acme.marketing.platform.identity.TenantScope;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/** Database-backed idempotency boundary shared by every decision replica. */
@Service
public class DecisionRequestExecutor {
    private static final long RETENTION_SECONDS = 86_400;
    private static final DefaultRedisScript<Long> CLAIM = new DefaultRedisScript<>("""
            if redis.call('EXISTS', KEYS[1]) == 1 then
              if redis.call('HGET', KEYS[1], 'payloadHash') ~= ARGV[1] then return -1 end
              if redis.call('HGET', KEYS[1], 'state') == 'COMPLETED' then return 2 end
              return 0
            end
            redis.call('HSET', KEYS[1], 'payloadHash', ARGV[1], 'state', 'PROCESSING')
            redis.call('EXPIRE', KEYS[1], ARGV[2])
            return 1
            """, Long.class);
    private static final DefaultRedisScript<Long> COMPLETE = new DefaultRedisScript<>("""
            if redis.call('HGET', KEYS[1], 'payloadHash') ~= ARGV[1] then return 0 end
            if redis.call('HGET', KEYS[1], 'state') ~= 'PROCESSING' then return 0 end
            redis.call('HSET', KEYS[1], 'state', 'COMPLETED', 'responseJson', ARGV[2])
            redis.call('EXPIRE', KEYS[1], ARGV[3])
            return 1
            """, Long.class);
    private static final DefaultRedisScript<Long> RELEASE = new DefaultRedisScript<>("""
            if redis.call('HGET', KEYS[1], 'payloadHash') == ARGV[1]
              and redis.call('HGET', KEYS[1], 'state') == 'PROCESSING' then
              return redis.call('DEL', KEYS[1])
            end
            return 0
            """, Long.class);
    private final DecisionCommandRepository commandRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final DecisionApplicationService decisions;
    private final TransactionTemplate transactions;
    private final StringRedisTemplate redis;
    private final Store store;

    public DecisionRequestExecutor(DecisionCommandRepository commandRepository, ObjectMapper objectMapper, Clock clock,
            DecisionApplicationService decisions, PlatformTransactionManager transactionManager,
            ObjectProvider<StringRedisTemplate> redisProvider,
            @Value("${marketing.decision.idempotency-store:JDBC}") String store) {
        this.commandRepository = commandRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.decisions = decisions;
        this.transactions = new TransactionTemplate(transactionManager);
        this.redis = redisProvider.getIfAvailable();
        this.store = Store.valueOf(store.toUpperCase(Locale.ROOT));
        if (this.store == Store.REDIS && this.redis == null) {
            throw new IllegalStateException("Redis idempotency store is enabled but unavailable");
        }
    }

    public DecisionApplicationService.DecisionResponse execute(
            TenantScope scope, DecisionApplicationService.DecisionRequest request) {
        if (!request.idempotencyKey().matches("[a-zA-Z0-9_.:-]{8,128}")) {
            throw new IllegalArgumentException("decision idempotency key is invalid");
        }
        return store == Store.REDIS ? executeRedis(scope, request) : executeJdbc(scope, request);
    }

    private DecisionApplicationService.DecisionResponse executeJdbc(
            TenantScope scope, DecisionApplicationService.DecisionRequest request) {
        DecisionApplicationService.DecisionResponse response = transactions.execute(ignored -> executeJdbcNow(scope, request));
        if (response == null) throw new IllegalStateException("decision transaction returned no response");
        return response;
    }

    private DecisionApplicationService.DecisionResponse executeJdbcNow(
            TenantScope scope, DecisionApplicationService.DecisionRequest request) {
        String tenantId = scope.tenantId().value();
        String payloadHash = Digests.sha256Hex(json(request));
        commandRepository.deleteExpired(tenantId, request.idempotencyKey(), clock.instant());
        boolean owner = commandRepository.tryClaim(tenantId, request.idempotencyKey(), payloadHash,
                clock.instant(), clock.instant().plusSeconds(RETENTION_SECONDS));
        // 加锁读取会等待赢得唯一键竞争的事务完成，从而重放字节等价响应。
        StoredCommand stored = commandRepository.lock(tenantId, request.idempotencyKey())
                .orElseThrow(() -> new ConflictException("DECISION_COMMAND_LOST", "idempotency row disappeared"));
        if (!stored.payloadHash().equals(payloadHash)) {
            throw new ConflictException("IDEMPOTENCY_PAYLOAD_CONFLICT",
                    "idempotency key was used with another decision request");
        }
        if (!owner) {
            if (!"COMPLETED".equals(stored.state()) || stored.responseJson() == null) {
                throw new ConflictException("COMMAND_IN_PROGRESS", "original decision is still in progress");
            }
            return read(stored.responseJson());
        }
        DecisionApplicationService.DecisionResponse response = decisions.evaluate(scope, request);
        commandRepository.complete(tenantId, request.idempotencyKey(), json(response));
        return response;
    }

    private DecisionApplicationService.DecisionResponse executeRedis(
            TenantScope scope, DecisionApplicationService.DecisionRequest request) {
        String payloadHash = Digests.sha256Hex(json(request));
        String key = "mk:decision:command:{" + scope.tenantId().value() + "}:" + request.idempotencyKey();
        Long claimed = redis.execute(CLAIM, List.of(key), payloadHash, Long.toString(RETENTION_SECONDS));
        if (claimed == null) throw new ConflictException("IDEMPOTENCY_STORE_UNAVAILABLE", "Redis returned no result");
        if (claimed == -1) {
            throw new ConflictException("IDEMPOTENCY_PAYLOAD_CONFLICT",
                    "idempotency key was used with another decision request");
        }
        if (claimed == 0) throw new ConflictException("COMMAND_IN_PROGRESS", "original decision is still in progress");
        if (claimed == 2) {
            Object stored = redis.opsForHash().get(key, "responseJson");
            if (!(stored instanceof String responseJson) || responseJson.isBlank()) {
                throw new ConflictException("IDEMPOTENCY_RESPONSE_MISSING", "completed Redis command has no response");
            }
            return read(responseJson);
        }
        try {
            DecisionApplicationService.DecisionResponse response = decisions.evaluate(scope, request);
            String responseJson = json(response);
            Long completed = redis.execute(COMPLETE, List.of(key), payloadHash, responseJson,
                    Long.toString(RETENTION_SECONDS));
            if (completed == null || completed != 1) {
                throw new ConflictException("IDEMPOTENCY_OWNERSHIP_LOST", "Redis command ownership was lost");
            }
            return response;
        } catch (RuntimeException failure) {
            redis.execute(RELEASE, List.of(key), payloadHash);
            throw failure;
        }
    }

    private String json(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (JacksonException failure) { throw new IllegalArgumentException("decision cannot be serialized", failure); }
    }

    private DecisionApplicationService.DecisionResponse read(String value) {
        try { return objectMapper.readValue(value, DecisionApplicationService.DecisionResponse.class); }
        catch (JacksonException failure) { throw new IllegalStateException("stored decision is invalid", failure); }
    }

    private enum Store { JDBC, REDIS }
}
