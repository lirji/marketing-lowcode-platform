package com.acme.marketing.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProviderConnectorTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void sandboxIsIdempotentByTenantAndContactKey() {
        SandboxProviderConnector connector = new SandboxProviderConnector(Clock.fixed(NOW, ZoneOffset.UTC));
        ProviderRequest request = request();

        ProviderResult first = connector.send(request, ProviderPolicy.defaults());
        ProviderResult duplicate = connector.send(request, ProviderPolicy.defaults());

        assertEquals(first, duplicate);
        assertEquals(ProviderResult.Status.ACCEPTED, first.status());
    }

    @Test
    void signaturesAreTamperEvident() {
        HmacRequestSigner signer = new HmacRequestSigner(
                "test-secret-that-is-long-enough".getBytes(StandardCharsets.UTF_8));
        byte[] body = "payload".getBytes(StandardCharsets.UTF_8);
        String signature = signer.sign("contact-1", NOW, body);

        assertTrue(signer.verify(signature, "contact-1", NOW, body));
        assertFalse(signer.verify(signature, "contact-2", NOW, body));
        assertFalse(signer.verify("not-hex", "contact-1", NOW, body));
    }

    private static ProviderRequest request() {
        return new ProviderRequest("tenant-a", "contact-1", "SMS", "subject-1", "tpl-v1",
                URI.create("https://provider.invalid/send"), Map.of("name", "Ada"), NOW);
    }
}
