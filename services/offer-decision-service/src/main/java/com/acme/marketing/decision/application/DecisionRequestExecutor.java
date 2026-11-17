package com.acme.marketing.decision.application;

import com.acme.marketing.decision.application.DecisionCommandRepository.StoredCommand;
import com.acme.marketing.platform.crypto.Digests;
import com.acme.marketing.platform.error.ConflictException;
import com.acme.marketing.platform.error.NotFoundException;
import static com.acme.marketing.platform.time.SqlTime.format;
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
            -- Lua不回滚已执行写入，先校验所有索引类型再修改完成状态。
            for i=2,3 do
              local kind = redis.call('TYPE', KEYS[i]).ok
              if kind ~= 'none' and kind ~= 'hash' then return -2 end
            end
            redis.call('HSET', KEYS[1], 'state', 'COMPLETED', 'responseJson', ARGV[2], 'traceJson', ARGV[4])
            for i=2,3 do
              if i == 2 or ARGV[6] == '1' then
                local previous = redis.call('HGET', KEYS[i], 'sort')
                if not previous or previous < ARGV[5] then
                  redis.call('HSET', KEYS[i], 'sort', ARGV[5], 'traceJson', ARGV[4])
                  redis.call('EXPIRE', KEYS[i], ARGV[3])
                end
              end
            end
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
        // 幂等行仍由唯一键与 FOR UPDATE 串行化；人群回源需看到最新提交的撤销，不能沿用旧快照。
        this.transactions.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.redis = redisProvider.getIfAvailable();
        this.store = Store.valueOf(store.toUpperCase(Locale.ROOT));
        if (this.store == Store.REDIS && this.redis == null) {
            throw new IllegalStateException("Redis idempotency store is enabled but unavailable");
        }
    }

    public DecisionApplicationService.DecisionResponse execute(
            TenantScope scope, DecisionApplicationService.DecisionRequest request) {
        // 回放同样需要当前权限和范围，不能绕过已撤销的调用资格。
        scope.requirePermission("decision:evaluate");
        scope.requireOrganization(request.organizationId());
        request.cart().lines().stream().map(line -> line.shopId()).distinct().forEach(scope::requireShop);
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
            decisions.validateBundleReplay(scope, request);
            return read(stored.responseJson());
        }
        DecisionApplicationService.DecisionResponse response = decisions.evaluate(scope, request);
        commandRepository.complete(tenantId, request.idempotencyKey(), json(response));
        AutomaticDecisionTrace trace = AutomaticDecisionTrace.from(scope, request, response, RETENTION_SECONDS);
        commandRepository.saveTrace(tenantId, request.idempotencyKey(), json(trace), Digests.sha256Hex(request.requestId()),
                request.orderId().isBlank() ? null : Digests.sha256Hex(request.orderId()), traceSort(trace));
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
            decisions.validateBundleReplay(scope, request);
            return read(responseJson);
        }
        try {
            DecisionApplicationService.DecisionResponse response = decisions.evaluate(scope, request);
            String responseJson = json(response);
            AutomaticDecisionTrace trace = AutomaticDecisionTrace.from(scope, request, response, RETENTION_SECONDS);
            Long completed = redis.execute(COMPLETE, List.of(key,
                            traceKey(scope, false, request.requestId()), traceKey(scope, true, request.orderId())),
                    payloadHash, responseJson, Long.toString(RETENTION_SECONDS), json(trace), traceSort(trace),
                    request.orderId().isBlank() ? "0" : "1");
            if (completed == null || completed != 1) {
                throw new ConflictException("IDEMPOTENCY_OWNERSHIP_LOST", "Redis command ownership was lost");
            }
            return response;
        } catch (RuntimeException failure) {
            redis.execute(RELEASE, List.of(key), payloadHash);
            throw failure;
        }
    }

    /** 当前租户的自动决策摘要；订单查询返回最近完成的决策，而非订单履约结果。 */
    public AutomaticDecisionTrace trace(TenantScope scope, String identity, boolean byOrder) {
        scope.requirePermission("trace:read");
        if (identity == null || identity.isBlank()) throw new IllegalArgumentException("轨迹查询标识不能为空");
        String encoded;
        if (store == Store.REDIS) {
            Object value = redis.opsForHash().get(traceKey(scope, byOrder, identity), "traceJson");
            encoded = value instanceof String text ? text : null;
        } else {
            String hash = Digests.sha256Hex(identity);
            encoded = commandRepository.findTrace(scope.tenantId().value(), byOrder ? null : hash,
                    byOrder ? hash : null, clock.instant()).orElse(null);
        }
        if (encoded == null) throw traceNotFound();
        AutomaticDecisionTrace result = objectMapper.readValue(encoded, AutomaticDecisionTrace.class);
        if (!clock.instant().isBefore(result.expiresAt())
                || !identity.equals(byOrder ? result.orderId() : result.requestId())) throw traceNotFound();
        return result;
    }

    private static String traceKey(TenantScope scope, boolean byOrder, String identity) {
        return "mk:decision:trace:{" + scope.tenantId().value() + "}:" + (byOrder ? "order:" : "request:") + Digests.sha256Hex(identity);
    }
    private static String traceSort(AutomaticDecisionTrace trace) { return format(trace.createdAt()) + ':' + trace.traceId(); }
    private static NotFoundException traceNotFound() { return new NotFoundException("TRACE_NOT_FOUND", "没有可查的自动决策轨迹，记录可能已过期或由旧版本生成"); }

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
