package com.acme.marketing.control.application;

import static com.acme.marketing.platform.time.SqlTime.format;

import com.acme.marketing.contracts.release.ArtifactReference;
import com.acme.marketing.contracts.release.ActivationDirective;
import com.acme.marketing.contracts.release.ActivationDirectiveSigner;
import com.acme.marketing.contracts.artifact.PinnedArtifactVerifier;
import com.acme.marketing.contracts.release.ReleaseManifest;
import com.acme.marketing.contracts.release.ReleaseManifestSigner;
import com.acme.marketing.contracts.release.PinnedRuntimeAckVerifier;
import com.acme.marketing.contracts.release.KillSwitchDirective;
import com.acme.marketing.contracts.release.KillSwitchDirectiveSigner;
import com.acme.marketing.contracts.release.RuntimeAck;
import com.acme.marketing.platform.crypto.SigningKeyRing;
import com.acme.marketing.platform.error.ConflictException;
import com.acme.marketing.platform.error.NotFoundException;
import com.acme.marketing.platform.identity.TenantId;
import com.acme.marketing.platform.identity.TenantScope;
import com.acme.marketing.platform.web.TenantContextHolder;
import com.acme.marketing.lowcode.model.GraphDefinition;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Value;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class ReleaseApplicationService {
    private final ControlRepository repository;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final SigningKeyRing keys;
    private final PinnedArtifactVerifier artifactVerifier;
    private final PinnedRuntimeAckVerifier ackVerifier;
    private final BenefitReleaseGate benefitReleaseGate;
    private final int minimumReadyReplicas;
    private final long minimumReadyCapacity;
    private final long maxReadyCapacityPerRuntime;
    private final String allowedRuntimeBuildDigest;
    private final String activationTopic;
    private final String killSwitchTopic;
    private final ReleaseManifestSigner signer = new ReleaseManifestSigner();

    public ReleaseApplicationService(ControlRepository repository, ObjectMapper mapper, Clock clock,
            SigningKeyRing keys,
            PinnedArtifactVerifier artifactVerifier, PinnedRuntimeAckVerifier ackVerifier,
            BenefitReleaseGate benefitReleaseGate,
            @Value("${marketing.release-policy.minimum-ready-replicas:1}") int minimumReadyReplicas,
            @Value("${marketing.release-policy.minimum-ready-capacity:1}") long minimumReadyCapacity,
            @Value("${marketing.release-policy.max-ready-capacity-per-runtime:1000}") long maxReadyCapacityPerRuntime,
            @Value("${marketing.release-policy.allowed-runtime-build-digest:}") String allowedRuntimeBuildDigest,
            @Value("${marketing.release-outbox.topic:mk.release.activation.v1}") String activationTopic,
            @Value("${marketing.release-outbox.kill-switch-topic:mk.release.kill-switch.v1}") String killSwitchTopic) {
        this.repository = repository;
        this.mapper = mapper;
        this.clock = clock;
        this.keys = keys;
        this.artifactVerifier = artifactVerifier;
        this.ackVerifier = ackVerifier;
        this.benefitReleaseGate = benefitReleaseGate;
        if (minimumReadyReplicas < 1 || minimumReadyCapacity < 1 || maxReadyCapacityPerRuntime < 1
                || activationTopic.isBlank()
                || killSwitchTopic.isBlank()) {
            throw new IllegalArgumentException("release readiness policy must be positive");
        }
        this.minimumReadyReplicas = minimumReadyReplicas;
        this.minimumReadyCapacity = minimumReadyCapacity;
        this.maxReadyCapacityPerRuntime = maxReadyCapacityPerRuntime;
        this.allowedRuntimeBuildDigest = allowedRuntimeBuildDigest == null ? "" : allowedRuntimeBuildDigest.trim();
        this.activationTopic = activationTopic;
        this.killSwitchTopic = killSwitchTopic;
    }

    @Transactional
    public ReleaseView stage(StageReleaseRequest request) {
        var scope = TenantContextHolder.requireCurrent();
        scope.requirePermission("release:write");
        assertApproved(scope, request.definitionId(), request.definitionVersion(),
                request.approvalCaseIds());
        assertBenefitClosure(scope, request);
        assertArtifacts(scope.tenantId().value(), request);
        Slot slot = lockSlot(scope.tenantId().value(), request.environment(), request.cell(),
                request.runtime(), request.namespace());
        if (request.canaryBasisPoints() > 0 && request.canaryBasisPoints() < 10_000
                && slot.stableGeneration() <= 0) {
            throw new ConflictException("CANARY_REQUIRES_STABLE_GENERATION",
                    "partial canary requires an active stable generation");
        }
        long generation = slot.latestGeneration() + 1;
        List<Long> retained = retained(scope.tenantId().value(), request, 10);
        List<Long> canary = request.canaryBasisPoints() > 0 ? List.of(generation) : List.of();
        Instant now = clock.instant();
        ReleaseManifest unsigned = new ReleaseManifest(UUID.randomUUID().toString(), scope.tenantId(),
                request.environment(), request.cell(), request.runtime(), request.namespace(), generation,
                slot.stableGeneration(), canary, retained, request.artifacts(), request.schemaVersions(),
                request.canaryBasisPoints(), request.activationAt() == null ? now : request.activationAt(),
                now.plus(30, ChronoUnit.DAYS), scope.actorId(), request.approvalCaseIds(), now, "");
        ReleaseManifest manifest = signer.sign(unsigned, keys.activePrivateKey());
        repository.saveManifest(new ControlRepository.ManifestWrite(scope.tenantId().value(), manifest.manifestId(),
                manifest.environment(), manifest.cell(), manifest.runtime(), manifest.namespace(),
                manifest.generation(), State.STAGED.name(), json(manifest), format(now)));
        repository.updateSlotLatestGeneration(new ControlRepository.SlotLatestWrite(scope.tenantId().value(),
                request.environment(), request.cell(), request.runtime(), request.namespace(), generation,
                format(now)));
        return new ReleaseView(manifest, State.STAGED, 0, 0, null);
    }

    @Transactional
    public ReleaseView acknowledge(String manifestId, RuntimeAck ack) {
        var scope = TenantContextHolder.requireCurrent();
        scope.requirePermission("runtime:ack");
        StoredManifest stored = stored(scope.tenantId().value(), manifestId, true);
        requireManifestScope(scope, stored.manifest());
        if (!manifestId.equals(ack.manifestId()) || stored.manifest().generation() != ack.generation()
                || !stored.manifest().cell().equals(ack.cell())) {
            throw new ConflictException("ACK_MANIFEST_MISMATCH", "runtime ACK does not match staged manifest");
        }
        if (stored.state() != State.STAGED) {
            throw new ConflictException("ACK_MANIFEST_NOT_STAGED", "runtime ACK only applies to a staged manifest");
        }
        Instant now = clock.instant();
        if (ack.acknowledgedAt().isBefore(now.minus(5, ChronoUnit.MINUTES))
                || ack.acknowledgedAt().isAfter(now.plus(1, ChronoUnit.MINUTES))) {
            throw new ConflictException("ACK_TIME_INVALID", "runtime ACK is stale or in the future");
        }
        if (!ackVerifier.verify(scope.tenantId().value(), ack)) {
            throw new ConflictException("ACK_SIGNATURE_INVALID", "runtime ACK signature is not trusted");
        }
        Set<String> requiredAbis = stored.manifest().artifacts().stream()
                .map(ArtifactReference::abi).collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (!ack.supportedAbis().containsAll(requiredAbis)) {
            throw new ConflictException("ACK_ABI_INCOMPATIBLE", "runtime does not support the artifact closure ABI");
        }
        Set<String> requiredArtifactIds = stored.manifest().artifacts().stream()
                .map(ArtifactReference::artifactId).collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (!ack.warmedArtifactIds().equals(requiredArtifactIds)) {
            throw new ConflictException("ACK_ARTIFACT_CLOSURE_MISMATCH",
                    "runtime did not warm the exact signed artifact closure");
        }
        if (!allowedRuntimeBuildDigest.isEmpty() && !allowedRuntimeBuildDigest.equals(ack.buildDigest())) {
            throw new ConflictException("ACK_BUILD_DIGEST_INVALID", "runtime build is not allowed for this release");
        }
        if (ack.status() == RuntimeAck.Status.READY
                && (ack.capacity() <= 0 || ack.capacity() > maxReadyCapacityPerRuntime)) {
            throw new ConflictException("ACK_CAPACITY_INVALID",
                    "ready runtime capacity is outside the configured per-runtime bound");
        }
        if (ack.status() != RuntimeAck.Status.READY && ack.capacity() != 0) {
            throw new ConflictException("ACK_CAPACITY_INVALID", "non-ready runtime capacity must be zero");
        }
        ControlRepository.RuntimeAckWrite write = new ControlRepository.RuntimeAckWrite(scope.tenantId().value(),
                manifestId, ack.runtimeId(), ack.status().name(), ack.buildDigest(),
                String.join(",", ack.supportedAbis()), String.join(",", ack.warmedArtifactIds()), ack.capacity(),
                format(ack.acknowledgedAt()), ack.signatureKeyId(), ack.signature());
        int updated = repository.updateRuntimeAck(write);
        if (updated == 0) {
            repository.saveRuntimeAck(write);
        }
        return view(scope.tenantId().value(), stored);
    }

    @Transactional
    public ReleaseView activate(String manifestId) {
        var scope = TenantContextHolder.requireCurrent();
        scope.requirePermission("release:activate");
        StoredManifest stored = stored(scope.tenantId().value(), manifestId, true);
        requireManifestScope(scope, stored.manifest());
        if (stored.state() != State.STAGED) {
            throw new ConflictException("MANIFEST_NOT_STAGED", "only staged manifest can be activated");
        }
        Readiness readiness = readiness(scope.tenantId().value(), manifestId);
        if (readiness.readyReplicas() < minimumReadyReplicas || readiness.capacity() < minimumReadyCapacity) {
            throw new ConflictException("RUNTIME_NOT_READY", "runtime ACK quorum or capacity is insufficient");
        }
        Instant now = clock.instant();
        if (stored.manifest().activationAt().isAfter(now)) {
            throw new ConflictException("ACTIVATION_TOO_EARLY", "manifest activation time has not arrived");
        }
        Slot slot = lockSlot(scope.tenantId().value(), stored.manifest().environment(), stored.manifest().cell(),
                stored.manifest().runtime(), stored.manifest().namespace());
        if (stored.manifest().generation() != slot.latestGeneration()
                || stored.manifest().generation() <= slot.desiredGeneration()) {
            throw new ConflictException("ACTIVATION_GENERATION_NOT_FORWARD",
                    "normal activation only accepts the latest generation and cannot downgrade desired state");
        }
        long stableGeneration = stored.manifest().canaryBasisPoints() > 0
                && stored.manifest().canaryBasisPoints() < 10_000
                ? stored.manifest().stableGeneration() : stored.manifest().generation();
        long activationSequence = slot.activationSequence() + 1;
        ActivationDirective directive = directive(stored.manifest(), activationSequence, stableGeneration,
                stored.manifest().canaryBasisPoints(), scope.actorId(), now);
        saveDirective(directive);
        int advanced = repository.compareAndSetSlot(new ControlRepository.SlotAdvanceWrite(
                scope.tenantId().value(), stored.manifest().environment(), stored.manifest().cell(),
                stored.manifest().runtime(), stored.manifest().namespace(), stableGeneration,
                stored.manifest().generation(), activationSequence, format(now), slot.desiredGeneration(),
                slot.activationSequence()));
        if (advanced != 1) {
            throw new ConflictException("ACTIVATION_POINTER_CONFLICT", "release desired state changed concurrently");
        }
        repository.updateManifestState(scope.tenantId().value(), manifestId, State.ACTIVE.name());
        enqueueActivation(directive);
        return new ReleaseView(stored.manifest(), State.ACTIVE, readiness.readyReplicas(), readiness.capacity(), directive);
    }

    @Transactional
    public ReleaseView rollback(String manifestId, long targetGeneration) {
        var scope = TenantContextHolder.requireCurrent();
        scope.requirePermission("release:rollback");
        StoredManifest current = stored(scope.tenantId().value(), manifestId, true);
        requireManifestScope(scope, current.manifest());
        if (current.state() != State.ACTIVE) {
            throw new ConflictException("ROLLBACK_SOURCE_NOT_ACTIVE", "rollback source manifest must be active");
        }
        if (!current.manifest().retainedGenerations().contains(targetGeneration)) {
            throw new ConflictException("GENERATION_NOT_RETAINED", "rollback generation is not retained");
        }
        StoredManifest target = byGeneration(scope.tenantId().value(), current.manifest(), targetGeneration);
        Instant now = clock.instant();
        if (target.state() != State.ACTIVE || !target.manifest().expiresAt().isAfter(now)) {
            throw new ConflictException("ROLLBACK_TARGET_UNAVAILABLE",
                    "rollback target was not previously active or has expired");
        }
        Slot slot = lockSlot(scope.tenantId().value(), current.manifest().environment(), current.manifest().cell(),
                current.manifest().runtime(), current.manifest().namespace());
        if (slot.desiredGeneration() != current.manifest().generation()) {
            throw new ConflictException("ROLLBACK_SOURCE_STALE", "manifest is no longer the desired generation");
        }
        long activationSequence = slot.activationSequence() + 1;
        ActivationDirective directive = directive(target.manifest(), activationSequence, targetGeneration,
                0, scope.actorId(), now);
        saveDirective(directive);
        int rewound = repository.compareAndSetSlot(new ControlRepository.SlotAdvanceWrite(
                scope.tenantId().value(), current.manifest().environment(), current.manifest().cell(),
                current.manifest().runtime(), current.manifest().namespace(), targetGeneration, targetGeneration,
                activationSequence, format(now), slot.desiredGeneration(), slot.activationSequence()));
        if (rewound != 1) {
            throw new ConflictException("ROLLBACK_POINTER_CONFLICT", "release desired state changed concurrently");
        }
        enqueueActivation(directive);
        repository.updateManifestState(scope.tenantId().value(), manifestId, State.ROLLED_BACK.name());
        Readiness readiness = readiness(scope.tenantId().value(), target.manifest().manifestId());
        return new ReleaseView(target.manifest(), State.ACTIVE, readiness.readyReplicas(), readiness.capacity(), directive);
    }

    @Transactional
    public KillSwitchView setKillSwitch(String namespace, boolean enabled, String reason) {
        var scope = TenantContextHolder.requireCurrent();
        scope.requirePermission("release:kill-switch");
        Instant now = clock.instant();
        repository.tryCreateKillSwitch(new ControlRepository.KillSwitchWrite(scope.tenantId().value(), namespace,
                0, false, "INITIAL", scope.actorId(), format(now), "{}"));
        Long current = repository.findKillSwitchSequenceForUpdate(scope.tenantId().value(), namespace).orElse(null);
        if (current == null) throw new IllegalStateException("kill switch state was not created");
        long sequence = Math.addExact(current, 1);
        KillSwitchDirective unsigned = new KillSwitchDirective(UUID.randomUUID().toString(), scope.tenantId(),
                namespace, sequence, enabled, reason, now, scope.actorId(), keys.activeKeyId(), "");
        KillSwitchDirective directive = KillSwitchDirectiveSigner.sign(keys.activePrivateKey(), unsigned);
        repository.updateKillSwitch(new ControlRepository.KillSwitchWrite(scope.tenantId().value(), namespace,
                sequence, enabled, reason, scope.actorId(), format(now), json(directive)));
        enqueueKillSwitch(directive);
        return new KillSwitchView(namespace, enabled, reason, scope.actorId(), now, sequence, directive);
    }

    public List<ReleaseView> manifests() {
        var scope = TenantContextHolder.requireCurrent();
        scope.requirePermission("release:read");
        List<StoredManifest> stored = repository.findManifests(scope.tenantId().value(), 50).stream()
                .map(this::storedManifest).toList();
        List<ReleaseView> views = new ArrayList<>();
        for (StoredManifest item : stored) {
            try {
                requireManifestScope(scope, item.manifest());
                views.add(view(scope.tenantId().value(), item));
            } catch (RuntimeException ignored) {
                // Skip manifests whose campaign is outside the caller's org/shop scope.
            }
        }
        return views;
    }

    @Transactional
    public ReleaseView desired(String environment, String cell, String runtime, String namespace) {
        var scope = TenantContextHolder.requireCurrent();
        scope.requirePermission("release:read");
        Slot slot = lockSlot(scope.tenantId().value(), environment, cell, runtime, namespace);
        if (slot.desiredGeneration() == 0) {
            throw new NotFoundException("DESIRED_MANIFEST_NOT_FOUND", "no desired manifest for slot");
        }
        StoredManifest stored = byGeneration(scope.tenantId().value(), environment, cell, runtime, namespace,
                slot.desiredGeneration());
        requireManifestScope(scope, stored.manifest());
        return view(scope.tenantId().value(), stored);
    }

    private ReleaseView view(String tenantId, StoredManifest stored) {
        Readiness readiness = readiness(tenantId, stored.manifest().manifestId());
        return new ReleaseView(stored.manifest(), stored.state(), readiness.readyReplicas(), readiness.capacity(),
                latestDirective(tenantId, stored.manifest()));
    }

    private Readiness readiness(String tenantId, String manifestId) {
        Instant leaseFloor = clock.instant().minus(5, ChronoUnit.MINUTES);
        ControlRepository.ReadinessRow row = repository.summarizeReadiness(tenantId, manifestId,
                RuntimeAck.Status.READY.name(), format(leaseFloor));
        return new Readiness(row.readyCount(), row.totalCapacity());
    }

    private void assertApproved(TenantScope scope, String definitionId, long version, List<String> caseIds) {
        List<ControlRepository.ResourceScopeRow> definitions = repository.findDefinitionScopes(
                scope.tenantId().value(), definitionId, version, true);
        if (definitions.size() != 1 || caseIds.isEmpty()) {
            throw new ConflictException("RELEASE_NOT_APPROVED", "approved definition and approval cases are required");
        }
        requireScope(scope, resourceScope(definitions.getFirst()));
        for (String caseId : caseIds) {
            int count = repository.countApprovedCase(
                    scope.tenantId().value(), caseId, definitionId, version);
            if (count != 1) {
                throw new ConflictException("APPROVAL_CASE_INVALID", "approval case does not cover release input");
            }
        }
    }

    private void assertArtifacts(String tenantId, StageReleaseRequest request) {
        if (request.artifacts().isEmpty()) {
            throw new ConflictException("ARTIFACT_CLOSURE_EMPTY", "release requires compiled artifacts");
        }
        String approvedSourceDigest = repository.findApprovedSemanticHash(
                tenantId, request.definitionId(), request.definitionVersion()).orElse(null);
        for (ArtifactReference artifact : request.artifacts()) {
            if (!request.definitionId().equals(artifact.definitionId())
                    || request.definitionVersion() != artifact.definitionVersion()) {
                throw new ConflictException("ARTIFACT_DEFINITION_MISMATCH",
                        "artifact is not compiled from the approved definition version");
            }
            if (!artifact.sourceDigest().equals(approvedSourceDigest)) {
                throw new ConflictException("ARTIFACT_SOURCE_MISMATCH",
                        "artifact was not compiled from the frozen approved source");
            }
            if (!artifactVerifier.verify(tenantId, artifact)) {
                throw new ConflictException("ARTIFACT_SIGNATURE_INVALID", "artifact compiler attestation is invalid");
            }
        }
        if ("decision".equals(request.runtime()) && (request.artifacts().size() != 1
                || request.artifacts().stream().filter(artifact -> "OFFER_POLICY".equals(artifact.type())
                        && "marketing-offer-policy/1".equals(artifact.abi())).count() != 1)) {
            throw new ConflictException("DECISION_ARTIFACT_CLOSURE_INVALID",
                    "decision release requires exactly one compatible offer policy artifact");
        }
        if ("journey".equals(request.runtime()) && (request.canaryBasisPoints() != 0
                || request.artifacts().size() != 1
                || request.artifacts().stream().filter(artifact -> "JOURNEY_PLAN".equals(artifact.type())
                        && "marketing-journey-plan/1".equals(artifact.abi())).count() != 1)) {
            throw new ConflictException("JOURNEY_ARTIFACT_CLOSURE_INVALID",
                    "journey release requires one compiled plan and full stable activation");
        }
    }

    /**
     * 从已经审核冻结的图中提取权益版本，服务端核验发布资格；不信任控制台另传一份 closure。
     */
    private void assertBenefitClosure(TenantScope scope, StageReleaseRequest request) {
        if (!"decision".equals(request.runtime())) return;
        String graphJson = repository.findApprovedGraphJson(scope.tenantId().value(),
                request.definitionId(), request.definitionVersion()).orElse(null);
        if (graphJson == null) {
            throw new ConflictException("RELEASE_NOT_APPROVED", "approved release graph is unavailable");
        }
        GraphDefinition graph;
        try {
            graph = mapper.readValue(graphJson, GraphDefinition.class);
        } catch (JacksonException invalid) {
            throw new IllegalStateException("approved release graph is invalid", invalid);
        }
        Set<String> references = graph.nodes().stream()
                .filter(node -> "offer.fixed".equals(node.stableTypeId())
                        || "offer.percentage".equals(node.stableTypeId()))
                .map(node -> node.config().get("benefitDefinitionVersion"))
                .filter(value -> value != null && !value.isBlank())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        boolean missingReference = graph.nodes().stream()
                .filter(node -> "offer.fixed".equals(node.stableTypeId())
                        || "offer.percentage".equals(node.stableTypeId()))
                .anyMatch(node -> node.config().get("benefitDefinitionVersion") == null
                        || node.config().get("benefitDefinitionVersion").isBlank());
        if (references.isEmpty() || missingReference) {
            throw new ConflictException("BENEFIT_RELEASE_UNBOUND",
                    "every offer artifact must reference a BenefitDefinition version bound to an ACTIVE SKU");
        }
        benefitReleaseGate.assertReleasable(scope, Set.copyOf(references));
    }

    private Slot lockSlot(String tenantId, String environment, String cell, String runtime, String namespace) {
        List<Slot> slots = slotQuery(tenantId, environment, cell, runtime, namespace, true);
        if (slots.isEmpty()) {
            repository.tryCreateReleaseSlot(new ControlRepository.ReleaseSlotWrite(tenantId, environment, cell,
                    runtime, namespace, 0, 0, 0, 0, format(clock.instant())));
            slots = slotQuery(tenantId, environment, cell, runtime, namespace, true);
        }
        if (slots.isEmpty()) throw new IllegalStateException("release slot was not created");
        return slots.getFirst();
    }

    private List<Slot> slotQuery(String tenantId, String environment, String cell, String runtime,
            String namespace, boolean lock) {
        return repository.findReleaseSlot(tenantId, environment, cell, runtime, namespace, lock)
                .map(row -> List.of(new Slot(row.stableGeneration(), row.desiredGeneration(),
                        row.latestGeneration(), row.activationSequence())))
                .orElseGet(List::of);
    }

    private List<Long> retained(String tenantId, StageReleaseRequest request, int limit) {
        return repository.findRetainedGenerations(tenantId, request.environment(), request.cell(), request.runtime(),
                request.namespace(), limit);
    }

    private StoredManifest stored(String tenantId, String manifestId, boolean lock) {
        return repository.findManifest(tenantId, manifestId, lock).map(this::storedManifest)
                .orElseThrow(() -> new NotFoundException("MANIFEST_NOT_FOUND", "release manifest not found"));
    }

    private StoredManifest byGeneration(String tenantId, ReleaseManifest slot, long generation) {
        return byGeneration(tenantId, slot.environment(), slot.cell(), slot.runtime(), slot.namespace(), generation);
    }

    private StoredManifest byGeneration(String tenantId, String environment, String cell, String runtime,
            String namespace, long generation) {
        return repository.findManifestByGeneration(tenantId, environment, cell, runtime, namespace, generation)
                .map(this::storedManifest)
                .orElseThrow(() -> new NotFoundException("GENERATION_NOT_FOUND", "release generation not found"));
    }

    private StoredManifest storedManifest(ControlRepository.ManifestRow row) {
        return new StoredManifest(read(row.manifestJson()), State.valueOf(row.stateName()));
    }

    private String json(ReleaseManifest manifest) {
        try { return mapper.writeValueAsString(manifest); }
        catch (JacksonException failure) { throw new IllegalStateException("manifest cannot be serialized", failure); }
    }

    private ReleaseManifest read(String json) {
        try { return mapper.readValue(json, ReleaseManifest.class); }
        catch (JacksonException failure) { throw new IllegalStateException("stored manifest is invalid", failure); }
    }

    private ActivationDirective directive(ReleaseManifest manifest, long sequence, long stableGeneration,
            int canaryBasisPoints, String actorId, Instant now) {
        Instant expiry = manifest.expiresAt().isBefore(now.plus(30, ChronoUnit.DAYS))
                ? manifest.expiresAt() : now.plus(30, ChronoUnit.DAYS);
        ActivationDirective unsigned = new ActivationDirective(UUID.randomUUID().toString(), manifest.tenantId(),
                manifest.manifestId(), manifest.environment(), manifest.cell(), manifest.runtime(),
                manifest.namespace(), sequence, manifest.generation(), stableGeneration, canaryBasisPoints,
                manifest.signature(), now, expiry, actorId, keys.activeKeyId(), "");
        return ActivationDirectiveSigner.sign(keys.activePrivateKey(), unsigned);
    }

    private void saveDirective(ActivationDirective directive) {
        repository.saveDirective(new ControlRepository.DirectiveWrite(directive.tenantId().value(),
                directive.directiveId(), directive.environment(), directive.cell(), directive.runtime(),
                directive.namespace(), directive.activationSequence(), directive.generation(), json(directive),
                format(directive.activatedAt())));
    }

    private void enqueueActivation(ActivationDirective directive) {
        String partitionKey = String.join(":", directive.tenantId().value(), directive.environment(),
                directive.cell(), directive.runtime(), directive.namespace());
        repository.saveOutbox(new ControlRepository.OutboxWrite(directive.tenantId().value(),
                directive.directiveId(), "ReleaseSlot", partitionKey, "RUNTIME_ACTIVATION_DIRECTIVE",
                json(directive), format(directive.activatedAt()), null, activationTopic, partitionKey,
                directive.activationSequence(), 0, format(directive.activatedAt()), ""));
    }

    private void enqueueKillSwitch(KillSwitchDirective directive) {
        String partitionKey = "kill:" + directive.streamKey();
        repository.saveOutbox(new ControlRepository.OutboxWrite(directive.tenantId().value(),
                directive.directiveId(), "KillSwitch", directive.streamKey(), "KILL_SWITCH_DIRECTIVE",
                json(directive), format(directive.activatedAt()), null, killSwitchTopic, partitionKey,
                directive.switchSequence(), 0, format(directive.activatedAt()), ""));
    }

    private ActivationDirective latestDirective(String tenantId, ReleaseManifest manifest) {
        return repository.findLatestDirectiveJson(tenantId, manifest.environment(), manifest.cell(),
                        manifest.runtime(), manifest.namespace(), manifest.generation())
                .map(value -> read(value, ActivationDirective.class)).orElse(null);
    }

    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (JacksonException failure) { throw new IllegalStateException("release object cannot be serialized", failure); }
    }

    private <T> T read(String value, Class<T> type) {
        try { return mapper.readValue(value, type); }
        catch (JacksonException failure) { throw new IllegalStateException("stored release object is invalid", failure); }
    }

    private void requireManifestScope(TenantScope scope, ReleaseManifest manifest) {
        ArtifactReference artifact = manifest.artifacts().getFirst();
        List<ControlRepository.ResourceScopeRow> rows = repository.findDefinitionScopes(scope.tenantId().value(),
                artifact.definitionId(), artifact.definitionVersion(), false);
        if (rows.size() != 1) throw new NotFoundException("RELEASE_DEFINITION_NOT_FOUND",
                "release definition scope is unavailable");
        requireScope(scope, resourceScope(rows.getFirst()));
    }

    private static ResourceScope resourceScope(ControlRepository.ResourceScopeRow row) {
        return new ResourceScope(row.organizationId(), row.shopId());
    }

    private static void requireScope(TenantScope scope, ResourceScope resource) {
        scope.requireOrganization(resource.organizationId());
        if (resource.shopId() != null && !resource.shopId().isBlank()) scope.requireShop(resource.shopId());
    }

    public enum State { STAGED, ACTIVE, ROLLED_BACK, REVOKED }
    private record Slot(long stableGeneration, long desiredGeneration, long latestGeneration,
            long activationSequence) { }
    private record StoredManifest(ReleaseManifest manifest, State state) { }
    private record Readiness(int readyReplicas, long capacity) { }
    private record ResourceScope(String organizationId, String shopId) { }
    public record ReleaseView(ReleaseManifest manifest, State state, int readyReplicas, long readyCapacity,
            ActivationDirective activationDirective) { }
    public record StageReleaseRequest(String definitionId, long definitionVersion, String environment, String cell,
            String runtime, String namespace, List<ArtifactReference> artifacts, Map<String, String> schemaVersions,
            int canaryBasisPoints, Instant activationAt, List<String> approvalCaseIds) {
        public StageReleaseRequest {
            if (definitionId == null || definitionId.isBlank() || definitionVersion < 1
                    || environment == null || environment.isBlank() || cell == null || cell.isBlank()
                    || runtime == null || runtime.isBlank() || namespace == null || namespace.isBlank()
                    || canaryBasisPoints < 0 || canaryBasisPoints > 10_000) {
                throw new IllegalArgumentException("release request is invalid");
            }
            artifacts = List.copyOf(artifacts == null ? List.of() : artifacts);
            schemaVersions = Map.copyOf(schemaVersions == null ? Map.of() : schemaVersions);
            approvalCaseIds = List.copyOf(approvalCaseIds == null ? List.of() : approvalCaseIds);
        }
    }
    public record KillSwitchView(String namespace, boolean enabled, String reason, String updatedBy,
            Instant updatedAt, long sequence, KillSwitchDirective directive) { }
}
