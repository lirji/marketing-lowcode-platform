package com.acme.marketing.decision.application;

import com.acme.marketing.contracts.offer.FundingShareClaim;
import com.acme.marketing.contracts.offer.OfferLineClaim;
import com.acme.marketing.contracts.offer.OfferTokenClaims;
import com.acme.marketing.contracts.offer.OfferTokenCodec;
import com.acme.marketing.decision.model.Cart;
import com.acme.marketing.decision.model.OfferCandidate;
import com.acme.marketing.decision.pricing.PricingEngine;
import com.acme.marketing.decision.pricing.PricingResult;
import com.acme.marketing.decision.runtime.AudienceMembershipProjection;
import com.acme.marketing.decision.runtime.OfferPolicy;
import com.acme.marketing.decision.runtime.RuntimeManifestRegistry;
import com.acme.marketing.platform.crypto.SigningKeyRing;
import com.acme.marketing.platform.identity.TenantScope;
import com.acme.marketing.platform.isolation.TenantBulkhead;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

public final class DecisionApplicationService {
    private final RuntimeManifestRegistry manifests;
    private final AudienceMembershipProjection audiences;
    private final SigningKeyRing offerKeys;
    private final Clock clock;
    private final TenantBulkhead bulkhead;
    private final KillSwitchGuard killSwitch;
    private final DecisionBundlePlanResolver bundlePlans;
    private final PricingEngine pricing = new PricingEngine();

    public DecisionApplicationService(RuntimeManifestRegistry manifests, AudienceMembershipProjection audiences,
            SigningKeyRing offerKeys, Clock clock, TenantBulkhead bulkhead) {
        this(manifests, audiences, offerKeys, clock, bulkhead, ignored -> { });
    }

    public DecisionApplicationService(RuntimeManifestRegistry manifests, AudienceMembershipProjection audiences,
            SigningKeyRing offerKeys, Clock clock, TenantBulkhead bulkhead, KillSwitchGuard killSwitch) {
        this(manifests, audiences, offerKeys, clock, bulkhead, killSwitch, null);
    }

    /** 组合解析独立接入，旧构造路径不能默默忽略新凭证。 */
    public DecisionApplicationService(RuntimeManifestRegistry manifests, AudienceMembershipProjection audiences,
            SigningKeyRing offerKeys, Clock clock, TenantBulkhead bulkhead, KillSwitchGuard killSwitch,
            DecisionBundlePlanResolver bundlePlans) {
        this.bundlePlans = bundlePlans;
        this.manifests = manifests;
        this.audiences = audiences;
        this.offerKeys = offerKeys;
        this.clock = clock;
        this.bulkhead = bulkhead;
        this.killSwitch = killSwitch;
    }

    public DecisionResponse evaluate(TenantScope scope, DecisionRequest request) {
        scope.requirePermission("decision:evaluate");
        if (request.organizationId() != null) scope.requireOrganization(request.organizationId());
        request.cart().lines().stream().map(line -> line.shopId()).distinct().forEach(scope::requireShop);
        killSwitch.check(scope.tenantId().value());
        return bulkhead.execute(scope.tenantId(), () -> evaluateNow(scope, request));
    }

    private DecisionResponse evaluateNow(TenantScope scope, DecisionRequest request) {
        long started = System.nanoTime();
        Instant now = clock.instant();
        if (request.occurredAt().isBefore(now.minusSeconds(300))
                || request.occurredAt().isAfter(now.plusSeconds(300))) {
            throw new IllegalArgumentException("decision occurredAt is outside the accepted clock window");
        }
        RuntimeManifestRegistry.GenerationSnapshot snapshot = request.bundleContext() == null
                ? manifests.route(scope.tenantId().value(), request.subjectToken()) : boundSnapshot(scope, request, now);
        List<OfferCandidate> candidates = new ArrayList<>();
        List<CandidateTrace> trace = new ArrayList<>();
        for (OfferPolicy policy : snapshot.policies()) {
            String rejection = rejection(scope.tenantId().value(), request, policy, now);
            if (rejection == null) {
                candidates.add(policy.candidate());
                trace.add(new CandidateTrace(policy.candidate().offerId(), "ELIGIBLE"));
            } else {
                trace.add(new CandidateTrace(policy.candidate().offerId(), rejection));
            }
        }
        PricingResult result = pricing.price(request.cart(), candidates);
        String quoteId = UUID.randomUUID().toString();
        String token = result.appliedOffers().isEmpty() ? "" : token(scope, request, snapshot, result, quoteId, now);
        long durationMicros = (System.nanoTime() - started) / 1_000;
        return new DecisionResponse(request.requestId(), quoteId, snapshot.generation(), result, token,
                trace, durationMicros, now);
    }

    /** 成功回放仍校验当前停用及签名信任；不重签报价，不赋予新的参与资格。 */
    public void validateBundleReplay(TenantScope scope, DecisionRequest request) {
        if (request.bundleContext() == null) return;
        killSwitch.check(scope.tenantId().value());
        boundSnapshot(scope, request, clock.instant());
    }

