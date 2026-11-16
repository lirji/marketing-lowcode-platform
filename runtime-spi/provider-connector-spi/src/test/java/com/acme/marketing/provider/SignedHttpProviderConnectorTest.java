package com.acme.marketing.provider;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SignedHttpProviderConnectorTest {
    private final MutableClock clock = new MutableClock();
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicInteger status = new AtomicInteger(503);
    private final ExecutorService executor = Executors.newFixedThreadPool(4);
    private HttpServer server;
    private SignedHttpProviderConnector connector;
    private volatile CountDownLatch entered;
    private volatile CountDownLatch release;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(executor);
        server.createContext("/send", exchange -> {
            calls.incrementAndGet();
            int response = status.get();
            if (entered != null) {
                entered.countDown();
                try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            }
            exchange.getRequestBody().readAllBytes();
            assertNotNull(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
            assertNotNull(exchange.getRequestHeaders().getFirst("X-Marketing-Signature"));
            exchange.sendResponseHeaders(response, -1);
            exchange.close();
        });
        server.start();
        connector = new SignedHttpProviderConnector(HttpClient.newHttpClient(),
                new HmacRequestSigner("test-secret-that-is-long-enough".getBytes(StandardCharsets.UTF_8)),
                clock, ignored -> {});
    }

    @AfterEach
    void stop() {
        if (release != null) release.countDown();
        server.stop(0);
        executor.shutdownNow();
        Thread.interrupted();
    }

    @Test
    void opensThenRecoversAfterCooldown() {
        ProviderPolicy policy = policy(100, 1, 1);
        assertEquals(ProviderResult.Status.RETRY_EXHAUSTED, send(policy).status());
        status.set(202);
        assertEquals(ProviderResult.Status.CIRCUIT_OPEN, send(policy).status());
        assertEquals(1, calls.get());
        clock.advance(Duration.ofSeconds(30));
        assertEquals(ProviderResult.Status.ACCEPTED, send(policy).status());
        assertEquals(ProviderResult.Status.ACCEPTED, send(policy).status());
        assertEquals(3, calls.get());
    }

    @Test
    void onlyOneHalfOpenProbeAndFailedProbeRestartsCooldown() throws Exception {
        ProviderPolicy policy = policy(100, 1, 1);
        send(policy);
        clock.advance(Duration.ofSeconds(30));
        entered = new CountDownLatch(1);
        release = new CountDownLatch(1);
        var probe = executor.submit(() -> send(policy));
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        assertEquals(ProviderResult.Status.CIRCUIT_OPEN, send(policy).status());
        release.countDown();
        assertEquals(ProviderResult.Status.RETRY_EXHAUSTED, probe.get(5, TimeUnit.SECONDS).status());
        assertEquals(ProviderResult.Status.CIRCUIT_OPEN, send(policy).status());
        assertEquals(2, calls.get());
        clock.advance(Duration.ofSeconds(30));
        status.set(200);
        assertEquals(ProviderResult.Status.ACCEPTED, send(policy).status());
    }

    @Test
    void lateSuccessCannotCloseNewerCircuit() throws Exception {
        ProviderPolicy policy = policy(100, 1, 1);
        status.set(200);
        entered = new CountDownLatch(1);
        CountDownLatch held = new CountDownLatch(1);
        release = held;
        var old = executor.submit(() -> send(policy));
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        entered = null;
        status.set(503);
        assertEquals(ProviderResult.Status.RETRY_EXHAUSTED, send(policy).status());
        held.countDown();
        assertEquals(ProviderResult.Status.ACCEPTED, old.get(5, TimeUnit.SECONDS).status());
        assertEquals(ProviderResult.Status.CIRCUIT_OPEN, send(policy).status());
    }

    @Test
    void limitsEveryAttemptAndRefillsWithoutCrossTenantOrChannelInterference() {
        ProviderPolicy policy = policy(1, 10, 3);
        ProviderResult first = send(policy);
        assertEquals(ProviderResult.Status.RATE_LIMITED, first.status());
        assertEquals(1, first.attempts());
        assertEquals(1, calls.get());
        status.set(200);
        assertEquals(ProviderResult.Status.ACCEPTED, connector.send(request("tenant-b", "SMS"), policy).status());
        assertEquals(ProviderResult.Status.ACCEPTED, connector.send(request("tenant-a", "EMAIL"), policy).status());
        clock.advance(Duration.ofSeconds(1));
        assertEquals(ProviderResult.Status.ACCEPTED, send(policy).status());
        assertEquals(4, calls.get());
    }

    @Test
    void subMillisecondRejectedCallsDoNotDiscardRefillTime() {
        status.set(200);
        ProviderPolicy policy = policy(1, 10, 1);
        assertEquals(ProviderResult.Status.ACCEPTED, send(policy).status());
        for (int i = 0; i < 1999; i++) {
            clock.advance(Duration.ofNanos(500_000));
            assertEquals(ProviderResult.Status.RATE_LIMITED, send(policy).status());
        }
        // 多给一次微小间隔，避免二进制小数累计在边界处产生舍入差异。
        clock.advance(Duration.ofMillis(1));
        assertEquals(ProviderResult.Status.ACCEPTED, send(policy).status());
        assertEquals(2, calls.get());
    }

    @Test
    void openCircuitDoesNotBlockOtherTenantOrChannel() {
        ProviderPolicy policy = policy(100, 1, 1);
        send(policy);
        status.set(200);
        assertEquals(ProviderResult.Status.ACCEPTED, connector.send(request("tenant-b", "SMS"), policy).status());
        assertEquals(ProviderResult.Status.ACCEPTED, connector.send(request("tenant-a", "EMAIL"), policy).status());
        assertEquals(ProviderResult.Status.CIRCUIT_OPEN, send(policy).status());
    }

    @Test
    void interruptionStopsRetriesAndInvalidCooldownIsRejected() {
        Thread.currentThread().interrupt();
        ProviderResult result = send(policy(100, 5, 3));
        assertEquals("INTERRUPTED", result.code());
        assertEquals(0, result.attempts());
        assertEquals(0, calls.get());
        assertTrue(Thread.currentThread().isInterrupted());
        assertThrows(IllegalArgumentException.class, () -> new ProviderPolicy(Duration.ofSeconds(1), 1,
                Duration.ZERO, Set.of(503), 1, 1, Duration.ZERO));
    }

    private ProviderResult send(ProviderPolicy policy) { return connector.send(request("tenant-a", "SMS"), policy); }
    private ProviderRequest request(String tenant, String channel) {
        return new ProviderRequest(tenant, "contact-1", channel, "subject-1", "tpl-v1",
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/send"), Map.of(), clock.instant());
    }
    private static ProviderPolicy policy(int rate, int threshold, int attempts) {
        return new ProviderPolicy(Duration.ofSeconds(5), attempts, Duration.ZERO, Set.of(503), rate, threshold,
                Duration.ofSeconds(30));
    }
    private static final class MutableClock extends Clock {
        private volatile Instant now = Instant.parse("2026-01-01T00:00:00Z");
        void advance(Duration duration) { now = now.plus(duration); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
