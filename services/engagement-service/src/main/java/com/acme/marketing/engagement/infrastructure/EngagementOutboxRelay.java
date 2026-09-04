package com.acme.marketing.engagement.infrastructure;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "marketing.engagement.outbox-enabled", havingValue = "true")
public final class EngagementOutboxRelay {
    private final EngagementOutboxPublisher publisher;

    public EngagementOutboxRelay(EngagementOutboxPublisher publisher) {
        this.publisher = publisher;
    }

    @Scheduled(fixedDelayString = "${marketing.engagement.outbox-delay-ms:250}")
    public void relay() {
        for (int batch = 0; batch < 10; batch++) {
            EngagementOutboxPublisher.Result result = publisher.publishBatch(500);
            if (result.published() < 500) return;
        }
    }
}
