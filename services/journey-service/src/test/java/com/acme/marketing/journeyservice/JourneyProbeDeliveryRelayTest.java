package com.acme.marketing.journeyservice;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.acme.marketing.journeyservice.application.JourneyProbeDeliveryWorker;
import com.acme.marketing.journeyservice.infrastructure.JourneyProbeDeliveryRelay;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** 验证独立relay只有一批在途，失败后仍唤醒，停机等待在途而不继续领取。 */
class JourneyProbeDeliveryRelayTest {
    @Test void relayContinuesAfterFailureAndStopsWithoutConcurrentOrLeakedThread() throws Exception {
        var worker = mock(JourneyProbeDeliveryWorker.class); var meters = new SimpleMeterRegistry();
        var observations = ObservationRegistry.create(); var calls = new AtomicInteger();
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        when(worker.runOnce()).thenAnswer(invocation -> {
            assertEquals("journey-probe-delivery", Thread.currentThread().getName());
            assertNull(com.acme.marketing.platform.web.TenantContextHolder.current().orElse(null));
            assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            int count = calls.incrementAndGet();
            if (count == 1) throw new IllegalStateException("test recoverable failure");
            entered.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)); return true;
        });
        var relay = new JourneyProbeDeliveryRelay(worker, observations, meters);
        relay.start(); relay.start();
        try (var stopping = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var stop = stopping.submit(() -> { relay.stop(); });
            // stop先改变领取标志，已经进入的一批保持有界等待，不能在后台再提交第二批。
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (relay.isRunning() && System.nanoTime() < deadline) Thread.sleep(10);
            assertFalse(relay.isRunning()); assertFalse(stop.isDone());
            release.countDown(); stop.get(5, TimeUnit.SECONDS);
            assertEquals(2, calls.get()); assertEquals(1, meters.get("marketing.journey.probe.relay.failure").counter().count());
            assertNull(observations.getCurrentObservation());
            assertTrue(Thread.getAllStackTraces().keySet().stream().noneMatch(thread -> thread.isAlive() && thread.getName().equals("journey-probe-delivery")));
        } finally { release.countDown(); relay.stop(); meters.close(); }
    }
}
