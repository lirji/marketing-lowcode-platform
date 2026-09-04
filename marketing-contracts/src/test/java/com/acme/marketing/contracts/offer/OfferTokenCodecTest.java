package com.acme.marketing.contracts.offer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.acme.marketing.platform.crypto.Ed25519;
import com.acme.marketing.platform.identity.TenantId;
import java.security.KeyPair;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class OfferTokenCodecTest {
    private static final Instant NOW = Instant.parse("2026-09-02T08:00:00Z");

    @Test
    void bindsTenantCartGenerationAndFunding() {
        KeyPair pair = Ed25519.generateKeyPair();
        TenantId tenant = new TenantId("tenant-a");
        String digest = "sha256:" + "a".repeat(64);
        OfferTokenClaims claims = new OfferTokenClaims("decision", tenant, "org-a", "subject-1", "order-1",
                List.of("shop-a"),
                digest, "quote-1", "request-1", 42, List.of("artifact-42"),
                List.of(new OfferLineClaim("offer-1", "benefit-1@1", "CNY", 8000, 1,
                        List.of(new FundingShareClaim("PLATFORM", "platform", "CNY", 5000),
                                new FundingShareClaim("MERCHANT", "merchant-1", "CNY", 3000)))),
                "terms-1", NOW, NOW.plusSeconds(60), "nonce-1", List.of("audience-3"));

        String token = OfferTokenCodec.encode("offer-key-1", pair.getPrivate(), claims);
        OfferTokenClaims verified = OfferTokenCodec.verify(token, ignored -> pair.getPublic(), tenant, digest,
                Clock.fixed(NOW.plusSeconds(1), ZoneOffset.UTC));

        assertEquals(42, verified.generation());
        assertEquals(8000, verified.totalDiscountMinorUnits());
        assertThrows(OfferTokenException.class, () -> OfferTokenCodec.verify(token, ignored -> pair.getPublic(),
                tenant, "sha256:" + "b".repeat(64), Clock.fixed(NOW, ZoneOffset.UTC)));
    }

    @Test
    void rejectsMixedCurrenciesAndUnfundedMonetaryOffersBeforeSigning() {
        assertThrows(IllegalArgumentException.class, () -> new OfferLineClaim(
                "offer", "benefit", "CNY", 1, 1, List.of()));
        OfferLineClaim cny = new OfferLineClaim("cny", "benefit", "CNY", 1, 1,
                List.of(new FundingShareClaim("PLATFORM", "platform", "CNY", 1)));
        OfferLineClaim usd = new OfferLineClaim("usd", "benefit", "USD", 1, 1,
                List.of(new FundingShareClaim("PLATFORM", "platform", "USD", 1)));
        assertThrows(IllegalArgumentException.class, () -> new OfferTokenClaims("decision",
                new TenantId("tenant-a"), "org-a", "subject", "order", List.of("shop"),
                "sha256:" + "a".repeat(64), "quote", "request", 1, List.of("artifact"),
                List.of(cny, usd), "terms", NOW, NOW.plusSeconds(60), "nonce", List.of()));
    }
}
