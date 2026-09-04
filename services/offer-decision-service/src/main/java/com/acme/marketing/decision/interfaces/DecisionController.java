package com.acme.marketing.decision.interfaces;

import com.acme.marketing.contracts.release.ActivationDirective;
import com.acme.marketing.contracts.release.ReleaseManifest;
import com.acme.marketing.decision.application.DecisionApplicationService;
import com.acme.marketing.decision.application.DecisionRequestExecutor;
import com.acme.marketing.decision.runtime.AudienceMembershipProjection;
import com.acme.marketing.decision.runtime.RuntimeManifestRegistry;
import com.acme.marketing.decision.runtime.RuntimeAckFactory;
import com.acme.marketing.contracts.release.RuntimeAck;
import com.acme.marketing.platform.web.TenantContextHolder;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class DecisionController {
    private final DecisionApplicationService service;
    private final RuntimeManifestRegistry manifests;
    private final AudienceMembershipProjection audiences;
    private final DecisionRequestExecutor idempotency;
    private final RuntimeAckFactory ackFactory;

    public DecisionController(DecisionApplicationService service, RuntimeManifestRegistry manifests,
            AudienceMembershipProjection audiences, DecisionRequestExecutor idempotency,
            RuntimeAckFactory ackFactory) {
        this.service = service;
        this.manifests = manifests;
        this.audiences = audiences;
        this.idempotency = idempotency;
        this.ackFactory = ackFactory;
    }

    @PostMapping("/decisions:evaluate")
    public DecisionApplicationService.DecisionResponse evaluate(
            @RequestBody DecisionApplicationService.DecisionRequest request) {
        var scope = TenantContextHolder.requireCurrent();
        return idempotency.execute(scope, request);
    }

    @PostMapping("/decisions:batch-evaluate")
    public List<DecisionApplicationService.DecisionResponse> batch(
            @RequestBody List<DecisionApplicationService.DecisionRequest> requests) {
        if (requests.size() > 50) throw new IllegalArgumentException("decision batch exceeds 50 requests");
        var scope = TenantContextHolder.requireCurrent();
        return requests.stream().map(request -> idempotency.execute(scope, request)).toList();
    }

    @PutMapping("/decisions/runtime/manifest")
    public WarmResponse install(@RequestBody WarmRequest request) {
        var scope = TenantContextHolder.requireCurrent();
        scope.requirePermission("runtime:activate");
        byte[] artifact;
        try {
            artifact = Base64.getDecoder().decode(request.policyArtifactBase64());
        } catch (IllegalArgumentException malformed) {
            throw new IllegalArgumentException("policyArtifactBase64 is invalid", malformed);
        }
        RuntimeManifestRegistry.GenerationSnapshot snapshot = manifests.install(
                scope.tenantId().value(), request.keyId(), request.manifest(), artifact);
        return new WarmResponse(snapshot, ackFactory.ready(request.manifest()));
    }

    @PutMapping("/decisions/runtime/activation")
    public RuntimeManifestRegistry.GenerationSnapshot activate(@RequestBody ActivationDirective directive) {
        var scope = TenantContextHolder.requireCurrent();
        scope.requirePermission("runtime:activate");
        return manifests.applyActivation(scope.tenantId().value(), directive);
    }

    @PostMapping("/decisions/runtime/ack")
    public RuntimeAck refreshAck() {
        var scope = TenantContextHolder.requireCurrent();
        scope.requirePermission("runtime:ack");
        return ackFactory.ready(manifests.currentManifest(scope.tenantId().value()));
    }

    @PutMapping("/decisions/runtime/audience-membership")
    public void membership(@RequestBody AudienceUpdate request) {
        var scope = TenantContextHolder.requireCurrent();
        scope.requirePermission("runtime:projection-write");
        audiences.update(scope.tenantId().value(), request.snapshotId(), request.subjectToken(),
                request.member(), request.version(), request.expiresAt());
    }

    public record WarmRequest(String keyId, ReleaseManifest manifest, String policyArtifactBase64) { }
    public record WarmResponse(RuntimeManifestRegistry.GenerationSnapshot generation, RuntimeAck ack) { }
    public record AudienceUpdate(String snapshotId, String subjectToken, boolean member,
            long version, Instant expiresAt) { }

}
