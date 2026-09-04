package com.acme.marketing.jobs.measurement;

import com.acme.marketing.contracts.event.MarketingFact;

public record FactContribution(
        MarketingFact.Type type,
        String businessKey,
        String campaignId,
        String experimentId,
        String variantId,
        String subjectHash,
        long revenueMinor,
        long costMinor,
        long occurredAtEpochMillis,
        long ingestedAtEpochMillis) {
}
