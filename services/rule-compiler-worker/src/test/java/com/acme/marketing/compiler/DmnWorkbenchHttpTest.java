package com.acme.marketing.compiler;

import static org.junit.jupiter.api.Assertions.*;

import com.acme.marketing.compiler.application.DmnWorkbenchService;
import com.acme.marketing.compiler.interfaces.DmnWorkbenchController;
import com.acme.marketing.compiler.infrastructure.CompilerConfiguration;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Configuration;

/** 独立无数据库 Web 上下文，真实 HTTP 与平台认证过滤器验证权限和边界。 */
@SpringBootTest(classes = DmnWorkbenchHttpTest.Application.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"marketing.security.mode=DEV", "marketing.security.dev-headers-enabled=true",
                "management.otlp.metrics.export.enabled=false", "management.tracing.enabled=false"})
class DmnWorkbenchHttpTest {
    private static final String TABLE = """
            {"hitPolicy":"UNIQUE","inputs":[{"name":"amount","type":"number"}],
             "outputs":[{"name":"answer","type":"string"}],
             "rules":[{"inputs":["-"],"outputs":["\\\"ok\\\""]}]}
            """;
    @LocalServerPort int port;
    private final HttpClient client = HttpClient.newHttpClient();

    @Test void tenantAndDistinctPermissionsAreRequiredBeforeEvaluation() throws Exception {
        String analyze = "{\"table\":" + TABLE + "}";
        assertEquals(401, post("analyze", analyze, null).statusCode());
        assertEquals(403, post("analyze", analyze, "definition:simulate").statusCode());
        assertEquals(403, post("evaluate", "{\"table\":" + TABLE + ",\"context\":{\"amount\":\"1\"}}", "definition:compile").statusCode());
        var result = post("analyze", analyze, "definition:compile");
        assertEquals(200, result.statusCode(), result.body());
        assertTrue(result.body().contains("\"valid\":true"), result.body());
        var evaluation = post("evaluate", "{\"table\":" + TABLE + ",\"context\":{\"amount\":\"1\"}}", "definition:simulate");
        assertEquals(200, evaluation.statusCode(), evaluation.body());
        assertTrue(evaluation.body().contains("DmnWorkbenchDecision"), evaluation.body());
    }

    @Test void invalidTypedPayloadReturnsStableDmnProblem() throws Exception {
        var invalid = post("evaluate", "{\"table\":" + TABLE + ",\"context\":{\"amount\":12}}", "definition:simulate");
        assertEquals(400, invalid.statusCode(), invalid.body());
        assertTrue(invalid.body().contains("DMN_CONTEXT_INVALID"), invalid.body());
        var injection = post("analyze", "{\"table\":" + TABLE.replace("[\"-\"]", "[\"1+1\"]") + "}", "definition:compile");
        assertEquals(400, injection.statusCode(), injection.body());
        assertTrue(injection.body().contains("DMN_CELL_INVALID"), injection.body());
        for (String malformed : new String[] {"{", "{\"table\":" + TABLE + ",\"table\":" + TABLE + "}"}) {
            var response = post("analyze", malformed, "definition:compile");
            assertEquals(400, response.statusCode(), response.body());
            assertTrue(response.body().contains("DMN_REQUEST_INVALID"), response.body());
        }
    }

    @Test void rejectsOversizedKnownAndChunkedBodiesBeforeJsonParsing() throws Exception {
        String body = " ".repeat(512 * 1024 + 1);
        var known = post("analyze", body, "definition:compile");
        assertEquals(400, known.statusCode(), known.body());
        assertTrue(known.body().contains("DMN_REQUEST_TOO_LARGE"), known.body());
        assertEquals(403, post("analyze", body, "definition:read").statusCode());
        byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var chunked = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/dmn/analyze"))
                .header("Content-Type", "application/json").header("X-Dev-Tenant-Id", "dmn-test")
                .header("X-Dev-Actor-Id", "tester").header("X-Dev-Permissions", "definition:compile")
                .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new java.io.ByteArrayInputStream(bytes))).build();
        assertEquals(-1, chunked.bodyPublisher().orElseThrow().contentLength());
        var result = client.send(chunked, HttpResponse.BodyHandlers.ofString());
        assertEquals(400, result.statusCode(), result.body());
        assertTrue(result.body().contains("DMN_REQUEST_TOO_LARGE"), result.body());
    }

    private HttpResponse<String> post(String operation, String body, String permission) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/dmn/" + operation))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        if (permission != null) request.header("X-Dev-Tenant-Id", "dmn-test").header("X-Dev-Actor-Id", "tester")
                .header("X-Dev-Permissions", permission);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(excludeName = {"org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
            "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration"})
    @Import({DmnWorkbenchController.class, DmnWorkbenchService.class, CompilerConfiguration.class})
    static class Application { }
}
