package com.acme.marketing.control.infrastructure;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "marketing.release-outbox.enabled", havingValue = "true")
public final class ReleaseOutboxRelay {
    private final ReleaseOutboxPublisher publisher;

    public ReleaseOutboxRelay(ReleaseOutboxPublisher publisher) {
        this.publisher = publisher;
    }

    @Scheduled(fixedDelayString = "${marketing.release-outbox.poll-delay-ms:250}")
    public void relay() {
        for (int sent = 0; sent < 100; sent++) {
            if (publisher.publishOne() != ReleaseOutboxPublisher.Result.PUBLISHED) return;
        }
    }
}
