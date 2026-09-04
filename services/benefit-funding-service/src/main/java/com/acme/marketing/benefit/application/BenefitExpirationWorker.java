package com.acme.marketing.benefit.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Releases expired reservations without relying on an operator or client request. */
@Component
@ConditionalOnProperty(name = "marketing.expiration.enabled", havingValue = "true", matchIfMissing = true)
public final class BenefitExpirationWorker {
    private final BenefitFundingService service;

    public BenefitExpirationWorker(BenefitFundingService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${marketing.expiration.fixed-delay-ms:1000}")
    public void expire() {
        service.expireDueReservations(200);
    }
}
