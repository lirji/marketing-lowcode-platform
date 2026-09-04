package com.acme.marketing.contracts.offer;

import com.acme.marketing.platform.identity.TenantId;
import java.time.Instant;
import java.util.List;

public record OfferTokenClaims(
        String issuer,
        TenantId tenantId,
        String organizationId,
        String subjectToken,
        String orderId,
        List<String> shopIds,
        String cartDigest,
        String quoteId,
        String decisionRequestId,
        long generation,
        List<String> artifactIds,
        List<OfferLineClaim> offerLines,
        String termsVersion,
        Instant issuedAt,
        Instant expiresAt,
        String nonce,
        List<String> audienceVersions) {
    public OfferTokenClaims {
        issuer = requireText(issuer, "issuer");
        if (tenantId == null) {
            throw new IllegalArgumentException("tenantId is required");
        }
        organizationId = requireText(organizationId, "organizationId");
        subjectToken = requireText(subjectToken, "subjectToken");
        orderId = orderId == null ? "" : orderId;
        shopIds = List.copyOf(shopIds == null ? List.of() : shopIds);
        if (shopIds.isEmpty() || shopIds.stream().anyMatch(shop -> shop == null || shop.isBlank())) {
            throw new IllegalArgumentException("shopIds are required");
        }
        cartDigest = requireDigest(cartDigest, "cartDigest");
        quoteId = requireText(quoteId, "quoteId");
        decisionRequestId = requireText(decisionRequestId, "decisionRequestId");
        if (generation <= 0) {
            throw new IllegalArgumentException("generation must be positive");
        }
        artifactIds = List.copyOf(artifactIds);
        offerLines = List.copyOf(offerLines);
        if (artifactIds.isEmpty() || offerLines.isEmpty()) {
            throw new IllegalArgumentException("token must pin artifacts and offer lines");
        }
        if (offerLines.stream().map(OfferLineClaim::currency).distinct().count() != 1) {
            throw new IllegalArgumentException("all offer lines in one token must use the same currency");
        }
        termsVersion = requireText(termsVersion, "termsVersion");
        if (issuedAt == null || expiresAt == null || !expiresAt.isAfter(issuedAt)) {
            throw new IllegalArgumentException("token timestamps are invalid");
        }
        nonce = requireText(nonce, "nonce");
        audienceVersions = List.copyOf(audienceVersions == null ? List.of() : audienceVersions);
    }

    public long totalDiscountMinorUnits() {
        return offerLines.stream().map(OfferLineClaim::minorUnits).reduce(0L, Math::addExact);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank() || value.length() > 512) {
            throw new IllegalArgumentException(name + " is invalid");
        }
        return value;
    }

    private static String requireDigest(String value, String name) {
        if (value == null || !value.matches("sha256:[a-f0-9]{64}")) {
            throw new IllegalArgumentException(name + " must be a sha256 digest");
        }
        return value;
    }
}
