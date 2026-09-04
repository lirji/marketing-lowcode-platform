package com.acme.marketing.decision.runtime;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public final class RuntimeManifestReconciler {
    private static final Logger LOGGER = LoggerFactory.getLogger(RuntimeManifestReconciler.class);
    private final RuntimeManifestRegistry registry;
    private final Clock clock;
    private final AtomicReference<Instant> lastSuccessfulPoll = new AtomicReference<>(Instant.EPOCH);

    public RuntimeManifestReconciler(RuntimeManifestRegistry registry, Clock clock) {
        this.registry = registry;
        this.clock = clock;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        reconcile();
    }

    @Scheduled(fixedDelayString = "${marketing.runtime.reconcile-interval-ms:1000}")
    public void reconcile() {
        try {
            int warmed = registry.reconcile();
            lastSuccessfulPoll.set(clock.instant());
            if (warmed > 0) LOGGER.info("warmed {} decision runtime generation(s)", warmed);
        } catch (RuntimeException failure) {
            LOGGER.warn("desired-state reconciliation failed; retaining last-known-good generations", failure);
        }
    }

    public Instant lastSuccessfulPoll() {
        return lastSuccessfulPoll.get();
    }
}
