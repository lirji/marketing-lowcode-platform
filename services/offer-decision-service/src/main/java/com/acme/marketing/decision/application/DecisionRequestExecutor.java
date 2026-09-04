package com.acme.marketing.decision.application;

import static com.acme.marketing.platform.time.SqlTime.format;

import com.acme.marketing.platform.crypto.Digests;
import com.acme.marketing.platform.error.ConflictException;
import com.acme.marketing.platform.identity.TenantScope;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
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
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final DecisionApplicationService decisions;
    private final TransactionTemplate transactions;
    private final StringRedisTemplate redis;
    private final Store store;

    public DecisionRequestExecutor(JdbcTemplate jdbc, ObjectMapper mapper, Clock clock,
            DecisionApplicationService decisions, PlatformTransactionManager transactionManager,
            ObjectProvider<StringRedisTemplate> redisProvider,
            @Value("${marketing.decision.idempotency-store:JDBC}") String store) {
        this.jdbc = jdbc;
        this.mapper = mapper;
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
        jdbc.update("delete from mk_decision_command where tenant_id=? and idempotency_key=? and expires_at<=?",
                tenantId, request.idempotencyKey(), format(clock.instant()));
        boolean owner = false;
        try {
            jdbc.update("insert into mk_decision_command(tenant_id,idempotency_key,payload_hash,state_name,response_json,created_at,expires_at) values(?,?,?,?,?,?,?)",
                    tenantId, request.idempotencyKey(), payloadHash, "PROCESSING", null,
                    format(clock.instant()), format(clock.instant().plusSeconds(RETENTION_SECONDS)));
            owner = true;
        } catch (DuplicateKeyException duplicate) {
            // The locked read below waits for the winning transaction and returns its byte-equivalent response.
        }
        List<StoredCommand> rows = jdbc.query("select payload_hash,state_name,response_json from mk_decision_command where tenant_id=? and idempotency_key=? for update",
                (rs, rowNum) -> new StoredCommand(rs.getString(1), rs.getString(2), rs.getString(3)),
                tenantId, request.idempotencyKey());
        if (rows.isEmpty()) throw new ConflictException("DECISION_COMMAND_LOST", "idempotency row disappeared");
        StoredCommand stored = rows.getFirst();
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
        jdbc.update("update mk_decision_command set state_name='COMPLETED',response_json=? where tenant_id=? and idempotency_key=?",
                json(response), tenantId, request.idempotencyKey());
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
        try { return mapper.writeValueAsString(value); }
        catch (JacksonException failure) { throw new IllegalArgumentException("decision cannot be serialized", failure); }
    }

    private DecisionApplicationService.DecisionResponse read(String value) {
        try { return mapper.readValue(value, DecisionApplicationService.DecisionResponse.class); }
        catch (JacksonException failure) { throw new IllegalStateException("stored decision is invalid", failure); }
    }

    private record StoredCommand(String payloadHash, String state, String responseJson) { }
    private enum Store { JDBC, REDIS }
}
