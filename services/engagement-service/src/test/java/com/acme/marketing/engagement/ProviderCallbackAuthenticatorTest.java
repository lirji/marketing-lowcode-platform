package com.acme.marketing.engagement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.acme.marketing.engagement.infrastructure.ProviderCallbackAuthenticator;
import com.acme.marketing.platform.error.ForbiddenException;
import com.acme.marketing.provider.HmacRequestSigner;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ProviderCallbackAuthenticatorTest {
    private static final Instant NOW = Instant.parse("2026-09-02T12:00:00Z");
    private static final String SECRET = "provider-callback-secret-with-32-bytes-minimum";
    private static final byte[] BODY = ("{\"providerEventId\":\"evt-1\","
            + "\"providerRequestId\":\"provider-1\",\"status\":\"DELIVERED\","
            + "\"occurredAt\":\"2026-09-02T11:59:59Z\",\"attributes\":{}}")
            .getBytes(StandardCharsets.UTF_8);

    @Test
    void oidcForbidsSandboxUnlessExplicitlyAllowed() {
        assertThrows(IllegalStateException.class, () -> new ProviderCallbackAuthenticator(
                new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC), "SANDBOX", "", "OIDC", false));
        new ProviderCallbackAuthenticator(
                new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC), "SANDBOX", "", "OIDC", true);
    }

    @Test
    void verifiesExactBytesTimestampAndProviderIdentity() {
        ProviderCallbackAuthenticator authenticator = authenticator();
        String signature = new HmacRequestSigner(SECRET.getBytes(StandardCharsets.UTF_8))
                .sign("provider-1", NOW, BODY);
        assertEquals("evt-1", authenticator.authenticate(BODY, "provider-1", NOW.toString(), signature)
                .providerEventId());

        assertThrows(ForbiddenException.class,
                () -> authenticator.authenticate(BODY, "provider-2", NOW.toString(), signature));
        assertThrows(ForbiddenException.class,
                () -> authenticator.authenticate(BODY, "provider-1", NOW.minusSeconds(301).toString(), signature));
        byte[] changed = BODY.clone();
        changed[10] ^= 1;
        assertThrows(ForbiddenException.class,
                () -> authenticator.authenticate(changed, "provider-1", NOW.toString(), signature));
    }

    private static ProviderCallbackAuthenticator authenticator() {
        return new ProviderCallbackAuthenticator(new ObjectMapper(),
                Clock.fixed(NOW, ZoneOffset.UTC), "HTTP", SECRET, "OIDC", false);
    }
}
