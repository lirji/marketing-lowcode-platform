package com.acme.marketing.control.application;

import static com.acme.marketing.platform.time.SqlTime.format;

import com.acme.marketing.platform.crypto.Digests;
import com.acme.marketing.platform.error.ConflictException;
import com.acme.marketing.platform.identity.TenantId;
import java.time.Clock;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/** Replica-safe idempotency boundary for every mutating control-plane HTTP command. */
@Service
public class ControlCommandExecutor {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final long retentionSeconds;

    public ControlCommandExecutor(JdbcTemplate jdbc, ObjectMapper mapper, Clock clock,
            @Value("${marketing.idempotency.retention-seconds:604800}") long retentionSeconds) {
        if (retentionSeconds < 3_600 || retentionSeconds > 2_592_000) {
            throw new IllegalArgumentException("control idempotency retention must be between one hour and 30 days");
        }
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.clock = clock;
        this.retentionSeconds = retentionSeconds;
    }

    @Transactional
    public <T> T execute(TenantId tenantId, String operation, String idempotencyKey, Object payload,
            Class<T> responseType, Supplier<T> command) {
        requireToken(operation, "operation", "[a-zA-Z0-9_.:-]{3,96}");
        requireToken(idempotencyKey, "idempotency key", "[a-zA-Z0-9_.:-]{8,128}");
        String tenant = tenantId.value();
        String payloadHash = Digests.sha256Hex(json(payload));
        String now = format(clock.instant());
        boolean owner = false;
        String expiresAt = format(clock.instant().plusSeconds(retentionSeconds));
        try {
            jdbc.update("insert into mk_control_command(tenant_id,operation_name,idempotency_key,payload_hash,state_name,response_json,created_at,expires_at) values(?,?,?,?,?,?,?,?)",
                    tenant, operation, idempotencyKey, payloadHash, "PROCESSING", null, now, expiresAt);
            owner = true;
        } catch (DuplicateKeyException duplicate) {
            // The locking read waits for the winning transaction and observes its committed response.
        }
        List<StoredCommand> rows = jdbc.query("select payload_hash,state_name,response_json,expires_at from mk_control_command where tenant_id=? and operation_name=? and idempotency_key=? for update",
                (rs, rowNum) -> new StoredCommand(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)),
                tenant, operation, idempotencyKey);
        if (rows.isEmpty()) {
            throw new ConflictException("CONTROL_COMMAND_LOST", "idempotency record disappeared");
        }
        StoredCommand stored = rows.getFirst();
        if (!owner && stored.expiresAt().compareTo(now) <= 0) {
            jdbc.update("delete from mk_control_command where tenant_id=? and operation_name=? and idempotency_key=?",
                    tenant, operation, idempotencyKey);
            jdbc.update("insert into mk_control_command(tenant_id,operation_name,idempotency_key,payload_hash,state_name,response_json,created_at,expires_at) values(?,?,?,?,?,?,?,?)",
                    tenant, operation, idempotencyKey, payloadHash, "PROCESSING", null, now, expiresAt);
            owner = true;
            stored = new StoredCommand(payloadHash, "PROCESSING", null, expiresAt);
        }
        if (!Digests.constantTimeEquals(stored.payloadHash(), payloadHash)) {
            throw new ConflictException("IDEMPOTENCY_PAYLOAD_CONFLICT",
                    "idempotency key was reused with another command payload");
        }
        if (!owner) {
            if (!"COMPLETED".equals(stored.state()) || stored.responseJson() == null) {
                throw new ConflictException("COMMAND_IN_PROGRESS", "original command is still in progress");
            }
            return read(stored.responseJson(), responseType);
        }
        T response = command.get();
        jdbc.update("update mk_control_command set state_name='COMPLETED',response_json=? where tenant_id=? and operation_name=? and idempotency_key=?",
                json(response), tenant, operation, idempotencyKey);
        return response;
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JacksonException failure) {
            throw new IllegalArgumentException("control command cannot be serialized", failure);
        }
    }

    private <T> T read(String value, Class<T> type) {
        try {
            return mapper.readValue(value, type);
        } catch (JacksonException failure) {
            throw new IllegalStateException("stored control command response is invalid", failure);
        }
    }

    private static void requireToken(String value, String name, String pattern) {
        if (value == null || !value.matches(pattern)) {
            throw new IllegalArgumentException(name + " is invalid");
        }
    }

    private record StoredCommand(String payloadHash, String state, String responseJson, String expiresAt) { }
}
