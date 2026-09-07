package com.acme.marketing.decision.runtime;

import com.acme.marketing.contracts.artifact.PinnedArtifactVerifier;
import com.acme.marketing.contracts.release.ActivationDirective;
import com.acme.marketing.contracts.release.ArtifactReference;
import com.acme.marketing.contracts.release.ReleaseManifest;
import com.acme.marketing.platform.crypto.Digests;
import com.acme.marketing.platform.crypto.StableBucket;
import com.acme.marketing.platform.error.ConflictException;
import com.acme.marketing.platform.error.NotFoundException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/** Immutable hot cache. Installing bytes never changes traffic; only a signed directive can activate them. */
public final class RuntimeManifestRegistry {
    private static final Logger LOGGER = LoggerFactory.getLogger(RuntimeManifestRegistry.class);
    private static final int MAX_CANDIDATES = 1_000;
    private static final int MAX_POLICY_ARTIFACT_BYTES = 1_048_576;
    private static final String POLICY_ARTIFACT_TYPE = "OFFER_POLICY";
    private static final String POLICY_ARTIFACT_ABI = "marketing-offer-policy/1";

    private final ManifestVerifier manifestVerifier;
    private final ActivationDirectiveVerifier activationVerifier;
    private final PinnedArtifactVerifier artifactVerifier;
    private final byte[] routingSecret;
    private final RuntimeSlot slot;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final RuntimeStateStore stateStore;
    private final ConcurrentHashMap<SlotGeneration, GenerationSnapshot> generations = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ActiveRouting> active = new ConcurrentHashMap<>();

    public RuntimeManifestRegistry(ManifestVerifier manifestVerifier, PinnedArtifactVerifier artifactVerifier,
            byte[] routingSecret, RuntimeSlot slot, ObjectMapper mapper, Clock clock) {
        this(manifestVerifier, (keyId, directive) -> false, artifactVerifier, routingSecret, slot, mapper, clock,
                new InMemoryRuntimeStateStore());
    }

    public RuntimeManifestRegistry(ManifestVerifier manifestVerifier,
            ActivationDirectiveVerifier activationVerifier, PinnedArtifactVerifier artifactVerifier,
            byte[] routingSecret, RuntimeSlot slot, ObjectMapper mapper, Clock clock) {
        this(manifestVerifier, activationVerifier, artifactVerifier, routingSecret, slot, mapper, clock,
                new InMemoryRuntimeStateStore());
    }

    public RuntimeManifestRegistry(ManifestVerifier manifestVerifier,
            ActivationDirectiveVerifier activationVerifier, PinnedArtifactVerifier artifactVerifier,
            byte[] routingSecret, RuntimeSlot slot, ObjectMapper mapper, Clock clock,
            RuntimeStateStore stateStore) {
        this.manifestVerifier = manifestVerifier;
        this.activationVerifier = activationVerifier;
        this.artifactVerifier = artifactVerifier;
        this.routingSecret = routingSecret.clone();
        this.slot = slot;
        this.mapper = mapper;
        this.clock = clock;
        this.stateStore = stateStore;
    }

    /** Verifies and persists an immutable generation without changing the active traffic pointer. */
    public synchronized GenerationSnapshot install(String expectedTenantId, String keyId, ReleaseManifest manifest,
            byte[] policyArtifactBytes) {
        GenerationSnapshot snapshot = verifyAndDecode(expectedTenantId, keyId, manifest, policyArtifactBytes);
        validateStableInstalled(expectedTenantId, manifest);
        stateStore.install(keyId, manifest, policyArtifactBytes);
        generations.put(new SlotGeneration(manifest.slotKey(), manifest.generation()), snapshot);
        return snapshot;
    }

    /** Compatibility alias whose semantics are deliberately warm-only. */
    public GenerationSnapshot activate(String expectedTenantId, String keyId, ReleaseManifest manifest,
            byte[] policyArtifactBytes) {
        return install(expectedTenantId, keyId, manifest, policyArtifactBytes);
    }

    /** Applies a separately signed, monotonic traffic directive to an already installed generation. */
    public synchronized GenerationSnapshot applyActivation(String expectedTenantId, ActivationDirective directive) {
        RuntimeStateStore.StoredGeneration stored = stateStore.generation(slot, expectedTenantId,
                        directive.generation())
                .orElseThrow(() -> new ConflictException("ACTIVATION_GENERATION_NOT_INSTALLED",
                        "activation references a generation that is not installed"));
        ActiveRouting routing = validateDesired(expectedTenantId,
                new RuntimeStateStore.DesiredState(stored, directive));
        stateStore.activate(directive);
        activateLocal(routing);
        return routing.primary();
    }

