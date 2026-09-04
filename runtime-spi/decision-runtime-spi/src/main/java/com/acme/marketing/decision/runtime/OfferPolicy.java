package com.acme.marketing.decision.runtime;

import com.acme.marketing.decision.model.OfferCandidate;
import java.time.Instant;
import java.util.Set;

public record OfferPolicy(
        OfferCandidate candidate,
        Instant validFrom,
        Instant validTo,
        Set<String> channels,
        Set<String> skuIds,
        Set<String> categoryIds,
        Set<String> shopIds,
        String requiredAudienceSnapshotId) {
    public OfferPolicy {
        if (candidate == null || validFrom == null || validTo == null || !validTo.isAfter(validFrom)) {
            throw new IllegalArgumentException("offer policy validity is invalid");
        }
        channels = Set.copyOf(channels == null ? Set.of() : channels);
        skuIds = Set.copyOf(skuIds == null ? Set.of() : skuIds);
        categoryIds = Set.copyOf(categoryIds == null ? Set.of() : categoryIds);
        shopIds = Set.copyOf(shopIds == null ? Set.of() : shopIds);
        requiredAudienceSnapshotId = requiredAudienceSnapshotId == null ? "" : requiredAudienceSnapshotId;
    }
}
