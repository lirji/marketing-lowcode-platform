package com.acme.marketing.journeyservice.infrastructure;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "marketing.journey.dispatch-enabled", havingValue = "true")
public final class JourneyDispatchRelay {
    private final JourneyDispatchPublisher publisher;

    public JourneyDispatchRelay(JourneyDispatchPublisher publisher) {
        this.publisher = publisher;
    }

    @Scheduled(fixedDelayString = "${marketing.journey.dispatch-delay-ms:250}")
    public void relay() {
        for (int batch = 0; batch < 10; batch++) {
            JourneyDispatchPublisher.BatchResult result = publisher.publishBatch(500);
            if (result.published() < 500) return;
        }
    }
}
