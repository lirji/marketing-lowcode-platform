package com.acme.marketing.engagement.infrastructure;

import com.acme.marketing.engagement.application.ProviderRoute;
import com.acme.marketing.provider.HmacRequestSigner;
import com.acme.marketing.provider.ProviderConnector;
import com.acme.marketing.provider.SandboxProviderConnector;
import com.acme.marketing.provider.SignedHttpProviderConnector;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class EngagementConfiguration {
    @Bean
    public ProviderConnector providerConnector(Clock clock,
            @Value("${marketing.provider.mode:SANDBOX}") String mode,
            @Value("${marketing.provider.hmac-secret:}") String hmacSecret,
            @Value("${marketing.security.mode:DEV}") String securityMode,
            @Value("${marketing.provider.allow-sandbox:false}") boolean allowSandbox) {
        if ("SANDBOX".equalsIgnoreCase(mode)) {
            if ("OIDC".equalsIgnoreCase(securityMode) && !allowSandbox) {
                throw new IllegalStateException("sandbox provider is forbidden in OIDC mode");
            }
            return new SandboxProviderConnector(clock);
        }
        if (!"HTTP".equalsIgnoreCase(mode) || hmacSecret.length() < 32) {
            throw new IllegalStateException("HTTP provider mode requires a HMAC secret of at least 32 characters");
        }
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        return new SignedHttpProviderConnector(client,
                new HmacRequestSigner(hmacSecret.getBytes(StandardCharsets.UTF_8)), clock,
                duration -> {
                    try {
                        Thread.sleep(duration);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                });
    }

    @Bean
    public ProviderRoute providerRoute(
            @Value("${marketing.provider.mode:SANDBOX}") String mode,
            @Value("${marketing.provider.endpoint:}") String endpoint) {
        if ("SANDBOX".equalsIgnoreCase(mode)) return (tenantId, channel) -> null;
        URI uri = URI.create(endpoint);
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getFragment() != null) {
            throw new IllegalStateException("provider endpoint must be an absolute HTTPS URI without userinfo or fragment");
        }
        return (tenantId, channel) -> uri;
    }
}