    private RuntimeManifestRegistry.GenerationSnapshot boundSnapshot(TenantScope scope, DecisionRequest request, Instant now) {
        if (bundlePlans == null) throw com.acme.marketing.contracts.release.ReleaseBundleProtocolError.PARTICIPATION_INVALID.conflict();
        return bundlePlans.resolve(scope, request, now);
    }

    private String rejection(String tenantId, DecisionRequest request, OfferPolicy policy, Instant now) {
        if (now.isBefore(policy.validFrom()) || !now.isBefore(policy.validTo())) return "OUTSIDE_VALIDITY";
        if (!policy.channels().isEmpty() && !policy.channels().contains(request.channel())) return "CHANNEL_MISMATCH";
        boolean scopeMatches = request.cart().lines().stream().anyMatch(line ->
                (policy.skuIds().isEmpty() || policy.skuIds().contains(line.skuId()))
                        && (policy.categoryIds().isEmpty() || policy.categoryIds().contains(line.categoryId()))
                        && (policy.shopIds().isEmpty() || policy.shopIds().contains(line.shopId())));
        if (!scopeMatches) return "PRODUCT_SCOPE_MISMATCH";
        if (!policy.requiredAudienceSnapshotId().isEmpty()
                && !audiences.isMember(tenantId, policy.requiredAudienceSnapshotId(), request.subjectToken(), now)) {
            return "AUDIENCE_NOT_VERIFIED";
        }
        return null;
    }

    private String token(TenantScope scope, DecisionRequest request,
            RuntimeManifestRegistry.GenerationSnapshot snapshot, PricingResult result, String quoteId, Instant now) {
        List<OfferLineClaim> offers = result.appliedOffers().stream().map(applied -> new OfferLineClaim(
                applied.offerId(), applied.benefitDefinitionVersion(), applied.discount().currency(),
                applied.discount().minorUnits(), 1, applied.funding().stream().map(funding ->
                        new FundingShareClaim(funding.funderType(), funding.funderId(),
                                funding.amount().currency(), funding.amount().minorUnits())).toList())).toList();
        OfferTokenClaims claims = new OfferTokenClaims("offer-decision-service", scope.tenantId(),
                request.organizationId(), request.subjectToken(), request.orderId(),
                request.cart().lines().stream().map(line -> line.shopId()).distinct().sorted().toList(),
                request.cart().digest(), quoteId,
                request.requestId(), snapshot.generation(), snapshot.artifactIds(), offers, snapshot.termsVersion(),
                now, now.plusSeconds(300), UUID.randomUUID().toString(), request.audienceSnapshotIds().stream().sorted().toList());
        return OfferTokenCodec.encode(offerKeys.activeKeyId(), offerKeys.activePrivateKey(), claims);
    }

    public record DecisionRequest(String requestId, String idempotencyKey, String organizationId, String orderId,
            String subjectToken, String channel, Instant occurredAt, Cart cart, Set<String> audienceSnapshotIds,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
            BundleContext bundleContext) {
        /** 缺省新字段不改变旧调用和JSON摘要，旧幂等记录仍可重放。 */
        public DecisionRequest(String requestId, String idempotencyKey, String organizationId, String orderId,
                String subjectToken, String channel, Instant occurredAt, Cart cart, Set<String> audienceSnapshotIds) {
            this(requestId, idempotencyKey, organizationId, orderId, subjectToken, channel, occurredAt, cart, audienceSnapshotIds, null);
        }
        public DecisionRequest {
            if (requestId == null || requestId.isBlank() || idempotencyKey == null || idempotencyKey.isBlank()
                    || organizationId == null || organizationId.isBlank() || subjectToken == null || subjectToken.isBlank()
                    || channel == null || occurredAt == null || cart == null) {
                throw new IllegalArgumentException("decision request is incomplete");
            }
            orderId = orderId == null ? "" : orderId;
            audienceSnapshotIds = Collections.unmodifiableSet(
                    new TreeSet<>(audienceSnapshotIds == null ? Set.of() : audienceSnapshotIds));
        }
    }
    /** 可信业务入口给出活动及参与编号，凭证必须与之相符；主体始终来自原请求。 */
    public record BundleContext(String campaignId, String participationId,
            com.acme.marketing.contracts.release.ReleaseBundleParticipation participation) {
        public BundleContext {
            if (campaignId == null || participationId == null || participation == null
                    || !campaignId.equals(participation.campaignId()) || !participationId.equals(participation.participationId()))
                throw new IllegalArgumentException(com.acme.marketing.contracts.release.ReleaseBundleProtocolError.PARTICIPATION_INVALID.message());
        }
    }
    public record CandidateTrace(String offerId, String outcome) { }
    public record DecisionResponse(String requestId, String quoteId, long generation, PricingResult pricing,
            String offerToken, List<CandidateTrace> candidates, long durationMicros, Instant decidedAt) {
        public DecisionResponse { candidates = List.copyOf(candidates); }
    }
    @FunctionalInterface
    public interface KillSwitchGuard { void check(String tenantId); }
}
