package com.acme.marketing.platform.idempotency;

import com.acme.marketing.platform.crypto.Digests;
import com.acme.marketing.platform.identity.TenantId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;

public final class IdempotentCommandExecutor {
    private final ConcurrentHashMap<ScopedKey, StoredResponse> responses = new ConcurrentHashMap<>();
    private final Clock clock;
    private final Duration retention;

    public IdempotentCommandExecutor(Clock clock, Duration retention) {
        this.clock = Objects.requireNonNull(clock, "clock");
        if (retention == null || retention.isNegative() || retention.isZero()) {
            throw new IllegalArgumentException("retention must be positive");
        }
        this.retention = retention;
    }

    public <T> T execute(
            TenantId tenantId,
            String idempotencyKey,
            String canonicalPayload,
            Supplier<T> command,
            Function<T, String> encoder,
            Function<String, T> decoder) {
        requireKey(idempotencyKey);
        Objects.requireNonNull(canonicalPayload, "canonicalPayload");
        ScopedKey scopedKey = new ScopedKey(tenantId, idempotencyKey);
        String payloadHash = Digests.sha256Hex(canonicalPayload);
        StoredResponse stored = responses.compute(scopedKey, (ignored, existing) -> {
            Instant now = clock.instant();
            if (existing != null && existing.expiresAt().isAfter(now)) {
                if (!Digests.constantTimeEquals(existing.payloadHash(), payloadHash)) {
                    throw new IdempotencyConflictException();
                }
                return existing;
            }
            T response = command.get();
            return new StoredResponse(payloadHash, encoder.apply(response), now.plus(retention));
        });
        return decoder.apply(stored.response());
    }

    public void evictExpired() {
        Instant now = clock.instant();
        responses.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(now));
    }

    public int size() {
        return responses.size();
    }

    private static void requireKey(String key) {
        if (key == null || !key.matches("[a-zA-Z0-9_.:-]{8,128}")) {
            throw new IllegalArgumentException("invalid idempotency key");
        }
    }

    private record ScopedKey(TenantId tenantId, String key) {
        private ScopedKey {
            Objects.requireNonNull(tenantId, "tenantId");
        }
    }

    private record StoredResponse(String payloadHash, String response, Instant expiresAt) {
    }
}
