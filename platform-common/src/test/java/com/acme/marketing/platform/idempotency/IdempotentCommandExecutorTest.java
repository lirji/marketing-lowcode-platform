package com.acme.marketing.platform.idempotency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.acme.marketing.platform.identity.TenantId;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class IdempotentCommandExecutorTest {
    @Test
    void returnsOriginalResponseAndRejectsChangedPayload() {
        IdempotentCommandExecutor executor = new IdempotentCommandExecutor(Clock.systemUTC(), Duration.ofHours(1));
        AtomicInteger calls = new AtomicInteger();

        int first = executor.execute(new TenantId("tenant-a"), "command-1234", "payload-a",
                calls::incrementAndGet, Object::toString, Integer::valueOf);
        int repeated = executor.execute(new TenantId("tenant-a"), "command-1234", "payload-a",
                calls::incrementAndGet, Object::toString, Integer::valueOf);

        assertEquals(1, first);
        assertEquals(1, repeated);
        assertEquals(1, calls.get());
        assertThrows(IdempotencyConflictException.class,
                () -> executor.execute(new TenantId("tenant-a"), "command-1234", "payload-b",
                        calls::incrementAndGet, Object::toString, Integer::valueOf));
    }
}
