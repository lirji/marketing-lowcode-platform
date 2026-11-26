package com.acme.marketing.decision;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.acme.marketing.decision.application.DecisionApplicationService;
import com.acme.marketing.decision.application.DecisionApplicationService.DecisionRequest;
import com.acme.marketing.decision.application.DecisionApplicationService.DecisionResponse;
import com.acme.marketing.decision.application.DecisionCommandRepository;
import com.acme.marketing.decision.application.DecisionRequestExecutor;
import com.acme.marketing.decision.model.Cart;
import com.acme.marketing.decision.model.CartLine;
import com.acme.marketing.decision.model.Money;
import com.acme.marketing.decision.pricing.PricingEngine;
import com.acme.marketing.platform.crypto.Digests;
import com.acme.marketing.platform.error.ForbiddenException;
import com.acme.marketing.platform.error.NotFoundException;
import com.acme.marketing.platform.identity.TenantId;
import com.acme.marketing.platform.identity.TenantScope;
import com.acme.marketing.testsupport.MySqlIntegrationTest;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.GenericContainer;
import tools.jackson.databind.ObjectMapper;

/** 两种既有权威存储均用真实隔离实例验证，不借共享Redis或数据库做故障注入。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AutomaticDecisionTraceIntegrationTest extends MySqlIntegrationTest {
    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
    private static LettuceConnectionFactory connections;
    private static StringRedisTemplate redis;
    @org.springframework.boot.test.web.server.LocalServerPort int port;
    @Autowired DecisionCommandRepository commands;
    @Autowired PlatformTransactionManager tx;
    @Autowired ObjectMapper mapper;
    private final Cart cart = new Cart("CNY", List.of(new CartLine("line", "sku", "shop", "", "", 1,
            new Money("CNY", 100), Money.zero("CNY"), false)));

    @BeforeAll static void start() {
        REDIS.start(); connections = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        connections.afterPropertiesSet(); connections.start(); redis = new StringRedisTemplate(connections);
    }
    @AfterAll static void stop() { connections.destroy(); REDIS.stop(); }

    @Test void jdbcAndRedisCompleteOnceAndExposeOnlySanitizedOriginalTrace() {
        for (String mode : List.of("JDBC", "REDIS")) {
            var f = fixture(mode);
            var request = request("request-" + UUID.randomUUID(), "order", f.time.now);
            var first = f.executor.execute(f.scope, request);
            var trace = f.executor.trace(f.scope, request.requestId(), false);
            assertTrue(first.businessNo().matches("MKT-QTE-[0-9]{6}-[0-9]{10}"));
            assertEquals(trace, f.executor.quoteTrace(f.scope, first.businessNo()));
            assertEquals(trace, f.executor.quoteTrace(f.scope, first.quoteId()));
            assertThrows(NotFoundException.class, () -> f.executor.quoteTrace(scope("other-tenant", "trace:read"), first.businessNo()));
            f.time.now = f.time.now.plusSeconds(1);
            assertEquals(first, f.executor.execute(f.scope, request));
            assertEquals(trace, f.executor.trace(f.scope, request.requestId(), false));
            assertEquals(trace.traceId(), f.executor.trace(f.scope, "order", true).traceId());
            assertFalse(mapper.writeValueAsString(trace).contains("sensitive-subject"));
            assertFalse(mapper.writeValueAsString(trace).contains("signed-secret-token"));
            assertEquals(1, trace.events().size());
            verify(f.decisions, times(1)).evaluate(any(), any());
        }
    }

    @Test void traceRequiresCurrentPermissionTenantAndUnexpiredWindow() {
        for (String mode : List.of("JDBC", "REDIS")) {
            var f = fixture(mode); var request = request("request-" + UUID.randomUUID(), "order", f.time.now);
            f.executor.execute(f.scope, request);
            assertThrows(ForbiddenException.class, () -> f.executor.trace(scope(f.scope.tenantId().value(), "decision:evaluate"), request.requestId(), false));
            assertThrows(NotFoundException.class, () -> f.executor.trace(scope("other-tenant", "trace:read"), request.requestId(), false));
            assertThrows(ForbiddenException.class, () -> f.executor.execute(scope(f.scope.tenantId().value(), "trace:read"), request));
            f.time.now = f.time.now.plusSeconds(86_400);
            assertThrows(NotFoundException.class, () -> f.executor.trace(f.scope, request.requestId(), false));
        }
    }

    @Test void lateOlderCompletionCannotReplaceNewerOrderObservation() throws Exception {
        for (String mode : List.of("JDBC", "REDIS")) {
            var f = fixture(mode); Instant oldTime = f.time.now;
            var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
            var older = request("old-"+UUID.randomUUID(), "shared-order", oldTime);
            var newer = request("new-"+UUID.randomUUID(), "shared-order", oldTime.plusSeconds(1));
            doAnswer(call -> {
                entered.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)); return response(older, oldTime);
            }).when(f.decisions).evaluate(any(), eq(older));
            doReturn(response(newer, oldTime.plusSeconds(1))).when(f.decisions).evaluate(any(), eq(newer));
            try (var pool = Executors.newSingleThreadExecutor()) {
                var old = pool.submit(() -> f.executor.execute(f.scope, older));
                assertTrue(entered.await(5, TimeUnit.SECONDS)); f.executor.execute(f.scope, newer); release.countDown(); old.get(5, TimeUnit.SECONDS);
            } finally { release.countDown(); }
            assertEquals(newer.requestId(), f.executor.trace(f.scope, "shared-order", true).requestId());
            assertEquals(older.requestId(), f.executor.trace(f.scope, older.requestId(), false).requestId());
        }
    }

    @Test void historicalCompletedCommandsReplayWithoutInventedTrace() {
        var f = fixture("JDBC"); var request = request("legacy-"+UUID.randomUUID(), "legacy-order", f.time.now);
        var original = response(request, f.time.now);
        new org.springframework.transaction.support.TransactionTemplate(tx).executeWithoutResult(status -> {
            commands.tryClaim(f.scope.tenantId().value(), request.idempotencyKey(), Digests.sha256Hex(mapper.writeValueAsString(request)), f.time.now, f.time.now.plusSeconds(86_400));
            commands.complete(f.scope.tenantId().value(), request.idempotencyKey(), mapper.writeValueAsString(original));
        });
        assertEquals(original, f.executor.execute(f.scope, request));
        assertThrows(NotFoundException.class, () -> f.executor.trace(f.scope, request.requestId(), false));
        verify(f.decisions, never()).evaluate(any(), any());
    }

    @Test void controllerReadsPersistedTraceAndAppliesPermissionAndTenant() throws Exception {
        var f = fixture("JDBC"); var request = request("route-"+UUID.randomUUID(), "route-order", f.time.now);
        f.executor.execute(f.scope, request);
        try (var http = java.net.http.HttpClient.newHttpClient()) {
            String url = "http://127.0.0.1:"+port+"/api/v1/decisions/traces/requests/"+request.requestId();
            for (var check : List.of(Map.entry("trace:read", 200), Map.entry("decision:evaluate", 403))) {
                var call = java.net.http.HttpRequest.newBuilder(java.net.URI.create(url)).header("X-Dev-Tenant-Id", f.scope.tenantId().value())
                        .header("X-Dev-Actor-Id", "operator").header("X-Dev-Permissions", check.getKey()).GET().build();
                var response = http.send(call, java.net.http.HttpResponse.BodyHandlers.ofString());
                assertEquals(check.getValue(), response.statusCode()); assertFalse(response.body().contains("signed-secret-token"));
            }
            var other = java.net.http.HttpRequest.newBuilder(java.net.URI.create(url)).header("X-Dev-Tenant-Id", "other")
                    .header("X-Dev-Actor-Id", "operator").header("X-Dev-Permissions", "trace:read").GET().build();
            assertEquals(404, http.send(other, java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode());
        }
    }

    @Test void redisIndexTypeFailureDoesNotPartiallyCompleteOrPublishOtherIndex() {
        var f = fixture("REDIS"); var request = request("request-"+UUID.randomUUID(), "order", f.time.now);
        String tenant = f.scope.tenantId().value();
        String corrupt = "mk:decision:trace:{"+tenant+"}:request:"+Digests.sha256Hex(request.requestId());
        redis.opsForValue().set(corrupt, "wrong-type");
        assertThrows(RuntimeException.class, () -> f.executor.execute(f.scope, request));
        assertFalse(Boolean.TRUE.equals(redis.hasKey("mk:decision:command:{"+tenant+"}:"+request.idempotencyKey())));
        assertFalse(Boolean.TRUE.equals(redis.hasKey("mk:decision:trace:{"+tenant+"}:order:"+Digests.sha256Hex("order"))));
    }

    @Test void jdbcTraceFailureRollsBackTheCompletedDecision() {
        var f = fixture("JDBC"); var request = request("request-"+UUID.randomUUID(), "order", f.time.now);
        var failing = mock(DecisionCommandRepository.class, org.mockito.AdditionalAnswers.delegatesTo(commands));
        doThrow(new IllegalStateException("injected trace write failure")).when(failing).saveTrace(anyString(), anyString(), anyString(), anyString(), any(), anyString(), anyString());
        var executor = executor(failing, f.time, f.decisions, "JDBC");
        assertThrows(IllegalStateException.class, () -> executor.execute(f.scope, request));
        assertTrue(commands.lock(f.scope.tenantId().value(), request.idempotencyKey()).isEmpty());
    }

    private Fixture fixture(String mode) {
        var time = new MutableClock(Instant.now()); var decisions = mock(DecisionApplicationService.class);
        when(decisions.evaluate(any(), any())).thenAnswer(call -> response(call.getArgument(1), time.now));
        return new Fixture(scope("trace-"+UUID.randomUUID(), "*"), time, decisions, executor(commands, time, decisions, mode));
    }
    @SuppressWarnings("unchecked")
    private DecisionRequestExecutor executor(DecisionCommandRepository repository, Clock time, DecisionApplicationService decisions, String mode) {
        ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class); when(provider.getIfAvailable()).thenReturn(redis);
        return new DecisionRequestExecutor(repository, mapper, time, decisions, tx, provider, mode);
    }
    private TenantScope scope(String tenant, String permission) { return new TenantScope(new TenantId(tenant), Set.of("org"), Set.of("shop"), "operator", Set.of(permission)); }
    private DecisionRequest request(String id, String order, Instant at) { return new DecisionRequest(id, "idem-"+UUID.randomUUID(), "org", order, "sensitive-subject", "WEB", at, cart, Set.of()); }
    private DecisionResponse response(DecisionRequest request, Instant at) {
        return new DecisionResponse(request.requestId(), UUID.randomUUID().toString(), 1, new PricingEngine().price(cart, List.of()),
                "signed-secret-token", List.of(), 10, at);
    }
    private record Fixture(TenantScope scope, MutableClock time, DecisionApplicationService decisions, DecisionRequestExecutor executor) { }
    private static final class MutableClock extends Clock {
        private Instant now;
        MutableClock(Instant now) { this.now=now; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
