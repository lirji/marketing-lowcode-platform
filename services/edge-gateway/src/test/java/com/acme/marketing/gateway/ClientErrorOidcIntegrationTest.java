package com.acme.marketing.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** JWT 验签器为受控测试边界；真实 HTTP 验证采集入口不信任 DEV 伪造身份。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "marketing.security.mode=OIDC", "marketing.security.dev-headers-enabled=false", "marketing.gateway.rate-limit.enabled=true"
})
class ClientErrorOidcIntegrationTest {
    @LocalServerPort private int port;
    @MockitoBean private StringRedisTemplate redis;
    @MockitoBean private JwtDecoder decoder;

    @Test void usesVerifiedJwtTenantAndRejectsMissingOrInvalidBearer() throws Exception {
        var client = HttpClient.newHttpClient();
        when(redis.execute(any(), anyList(), any(Object[].class))).thenReturn(1L);
        when(decoder.decode("valid-token")).thenReturn(Jwt.withTokenValue("valid-token").header("alg", "RS256")
                .subject("verified-actor").claim("tenant_id", "verified-tenant").issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60)).build());
        when(decoder.decode("invalid-token")).thenThrow(new BadJwtException("invalid"));
        assertEquals(401, client.send(request(null), HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(401, client.send(request("invalid-token"), HttpResponse.BodyHandlers.ofString()).statusCode());
        verifyNoInteractions(redis);
        assertEquals(204, client.send(request("valid-token"), HttpResponse.BodyHandlers.ofString()).statusCode());
        verify(redis).execute(any(), eq(List.of("marketing:gateway:rate:{verified-tenant}:client-errors")), eq("5"), eq("10"), eq("1"));
    }

    private HttpRequest request(String token) {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + ClientErrorController.PATH))
                .header("Content-Type", "application/json").header("X-Dev-Tenant-Id", "forged-tenant")
                .header("X-Dev-Actor-Id", "forged-actor").POST(HttpRequest.BodyPublishers.ofString(
                        "{\"schemaVersion\":1,\"source\":\"WINDOW\",\"route\":\"HOME\",\"httpStatus\":0}"));
        if (token != null) builder.header("Authorization", "Bearer " + token);
        return builder.build();
    }
}
