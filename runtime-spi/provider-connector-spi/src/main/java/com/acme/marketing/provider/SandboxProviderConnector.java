package com.acme.marketing.provider;

import com.acme.marketing.platform.crypto.Digests;
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class SandboxProviderConnector implements ProviderConnector {
    private final Clock clock;
    private final ConcurrentHashMap<String, ProviderResult> sent = new ConcurrentHashMap<>();

    public SandboxProviderConnector(Clock clock) {
        this.clock = clock;
    }

    @Override
    public ProviderResult send(ProviderRequest request, ProviderPolicy policy) {
        return sent.computeIfAbsent(request.tenantId() + ':' + request.contactKey(), ignored ->
                new ProviderResult(ProviderResult.Status.ACCEPTED,
                        "sandbox-" + Digests.sha256Hex(request.tenantId() + ':' + request.contactKey()).substring(0, 20),
                        "SANDBOX_ACCEPTED", 1, clock.instant(), Map.of("channel", request.channel())));
    }
}