    /** Kafka replay boundary: trusted older directives are acknowledged as no-ops instead of blocking a partition. */
    public synchronized void applyActivationNotification(String expectedTenantId, ActivationDirective directive) {
        validateActivationEnvelope(expectedTenantId, directive);
        RuntimeStateStore.DesiredState current = stateStore.desired(slot, expectedTenantId).orElse(null);
        if (current != null && directive.activationSequence() <= current.directive().activationSequence()) {
            if (directive.activationSequence() == current.directive().activationSequence()
                    && !directive.signature().equals(current.directive().signature())) {
                throw new ConflictException("ACTIVATION_SEQUENCE_CONFLICT", "activation sequence was reused");
            }
            return;
        }
        applyActivation(expectedTenantId, directive);
    }

    private void validateActivationEnvelope(String expectedTenantId, ActivationDirective directive) {
        if (!expectedTenantId.equals(directive.tenantId().value())) {
            throw new ConflictException("ACTIVATION_TENANT_MISMATCH", "activation belongs to another tenant");
        }
        if (!activationVerifier.verify(directive.signatureKeyId(), directive)) {
            throw new ConflictException("ACTIVATION_SIGNATURE_INVALID", "activation directive is not trusted");
        }
        if (!slot.matches(directive) || !"decision".equals(directive.runtime())) {
            throw new ConflictException("ACTIVATION_SLOT_MISMATCH", "activation belongs to another runtime slot");
        }
    }

    private GenerationSnapshot verifyAndDecode(String expectedTenantId, String keyId, ReleaseManifest manifest,
            byte[] policyArtifactBytes) {
        if (!expectedTenantId.equals(manifest.tenantId().value())) {
            throw new ConflictException("MANIFEST_TENANT_MISMATCH", "manifest belongs to another tenant");
        }
        if (!manifestVerifier.verify(keyId, manifest)) {
            throw new ConflictException("MANIFEST_SIGNATURE_INVALID", "manifest signature is not trusted");
        }
        if (!slot.matches(manifest) || !"decision".equals(manifest.runtime())) {
            throw new ConflictException("MANIFEST_SLOT_MISMATCH", "manifest belongs to another runtime slot");
        }
        if (!manifest.expiresAt().isAfter(clock.instant())) {
            throw new ConflictException("MANIFEST_TIME_INVALID", "manifest has expired");
        }
        if (policyArtifactBytes == null || policyArtifactBytes.length == 0
                || policyArtifactBytes.length > MAX_POLICY_ARTIFACT_BYTES) {
            throw new ConflictException("POLICY_ARTIFACT_SIZE_INVALID", "policy artifact is empty or exceeds one MiB");
        }
        ArtifactReference policyReference = policyReference(manifest);
        if (!artifactVerifier.verify(expectedTenantId, policyReference)) {
            throw new ConflictException("POLICY_ARTIFACT_SIGNATURE_INVALID",
                    "policy artifact is not attested by a trusted compiler key");
        }
        String actualChecksum = "sha256:" + Digests.sha256Hex(policyArtifactBytes);
        if (!policyReference.checksum().equals(actualChecksum)) {
            throw new ConflictException("POLICY_ARTIFACT_CHECKSUM_MISMATCH",
                    "policy bytes do not match the signed release manifest");
        }
        List<OfferPolicy> policies = decode(policyArtifactBytes).policies();
        if (policies.isEmpty() || policies.size() > MAX_CANDIDATES
                || policies.stream().map(policy -> policy.candidate().offerId()).distinct().count() != policies.size()) {
            throw new ConflictException("POLICY_ARTIFACT_INVALID", "policy artifact must contain unique bounded offers");
        }
        return new GenerationSnapshot(manifest.generation(), manifest.artifacts().stream()
                .map(ArtifactReference::artifactId).toList(),
                manifest.schemaVersions().getOrDefault("terms", "terms-v1"), policies);
    }

