package com.acme.marketing.decision;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.acme.marketing.decision.runtime.AudienceMembershipProjection;
import com.acme.marketing.decision.runtime.AudienceMembershipStore;
import com.acme.marketing.decision.runtime.AudienceMembershipStore.StoredMembership;
import com.acme.marketing.platform.crypto.Digests;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class AudienceMembershipProjectionTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void coldStartDoesNotScanAndNegativeEntriesAreBoundedAndRefreshed() {
        var store = mock(AudienceMembershipStore.class);
        when(store.find(anyString(), anyString(), anyString())).thenReturn(Optional.empty());
        var projection = new AudienceMembershipProjection(store, Clock.fixed(NOW, java.time.ZoneOffset.UTC), 2, Duration.ofSeconds(1));
        verifyNoInteractions(store);
        for (String subject : new String[]{"one", "one", "two", "three", "one"}) {
            assertFalse(projection.isMember("t", "a", subject, NOW));
        }
        verify(store, times(2)).find("t", "a", Digests.sha256Hex("one"));
        verify(store, times(4)).find(anyString(), anyString(), anyString());
    }

    @Test
    void expiredCacheCannotGrantMembershipDuringDatabaseFailure() {
        var store = mock(AudienceMembershipStore.class);
        var clock = mock(Clock.class);
        when(clock.instant()).thenReturn(NOW);
        when(store.find(anyString(), anyString(), anyString())).thenReturn(Optional.of(row(true, 1)));
        var projection = new AudienceMembershipProjection(store, clock);
        assertTrue(projection.isMember("t", "a", "subject", NOW));
        when(clock.instant()).thenReturn(NOW.plusSeconds(1));
        when(store.find(anyString(), anyString(), anyString())).thenThrow(new IllegalStateException("offline"));
        assertThrows(IllegalStateException.class, () -> projection.isMember("t", "a", "subject", NOW.plusSeconds(1)));
    }

    @Test
    void concurrentOldReadCannotRefillCacheAfterLocalRevocation() throws Exception {
        var store = mock(AudienceMembershipStore.class);
        var database = new AtomicReference<>(row(true, 1));
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(store.find(anyString(), anyString(), anyString())).thenAnswer(invocation -> {
            var observed = database.get();
            entered.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return Optional.of(observed);
        });
        doAnswer(invocation -> { database.set(row(false, 2)); return null; })
                .when(store).saveIfNewer(anyString(), anyString(), anyString(), eq(false), eq(2L), any(), any());
        var projection = new AudienceMembershipProjection(store, Clock.fixed(NOW, java.time.ZoneOffset.UTC));
        try (var threads = Executors.newFixedThreadPool(2)) {
            var read = threads.submit(() -> projection.isMember("t", "a", "subject", NOW));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var write = threads.submit(() -> projection.update("t", "a", "subject", false, 2, NOW.plusSeconds(100)));
            release.countDown();
            read.get(5, TimeUnit.SECONDS);
            write.get(5, TimeUnit.SECONDS);
            assertFalse(projection.isMember("t", "a", "subject", NOW));
        } finally { release.countDown(); }
    }

    private static StoredMembership row(boolean member, long version) {
        return new StoredMembership("t", "a", Digests.sha256Hex("subject"), member, version, NOW.plusSeconds(100), NOW);
    }
}
