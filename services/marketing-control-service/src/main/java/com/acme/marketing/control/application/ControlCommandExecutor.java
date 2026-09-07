package com.acme.marketing.control.application;

import static com.acme.marketing.platform.time.SqlTime.format;

import com.acme.marketing.platform.crypto.Digests;
import com.acme.marketing.platform.error.ConflictException;
import com.acme.marketing.platform.identity.TenantId;
import java.time.Clock;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/** 控制面写命令的多副本安全幂等边界。 */
@Service
public class ControlCommandExecutor {
    private final ControlRepository repository;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final long retentionSeconds;

    public ControlCommandExecutor(ControlRepository repository, ObjectMapper mapper, Clock clock,
            @Value("${marketing.idempotency.retention-seconds:604800}") long retentionSeconds) {
        if (retentionSeconds < 3_600 || retentionSeconds > 2_592_000) {
            throw new IllegalArgumentException("control idempotency retention must be between one hour and 30 days");
        }
        this.repository = repository;
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
        String expiresAt = format(clock.instant().plusSeconds(retentionSeconds));
        boolean owner = repository.tryBeginCommand(new ControlRepository.CommandWrite(tenant, operation,
                idempotencyKey, payloadHash, "PROCESSING", null, now, expiresAt));
        ControlRepository.CommandRow stored = repository.findCommandForUpdate(tenant, operation, idempotencyKey)
                .orElseThrow(() -> new ConflictException("CONTROL_COMMAND_LOST", "idempotency record disappeared"));
        if (!owner && stored.expiresAt().compareTo(now) <= 0) {
            repository.deleteCommand(tenant, operation, idempotencyKey);
            owner = repository.tryBeginCommand(new ControlRepository.CommandWrite(tenant, operation,
                    idempotencyKey, payloadHash, "PROCESSING", null, now, expiresAt));
            if (!owner) throw new ConflictException("CONTROL_COMMAND_RACE", "expired command was reclaimed");
            stored = new ControlRepository.CommandRow(payloadHash, "PROCESSING", null, expiresAt);
        }
        if (!Digests.constantTimeEquals(stored.payloadHash(), payloadHash)) {
            throw new ConflictException("IDEMPOTENCY_PAYLOAD_CONFLICT",
                    "idempotency key was reused with another command payload");
        }
        if (!owner) {
            if (!"COMPLETED".equals(stored.stateName()) || stored.responseJson() == null) {
                throw new ConflictException("COMMAND_IN_PROGRESS", "original command is still in progress");
            }
            return read(stored.responseJson(), responseType);
        }
        T response = command.get();
        repository.completeCommand(tenant, operation, idempotencyKey, json(response));
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

}