    private ActiveRouting validateDesired(String expectedTenantId, RuntimeStateStore.DesiredState desired) {
        ActivationDirective directive = desired.directive();
        ReleaseManifest manifest = desired.generation().manifest();
        validateActivationEnvelope(expectedTenantId, directive);
        if (directive.activatedAt().isAfter(clock.instant().plusSeconds(60))
                || !directive.expiresAt().isAfter(clock.instant())) {
            throw new ConflictException("ACTIVATION_TIME_INVALID", "activation directive is not current");
        }
        if (directive.generation() != manifest.generation()
                || !directive.manifestId().equals(manifest.manifestId())
                || !directive.manifestSignature().equals(manifest.signature())
                || directive.expiresAt().isAfter(manifest.expiresAt())) {
            throw new ConflictException("ACTIVATION_MANIFEST_MISMATCH",
                    "activation does not bind the installed manifest");
        }
        GenerationSnapshot primary = verifyAndDecode(expectedTenantId, desired.generation().releaseKeyId(), manifest,
                desired.generation().artifactPayload());
        GenerationSnapshot stable = primary;
        if (directive.canaryBasisPoints() > 0 && directive.canaryBasisPoints() < 10_000) {
            if (directive.stableGeneration() <= 0 || directive.stableGeneration() == directive.generation()) {
                throw new ConflictException("STABLE_GENERATION_NOT_AVAILABLE",
                        "partial canary requires a distinct stable generation");
            }
            RuntimeStateStore.StoredGeneration storedStable = stateStore.generation(
                            slot, expectedTenantId, directive.stableGeneration())
                    .orElseThrow(() -> new ConflictException("STABLE_GENERATION_NOT_AVAILABLE",
                            "activation references a missing stable generation"));
            stable = verifyAndDecode(expectedTenantId, storedStable.releaseKeyId(), storedStable.manifest(),
                    storedStable.artifactPayload());
        } else if (directive.canaryBasisPoints() == 0
                && directive.stableGeneration() != directive.generation()) {
            throw new ConflictException("ACTIVATION_STABLE_MISMATCH",
                    "non-canary activation must identify its generation as stable");
        }
        return new ActiveRouting(manifest, directive, primary, stable);
    }

    private void activateLocal(ActiveRouting routing) {
        String slotKey = routing.manifest().slotKey();
        ActiveRouting current = active.get(slotKey);
        if (current != null && routing.directive().activationSequence() < current.directive().activationSequence()) {
            return;
        }
        generations.put(new SlotGeneration(slotKey, routing.primary().generation()), routing.primary());
        generations.put(new SlotGeneration(slotKey, routing.stable().generation()), routing.stable());
        active.put(slotKey, routing);
        generations.keySet().removeIf(key -> key.slotKey().equals(slotKey)
                && key.generation() != routing.primary().generation()
                && key.generation() != routing.stable().generation()
                && !routing.manifest().retainedGenerations().contains(key.generation()));
    }

    public GenerationSnapshot route(String tenantId, String targetingKey) {
        String slotKey = slot.key(tenantId);
        ActiveRouting routing = active.get(slotKey);
        if (routing == null) {
            throw new NotFoundException("ACTIVE_MANIFEST_NOT_FOUND", "no decision generation is active");
        }
        if (routing.directive().activatedAt().isAfter(clock.instant())
                || !routing.directive().expiresAt().isAfter(clock.instant())
                || !routing.manifest().expiresAt().isAfter(clock.instant())) {
            throw new NotFoundException("ACTIVE_MANIFEST_EXPIRED", "active decision generation is not current");
        }
        long generation = routing.directive().generation();
        int canary = routing.directive().canaryBasisPoints();
        if (canary > 0 && canary < 10_000) {
            int bucket = StableBucket.assign(routingSecret, slotKey + ':' + targetingKey, 10_000);
            generation = bucket < canary ? routing.directive().generation() : routing.directive().stableGeneration();
        }
        GenerationSnapshot snapshot = generations.get(new SlotGeneration(slotKey, generation));
        if (snapshot == null) {
            throw new NotFoundException("GENERATION_NOT_WARM", "selected generation is not warm");
        }
        return snapshot;
    }

    /** Pulls pointer metadata first and reloads only changed desired state, preserving last-known-good per tenant. */
    public synchronized int reconcile() {
        int activated = 0;
        for (RuntimeStateStore.DesiredPointer pointer : stateStore.desiredPointers(slot)) {
            ActiveRouting current = active.get(slot.key(pointer.tenantId()));
            if (current != null
                    && current.directive().activationSequence() == pointer.activationSequence()) continue;
            try {
                RuntimeStateStore.DesiredState desired = stateStore.desired(slot, pointer.tenantId())
                        .orElseThrow(() -> new ConflictException("DESIRED_STATE_NOT_FOUND",
                                "runtime pointer has no installed desired state"));
                activateLocal(validateDesired(pointer.tenantId(), desired));
                activated++;
            } catch (RuntimeException failure) {
                LOGGER.warn("rejected desired decision state for tenant {}; retaining last-known-good",
                        pointer.tenantId(), failure);
            }
        }
        return activated;
    }

