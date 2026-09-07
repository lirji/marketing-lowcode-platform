package com.acme.marketing.decision;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.acme.marketing.contracts.offer.OfferTokenClaims;
import com.acme.marketing.contracts.offer.OfferTokenCodec;
import com.acme.marketing.contracts.artifact.ArtifactAttestation;
import com.acme.marketing.contracts.artifact.PinnedArtifactVerifier;
import com.acme.marketing.contracts.release.ArtifactReference;
import com.acme.marketing.contracts.release.ActivationDirective;
import com.acme.marketing.contracts.release.ActivationDirectiveSigner;
import com.acme.marketing.contracts.release.ReleaseManifest;
import com.acme.marketing.contracts.release.ReleaseManifestSigner;
import com.acme.marketing.decision.application.DecisionApplicationService;
import com.acme.marketing.decision.infrastructure.DecisionRuntimeHealthIndicator;
import com.acme.marketing.decision.model.Cart;
import com.acme.marketing.decision.model.CartLine;
import com.acme.marketing.decision.model.FundingRule;
import com.acme.marketing.decision.model.HitPolicy;
import com.acme.marketing.decision.model.Money;
import com.acme.marketing.decision.model.OfferCandidate;
import com.acme.marketing.decision.model.PriceStage;
import com.acme.marketing.decision.model.PromotionFormula;
import com.acme.marketing.decision.model.PromotionKind;
import com.acme.marketing.decision.runtime.AudienceMembershipProjection;
import com.acme.marketing.decision.runtime.OfferPolicy;
import com.acme.marketing.decision.runtime.PinnedManifestVerifier;
import com.acme.marketing.decision.runtime.PinnedActivationDirectiveVerifier;
import com.acme.marketing.decision.runtime.RuntimeManifestRegistry;
import com.acme.marketing.decision.runtime.OfferPolicyArtifact;
import com.acme.marketing.platform.crypto.Ed25519;
import com.acme.marketing.platform.crypto.Digests;
import com.acme.marketing.platform.crypto.SigningKeyRing;
import com.acme.marketing.platform.identity.TenantId;
import com.acme.marketing.platform.identity.TenantScope;
import com.acme.marketing.platform.isolation.TenantBulkhead;
import java.security.KeyPair;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;
import tools.jackson.databind.ObjectMapper;

class DecisionApplicationServiceTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void trustedManifestAudienceAndPricingProduceVerifiableQuote() throws Exception {
        KeyPair releaseKeys = Ed25519.generateKeyPair();
        ObjectMapper mapper = new ObjectMapper();
        OfferPolicy policy = new OfferPolicy(candidate(), NOW.minusSeconds(10), NOW.plusSeconds(600),
                Set.of("CHECKOUT"), Set.of(), Set.of("home"), Set.of("shop-1"), "audience-v1");
        byte[] policyArtifact = mapper.writeValueAsBytes(new OfferPolicyArtifact(List.of(policy)));
        String checksum = "sha256:" + Digests.sha256Hex(policyArtifact);
        String sourceDigest = "sha256:" + "b".repeat(64);
        String artifactSignature = ArtifactAttestation.sign(releaseKeys.getPrivate(), "tenant-a", "artifact-1",
                "offer-definition", 1, "OFFER_POLICY", "marketing-offer-policy/1", checksum, sourceDigest);
        ReleaseManifest unsigned = manifest(checksum, sourceDigest, artifactSignature);
        ReleaseManifest signed = new ReleaseManifestSigner().sign(unsigned, releaseKeys.getPrivate());
        RuntimeManifestRegistry registry = new RuntimeManifestRegistry(
                new PinnedManifestVerifier(Map.of("release-key", releaseKeys.getPublic())),
                new PinnedActivationDirectiveVerifier(Map.of("release-key", releaseKeys.getPublic())),
                new PinnedArtifactVerifier(Map.of("compiler-key", releaseKeys.getPublic())), "route-key".getBytes(),
                new RuntimeManifestRegistry.RuntimeSlot("prod", "cell-a", "main"), mapper,
                Clock.fixed(NOW, ZoneOffset.UTC));
        DecisionRuntimeHealthIndicator readiness = new DecisionRuntimeHealthIndicator(registry);
        assertEquals(Status.DOWN, readiness.health().getStatus(),
                "a warm-but-not-active or empty runtime must not receive decision traffic");
        registry.install("tenant-a", "release-key", signed, policyArtifact);
        ActivationDirective unsignedDirective = new ActivationDirective("directive-1", new TenantId("tenant-a"),
                signed.manifestId(), "prod", "cell-a", "decision", "main", 1, 1, 1, 0,
                signed.signature(), NOW.minusSeconds(1), signed.expiresAt(), "release-manager", "release-key", "");
        registry.applyActivation("tenant-a",
                ActivationDirectiveSigner.sign(releaseKeys.getPrivate(), unsignedDirective));
        assertEquals(Status.UP, readiness.health().getStatus());
        assertEquals(1, registry.usableGenerationCount());
        AudienceMembershipProjection audiences = new AudienceMembershipProjection();
        audiences.update("tenant-a", "audience-v1", "subject-1", true, 1, NOW.plusSeconds(600));
        SigningKeyRing offerKeys = new SigningKeyRing("offer-key", Ed25519.generateKeyPair());
        DecisionApplicationService service = new DecisionApplicationService(registry, audiences, offerKeys,
                Clock.fixed(NOW, ZoneOffset.UTC), new TenantBulkhead(2, Duration.ofMillis(10)));
        TenantScope scope = new TenantScope(new TenantId("tenant-a"), Set.of("org-a"), Set.of("shop-1"),
                "decision-client", Set.of("decision:evaluate"));
        Cart cart = new Cart("CNY", List.of(new CartLine("line-1", "sku-1", "shop-1", "home", "brand-1",
                1, new Money("CNY", 10_000), new Money("CNY", 1_000), false)));
        DecisionApplicationService.DecisionRequest request = new DecisionApplicationService.DecisionRequest(
                "request-1", "idem-request-1", "org-a", "order-1", "subject-1", "CHECKOUT", NOW, cart,
                Set.of("audience-v1"));

        DecisionApplicationService.DecisionResponse response = service.evaluate(scope, request);

        assertEquals(8_000, response.pricing().payable().minorUnits());
        assertFalse(response.offerToken().isBlank());
        OfferTokenClaims claims = OfferTokenCodec.verify(response.offerToken(), offerKeys::publicKey,
                new TenantId("tenant-a"), cart.digest(), Clock.fixed(NOW, ZoneOffset.UTC));
        assertEquals(2_000, claims.totalDiscountMinorUnits());
        assertTrue(response.candidates().stream().allMatch(trace -> trace.outcome().equals("ELIGIBLE")));
    }

    private static ReleaseManifest manifest(String checksum, String sourceDigest, String artifactSignature) {
        ArtifactReference artifact = new ArtifactReference("artifact-1", "OFFER_POLICY", "s3://artifacts/a1",
                checksum, sourceDigest, "compiler-key", artifactSignature, "marketing-offer-policy/1",
                "offer-definition", 1);
        return new ReleaseManifest("manifest-1", new TenantId("tenant-a"), "prod", "cell-a", "decision", "main",
                1, 0, List.of(), List.of(), List.of(artifact), Map.of("terms", "terms-v1"), 0,
                NOW.minusSeconds(1), NOW.plusSeconds(3600), "release-manager", List.of("approval-1"), NOW, "");
    }

    private static OfferCandidate candidate() {
        return new OfferCandidate("offer-1", "benefit-v1", PriceStage.PLATFORM,
                new PromotionFormula(PromotionKind.FIXED_OFF, 5_000, 2_000, 0, 2_000, 1),
                Set.of(), "order-discount", HitPolicy.BEST, 1, 100, Set.of(),
                List.of(new FundingRule("PLATFORM", "platform", 7_000),
                        new FundingRule("MERCHANT", "shop-1", 3_000)), "OFFER_MATCHED");
    }
}
