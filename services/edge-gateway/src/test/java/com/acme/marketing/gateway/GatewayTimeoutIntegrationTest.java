package com.acme.marketing.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 通过真实 Gateway 路由验证冷启动控制操作不会被默认一秒预算截断，实时请求仍受短预算保护。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "marketing.gateway.rate-limit.enabled=false")
class GatewayTimeoutIntegrationTest {
    private static final ExecutorService WORKERS = Executors.newFixedThreadPool(4);
    private static final HttpServer BACKEND = backend();
    @LocalServerPort private int port;
    private final HttpClient client = HttpClient.newHttpClient();

    /** 下游模拟真实耗时；所有路由共用同一服务，避免结果来自不同响应实现。 */
    private static HttpServer backend() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(WORKERS);
            server.createContext("/", exchange -> {
                try {
                    Thread.sleep(1500);
                    byte[] body = "{\"completed\":true}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } finally {
                    exchange.close();
                }
            });
            server.start();
            return server;
        } catch (IOException failure) {
            throw new IllegalStateException("test backend cannot start", failure);
        }
    }

    @DynamicPropertySource
    static void upstreams(DynamicPropertyRegistry properties) {
        for (String key : new String[]{"CONTROL_SERVICE_URL", "COMPILER_SERVICE_URL", "DECISION_SERVICE_URL", "JOURNEY_SERVICE_URL"}) {
            properties.add(key, () -> "http://127.0.0.1:" + BACKEND.getAddress().getPort());
        }
    }

    @AfterAll
    static void closeBackend() {
        BACKEND.stop(0);
        WORKERS.shutdownNow();
    }

    @Test
    void compilationAndControlWritesCanCompleteAfterOneSecond() throws Exception {
        assertEquals(200, request("/api/v1/compile", "POST").statusCode());
        assertEquals(200, request("/api/v1/dmn/analyze", "POST").statusCode());
        assertEquals(200, request("/api/v1/dmn/evaluate", "POST").statusCode());
        assertEquals(200, request("/api/v1/campaigns", "POST").statusCode());
    }

    @Test
    void bothRuntimeWarmupRoutesUseTheirControlBudget() throws Exception {
        assertEquals(200, request("/api/v1/decisions/runtime/manifest", "PUT").statusCode());
        assertEquals(200, request("/api/v1/journey-runtime/manifest", "PUT").statusCode());
    }

    @Test
    void onlineDecisionDoesNotInheritTheCompilationBudget() throws Exception {
        long start = System.nanoTime();
        assertEquals(504, request("/api/v1/decisions:evaluate", "POST").statusCode());
        assertTrue(Duration.ofNanos(System.nanoTime() - start).compareTo(Duration.ofSeconds(3)) < 0);
    }

    private HttpResponse<String> request(String path, String method) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .header("X-Dev-Tenant-Id", "timeout-test")
                .header("X-Dev-Actor-Id", "gateway-test")
                .header("X-Dev-Permissions", "*")
                .method(method, HttpRequest.BodyPublishers.ofString("{}"))
                .build(), HttpResponse.BodyHandlers.ofString());
    }
}
