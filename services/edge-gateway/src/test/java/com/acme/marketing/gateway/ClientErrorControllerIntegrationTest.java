package com.acme.marketing.gateway;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.micrometer.core.instrument.MeterRegistry;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** 真实 HTTP 穿过身份和限流链；Redis 故障注入验证诊断不会绕过准入保护。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "marketing.gateway.rate-limit.enabled=true")
@ExtendWith(OutputCaptureExtension.class)
class ClientErrorControllerIntegrationTest {
    private static final String VALID = "{\"schemaVersion\":1,\"source\":\"API\",\"route\":\"ASSETS\",\"httpStatus\":503}";
    private static final String TENANT = "private-tenant-diagnostic";
    @LocalServerPort private int port;
    @MockitoBean private StringRedisTemplate redis;
    @Autowired private MeterRegistry meters;
    private final HttpClient client = HttpClient.newHttpClient();

    @BeforeEach void allow() { when(redis.execute(anyScript(), anyList(), any(Object[].class))).thenReturn(1L); }

    @Test void acceptsSafeEventWithTrustedIdentityAndLowCardinalityMetrics(CapturedOutput output) throws Exception {
        var counter = meters.counter("marketing.console.errors", "source", "API", "route", "ASSETS", "status_class", "5xx");
        double before = counter.count();
        String trace = "ABCDEF0123456789ABCDEF0123456789";
        var response = send(VALID.replace("}", ",\"backendTraceId\":\"" + trace + "\"}"), true);
        assertEquals(204, response.statusCode()); assertEquals("", response.body());
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        assertEquals("5", response.headers().firstValue("X-RateLimit-Limit").orElseThrow());
        assertEquals(before + 1, counter.count());
        assertTrue(output.getOut().contains("client_error schemaVersion=1 source=API route=ASSETS httpStatus=503 backendTraceId=" + trace.toLowerCase()));
        assertFalse(output.getOut().contains(TENANT)); assertFalse(output.getOut().contains("private-actor"));
        assertEquals(List.of("route", "source", "status_class"), counter.getId().getTags().stream().map(t -> t.getKey()).sorted().toList());
        verify(redis).execute(anyScript(), eq(List.of("marketing:gateway:rate:{" + TENANT + "}:client-errors")), eq("5"), eq("10"), eq("1"));
    }

    @Test void rejectsAnonymousBeforeRedisOrLogging(CapturedOutput output) throws Exception {
        assertEquals(401, send(VALID, false).statusCode());
        verifyNoInteractions(redis); assertFalse(output.getOut().contains("client_error schemaVersion="));
    }

    @Test void rejectsArbitraryDataMalformedJsonAndNonContractTypes(CapturedOutput output) throws Exception {
        for (String body : List.of("null", "[]", "{}", VALID + " {}", VALID.replace("1,", "1.0,"),
                VALID.replace("503", "600"), VALID.replace("503", "99"), VALID.replace("503", "2147483648"),
                VALID.replace("\"API\"", "\"tenant-secret\""), VALID.replace("\"ASSETS\"", "null"),
                VALID.replace("}", ",\"tenantId\":\"tenant-secret\"}"),
                VALID.replace("}", ",\"message\":\"Bearer secret-diagnostic\"}"),
                VALID.replace("}", ",\"source\":\"WINDOW\"}"),
                VALID.replace("}", ",\"backendTraceId\":\"token-secret\"}"))) {
            var response = send(body, true);
            assertEquals(400, response.statusCode(), body);
            assertTrue(response.body().contains("CLIENT_ERROR_INPUT_INVALID"));
            assertFalse(response.body().contains("secret"));
        }
        assertFalse(output.getOut().contains("client_error schemaVersion="));
        assertFalse(output.getOut().contains("secret-diagnostic"));
    }

    @Test void boundsBothKnownAndChunkedBodiesBeforeParsing() throws Exception {
        String body = VALID + " ".repeat(1025);
        var known = send(body, true); assertEquals(400, known.statusCode());
        assertTrue(known.body().contains("CLIENT_ERROR_REQUEST_TOO_LARGE"));
        var chunked = request(true).POST(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)))).build();
        var response = client.send(chunked, HttpResponse.BodyHandlers.ofString());
        assertEquals(400, response.statusCode()); assertTrue(response.body().contains("CLIENT_ERROR_REQUEST_TOO_LARGE"));
        String exact = VALID + " ".repeat(1024 - VALID.length());
        assertEquals(204, send(exact, true).statusCode());
    }

    @Test void rejectsNonUtf8AndWrongMediaType() throws Exception {
        var utf16 = request(true).POST(HttpRequest.BodyPublishers.ofByteArray(VALID.getBytes(StandardCharsets.UTF_16))).build();
        assertEquals(400, client.send(utf16, HttpResponse.BodyHandlers.ofString()).statusCode());
        var text = request(true).setHeader("Content-Type", "text/plain").POST(HttpRequest.BodyPublishers.ofString(VALID)).build();
        assertEquals(415, client.send(text, HttpResponse.BodyHandlers.ofString()).statusCode());
    }

    @Test void isolatedQuotaRejectsBeforeLoggingAndFailsClosed(CapturedOutput output) throws Exception {
        when(redis.execute(anyScript(), anyList(), any(Object[].class))).thenReturn(0L);
        var denied = send(VALID, true); assertEquals(429, denied.statusCode());
        assertEquals("1", denied.headers().firstValue("Retry-After").orElseThrow());
        when(redis.execute(anyScript(), anyList(), any(Object[].class))).thenReturn(null);
        assertEquals(503, send(VALID, true).statusCode());
        when(redis.execute(anyScript(), anyList(), any(Object[].class))).thenThrow(new RedisConnectionFailureException("private redis error"));
        var unavailable = send(VALID, true); assertEquals(503, unavailable.statusCode());
        assertFalse(unavailable.body().contains("private redis error"));
        assertFalse(output.getOut().contains("client_error schemaVersion="));
    }

    private HttpRequest.Builder request(boolean authenticated) {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + ClientErrorController.PATH))
                .timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json");
        if (authenticated) builder.header("X-Dev-Tenant-Id", TENANT).header("X-Dev-Actor-Id", "private-actor").header("X-Dev-Permissions", "campaign:read");
        return builder;
    }
    private HttpResponse<String> send(String body, boolean authenticated) throws Exception {
        return client.send(request(authenticated).POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
    private static RedisScript<Long> anyScript() { return any(); }
}
