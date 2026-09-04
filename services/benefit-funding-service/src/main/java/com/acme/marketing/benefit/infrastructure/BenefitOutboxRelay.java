package com.acme.marketing.benefit.infrastructure;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "marketing.benefit.outbox-enabled", havingValue = "true")
public final class BenefitOutboxRelay {
    private final BenefitOutboxPublisher publisher;

    public BenefitOutboxRelay(BenefitOutboxPublisher publisher) {
        this.publisher = publisher;
    }

    @Scheduled(fixedDelayString = "${marketing.benefit.outbox-delay-ms:250}")
    public void relay() {
        for (int batch = 0; batch < 10; batch++) {
            BenefitOutboxPublisher.Result result = publisher.publishBatch(500);
            if (result.published() < 500) return;
        }
    }
}