    public int loadedDesiredCount() {
        return active.size();
    }

    /**
     * 返回当前确实可路由的 generation 数量。readiness 不能只看已缓存 map 大小，
     * 因为 manifest 或 activation 过期后，Decision 已无法安全接流。
     */
    public int usableGenerationCount() {
        Instant now = clock.instant();
        return (int) active.entrySet().stream().filter(entry -> {
            ActiveRouting routing = entry.getValue();
            if (routing.directive().activatedAt().isAfter(now)
                    || !routing.directive().expiresAt().isAfter(now)
                    || !routing.manifest().expiresAt().isAfter(now)) {
                return false;
            }
            long primaryGeneration = routing.directive().generation();
            if (!generations.containsKey(new SlotGeneration(entry.getKey(), primaryGeneration))) return false;
            int canary = routing.directive().canaryBasisPoints();
            return canary <= 0 || canary >= 10_000
                    || generations.containsKey(new SlotGeneration(
                            entry.getKey(), routing.directive().stableGeneration()));
        }).count();
    }

    public ReleaseManifest currentManifest(String tenantId) {
        ActiveRouting routing = active.get(slot.key(tenantId));
        if (routing == null) {
            throw new NotFoundException("ACTIVE_MANIFEST_NOT_FOUND", "no decision generation is active");
        }
        return routing.manifest();
    }

    private ArtifactReference policyReference(ReleaseManifest manifest) {
        List<ArtifactReference> matches = manifest.artifacts().stream()
                .filter(reference -> POLICY_ARTIFACT_TYPE.equals(reference.type())
                        && POLICY_ARTIFACT_ABI.equals(reference.abi()))
                .toList();
        if (matches.size() != 1 || manifest.artifacts().size() != 1) {
            throw new ConflictException("POLICY_ARTIFACT_REFERENCE_INVALID",
                    "decision manifest must contain exactly one compatible offer policy artifact");
        }
        return matches.getFirst();
    }

    private OfferPolicyArtifact decode(byte[] bytes) {
        try {
            return mapper.readValue(bytes, OfferPolicyArtifact.class);
        } catch (JacksonException | IllegalArgumentException failure) {
            throw new ConflictException("POLICY_ARTIFACT_INVALID", "policy artifact cannot be decoded");
        }
    }

    private void validateStableInstalled(String tenantId, ReleaseManifest manifest) {
        if (manifest.canaryBasisPoints() <= 0 || manifest.canaryBasisPoints() >= 10_000) return;
        if (manifest.stableGeneration() <= 0 || manifest.stableGeneration() >= manifest.generation()
                || stateStore.generation(slot, tenantId, manifest.stableGeneration()).isEmpty()) {
            throw new ConflictException("STABLE_GENERATION_NOT_WARM",
                    "partial canary requires a previously installed stable generation");
        }
    }

    public record RuntimeSlot(String environment, String cell, String namespace) {
        public RuntimeSlot {
            if (environment == null || environment.isBlank() || cell == null || cell.isBlank()
                    || namespace == null || namespace.isBlank()) {
                throw new IllegalArgumentException("runtime slot is incomplete");
            }
        }
        public boolean matches(ReleaseManifest manifest) {
            return environment.equals(manifest.environment()) && cell.equals(manifest.cell())
                    && namespace.equals(manifest.namespace());
        }
        public boolean matches(ActivationDirective directive) {
            return environment.equals(directive.environment()) && cell.equals(directive.cell())
                    && namespace.equals(directive.namespace());
        }
        public String key(String tenantId) {
            return String.join(":", tenantId, environment, cell, "decision", namespace);
        }
    }

    public record GenerationSnapshot(long generation, List<String> artifactIds,
            String termsVersion, List<OfferPolicy> policies) {
        public GenerationSnapshot {
            artifactIds = List.copyOf(artifactIds);
            policies = List.copyOf(policies);
        }
    }

    private record ActiveRouting(ReleaseManifest manifest, ActivationDirective directive,
            GenerationSnapshot primary, GenerationSnapshot stable) { }
    private record SlotGeneration(String slotKey, long generation) { }
}
