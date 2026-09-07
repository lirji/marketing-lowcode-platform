package com.acme.marketing.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "marketing.gateway.rate-limit.enabled=true",
        "EVENT_SERVICE_URL=http://127.0.0.1:1"
})
class ClusterRateLimitFilterIntegrationTest {
    @LocalServerPort private int port;
    @MockitoBean private StringRedisTemplate redis;
    private final HttpClient client = HttpClient.newHttpClient();

    @Test
    void allGatewayReplicasUseTheSharedDecisionAndFailClosedWhenRedisIsUnavailable() throws Exception {
        when(redis.execute(anyRedisScript(), anyList(), any(Object[].class))).thenReturn(0L);
        HttpResponse<String> throttled = client.send(request(), HttpResponse.BodyHandlers.ofString());
        assertEquals(429, throttled.statusCode());
        assertEquals("1", throttled.headers().firstValue("Retry-After").orElseThrow());
        assertTrue(throttled.body().contains("TENANT_RATE_LIMITED"));

        when(redis.execute(anyRedisScript(), anyList(), any(Object[].class))).thenReturn(null);
        HttpResponse<String> emptyDecision = client.send(request(), HttpResponse.BodyHandlers.ofString());
        assertEquals(503, emptyDecision.statusCode());
        assertTrue(emptyDecision.body().contains("GATEWAY_RATE_LIMITER_UNAVAILABLE"));

        when(redis.execute(anyRedisScript(), anyList(), any(Object[].class)))
                .thenThrow(new RedisConnectionFailureException("redis unavailable"));
        HttpResponse<String> unavailable = client.send(request(), HttpResponse.BodyHandlers.ofString());
        assertEquals(503, unavailable.statusCode());
        assertTrue(unavailable.body().contains("GATEWAY_RATE_LIMITER_UNAVAILABLE"));
    }

    private HttpRequest request() {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/events"))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .header("X-Dev-Tenant-Id", "tenant-rate-test")
                .header("X-Dev-Actor-Id", "gateway-test")
                .header("X-Dev-Permissions", "*")
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build();
    }

    private static RedisScript<Long> anyRedisScript() {
        return org.mockito.ArgumentMatchers.any();
    }
}
