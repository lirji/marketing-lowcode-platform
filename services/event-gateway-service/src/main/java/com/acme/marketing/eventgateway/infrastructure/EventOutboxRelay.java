package com.acme.marketing.eventgateway.infrastructure;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "marketing.outbox.enabled", havingValue = "true")
public final class EventOutboxRelay {
    private static final int BATCH_SIZE = 1_000;
    private static final int MAX_BATCHES_PER_TICK = 10;
    private final EventOutboxPublisher publisher;

    public EventOutboxRelay(EventOutboxPublisher publisher) {
        this.publisher = publisher;
    }

    @Scheduled(fixedDelayString = "${marketing.outbox.fixed-delay-ms:250}")
    public void relay() {
        for (int index = 0; index < MAX_BATCHES_PER_TICK; index++) {
            EventOutboxPublisher.BatchResult result = publisher.publishBatch(BATCH_SIZE);
            if (result.published() < BATCH_SIZE) return;
        }
    }
}
