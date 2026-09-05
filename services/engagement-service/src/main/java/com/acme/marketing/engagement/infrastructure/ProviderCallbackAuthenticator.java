package com.acme.marketing.engagement.infrastructure;

import com.acme.marketing.platform.error.ForbiddenException;
import com.acme.marketing.provider.HmacRequestSigner;
import com.acme.marketing.provider.ProviderCallback;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/** Verifies the exact callback bytes before deserialization so JSON normalization cannot bypass the MAC. */
@Component
public final class ProviderCallbackAuthenticator {
    private static final int MAX_BODY_BYTES = 64 * 1024;
    private static final Duration MAX_AGE = Duration.ofMinutes(5);
    private static final Duration MAX_FUTURE_SKEW = Duration.ofSeconds(30);

    private final ObjectMapper mapper;
    private final Clock clock;
    private final boolean verificationRequired;
    private final HmacRequestSigner signer;

    public ProviderCallbackAuthenticator(ObjectMapper mapper, Clock clock,
            @Value("${marketing.provider.mode:SANDBOX}") String mode,
            @Value("${marketing.provider.hmac-secret:}") String hmacSecret,
            @Value("${marketing.security.mode:DEV}") String securityMode,
            @Value("${marketing.provider.allow-sandbox:false}") boolean allowSandbox) {
        this.mapper = mapper;
        this.clock = clock;
        this.verificationRequired = "HTTP".equalsIgnoreCase(mode);
        if ("OIDC".equalsIgnoreCase(securityMode) && !verificationRequired && !allowSandbox) {
            throw new IllegalStateException("OIDC provider callbacks require HTTP mode and HMAC verification");
        }
        if (verificationRequired && hmacSecret.length() < 32) {
            throw new IllegalStateException("provider callback HMAC secret must contain at least 32 characters");
        }
        this.signer = verificationRequired
                ? new HmacRequestSigner(hmacSecret.getBytes(StandardCharsets.UTF_8)) : null;
    }

    public ProviderCallback authenticate(byte[] body, String providerRequestId,
            String timestampHeader, String signature) {
        if (body == null || body.length == 0 || body.length > MAX_BODY_BYTES) {
            throw forbidden("provider callback body is empty or exceeds 64 KiB");
        }
        if (!verificationRequired) return decode(body);
        if (blank(providerRequestId) || blank(timestampHeader) || blank(signature)) {
            throw forbidden("provider callback authentication headers are required");
        }
        Instant timestamp;
        try {
            timestamp = Instant.parse(timestampHeader);
        } catch (RuntimeException malformed) {
            throw forbidden("provider callback timestamp is invalid");
        }
        Instant now = clock.instant();
        if (timestamp.isBefore(now.minus(MAX_AGE)) || timestamp.isAfter(now.plus(MAX_FUTURE_SKEW))
                || !signer.verify(signature, providerRequestId, timestamp, body)) {
            throw forbidden("provider callback signature or timestamp is invalid");
        }
        ProviderCallback callback = decode(body);
        if (!providerRequestId.equals(callback.providerRequestId())) {
            throw forbidden("signed provider request identity does not match the callback body");
        }
        return callback;
    }

    private ProviderCallback decode(byte[] body) {
        try {
            ProviderCallback callback = mapper.readValue(body, ProviderCallback.class);
            if (blank(callback.providerEventId()) || blank(callback.providerRequestId())
                    || callback.status() == null || callback.occurredAt() == null) {
                throw forbidden("provider callback identity is incomplete");
            }
            return callback;
        } catch (JacksonException malformed) {
            throw forbidden("provider callback body is invalid");
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static ForbiddenException forbidden(String message) {
        return new ForbiddenException("PROVIDER_CALLBACK_SIGNATURE_INVALID", message);
    }
}
