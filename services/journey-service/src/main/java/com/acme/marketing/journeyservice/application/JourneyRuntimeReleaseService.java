package com.acme.marketing.journeyservice.application;

import static com.acme.marketing.platform.time.SqlTime.format;

import com.acme.marketing.contracts.release.ActivationDirective;
import com.acme.marketing.contracts.release.ArtifactReference;
import com.acme.marketing.contracts.release.ReleaseManifest;
import com.acme.marketing.contracts.release.RuntimeAck;
import com.acme.marketing.contracts.release.RuntimeAckSigner;
import com.acme.marketing.contracts.release.PinnedReleaseBundleVerifier;
import com.acme.marketing.contracts.release.ReleaseBundleAck;
import com.acme.marketing.contracts.release.ReleaseBundleAckSigner;
import com.acme.marketing.contracts.release.ReleaseBundlePrepareRequest;
import com.acme.marketing.contracts.release.ReleaseBundleProtocolError;
import com.acme.marketing.journey.JourneyPlan;
import com.acme.marketing.journey.JourneyReleaseVerifier;
import com.acme.marketing.platform.crypto.SigningKeyRing;
import com.acme.marketing.platform.error.ConflictException;
import com.acme.marketing.platform.error.NotFoundException;
import com.acme.marketing.platform.web.TenantContextHolder;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class JourneyRuntimeReleaseService {
    private final JourneyRepository repository;
    private final ObjectMapper mapper;
    private final JourneyReleaseVerifier verifier;
    private final PinnedReleaseBundleVerifier bundleVerifier;
    private final SigningKeyRing ackKeys;
    private final Clock clock;
    private final String runtimeId;
    private final String buildDigest;
    private final long capacity;

    public JourneyRuntimeReleaseService(JourneyRepository repository, ObjectMapper mapper,
            JourneyReleaseVerifier verifier, PinnedReleaseBundleVerifier bundleVerifier,
            @Qualifier("journeyRuntimeAckSigningKeyRing") SigningKeyRing ackKeys, Clock clock,
            @Value("${marketing.runtime.id:journey-local}") String runtimeId,
            @Value("${marketing.runtime.build-digest:development}") String buildDigest,
            @Value("${marketing.runtime.capacity:1000}") long capacity) {
        this.repository = repository;
        this.mapper = mapper;
        this.verifier = verifier;
        this.bundleVerifier = bundleVerifier;
        this.ackKeys = ackKeys;
        this.clock = clock;
        this.runtimeId = runtimeId;
        this.buildDigest = buildDigest;
        this.capacity = capacity;
        if (runtimeId.isBlank() || buildDigest.isBlank() || capacity < 1) {
            throw new IllegalArgumentException("journey runtime identity and capacity are required");
        }
    }

    @Transactional
    public WarmView warm(WarmRequest request) {
        var scope = TenantContextHolder.requireCurrent();
        scope.requirePermission("runtime:warm");
        byte[] payload = decode(request.artifactBase64());
        JourneyReleaseVerifier.VerifiedArtifact artifact = verifyArtifact(scope.tenantId().value(),
                request.releaseKeyId(), request.manifest(), payload);
        JourneyPlan plan = read(payload, JourneyPlan.class);
        assertPlan(plan, artifact.reference());
        ReleaseManifest manifest = request.manifest();
        boolean installed = repository.trySaveGeneration(new JourneyRepository.GenerationWrite(
                scope.tenantId().value(), manifest.environment(), manifest.cell(), manifest.namespace(),
                manifest.generation(), request.releaseKeyId(), json(manifest), artifact.reference().artifactId(),
                payload, manifest.signature(), format(clock.instant())));
        if (!installed) {
            StoredGeneration existing = generation(scope.tenantId().value(), manifest, manifest.generation());
            if (!existing.manifest().signature().equals(manifest.signature())
                    || !Arrays.equals(existing.payload(), payload)) {
                throw new ConflictException("JOURNEY_GENERATION_CONFLICT",
                        "generation already contains another signed journey artifact");
            }
        }
        installDefinition(scope.tenantId().value(), plan, request.releaseKeyId());
        return new WarmView(artifact.reference().artifactId(), plan.journeyId(), plan.version(), ready(manifest, artifact.reference()));
    }

    /** 仅从持久库复核完整组合及本站版本，不重装定义、不推进激活指针。 */
    @Transactional(readOnly = true, timeout = 10)
    public ReleaseBundleAck prepareBundle(ReleaseBundlePrepareRequest request) {
        var scope = TenantContextHolder.requireCurrent();
        scope.requirePermission("runtime:warm");
        bundleVerifier.requireValid(scope.tenantId().value(), request.bundle(), clock.instant());
        ReleaseManifest member = request.member();
        ArtifactReference artifact = verifyInstalledMember(scope.tenantId().value(), member);
        return ReleaseBundleAckSigner.sign(ackKeys.activePrivateKey(), scope.tenantId().value(),
                ReleaseBundleAck.unsigned(request.bundle(), member, ready(member, artifact)));
    }

    /** 仅供已鉴权/验签的组合应用入口复用；验证持久制品和实际安装定义，不签发ACK。 */
    ArtifactReference verifyInstalledMember(String tenantId, ReleaseManifest member) {
        StoredGeneration stored = generation(tenantId, member, member.generation());
        if (!member.equals(stored.manifest())) throw ReleaseBundleProtocolError.MEMBER_MISMATCH.conflict();
        var artifact = verifyArtifact(tenantId, stored.releaseKeyId(), member, stored.payload());
        JourneyPlan plan = read(stored.payload(), JourneyPlan.class);
        assertPlan(plan, artifact.reference());
        String definition = repository.findPlanJson(tenantId, plan.journeyId(), plan.version())
                .orElseThrow(ReleaseBundleProtocolError.MEMBER_NOT_INSTALLED::conflict);
        if (!plan.equals(read(definition, JourneyPlan.class))) throw ReleaseBundleProtocolError.MEMBER_MISMATCH.conflict();
        return artifact.reference();
    }

    private JourneyReleaseVerifier.VerifiedArtifact verifyArtifact(String tenantId, String releaseKeyId,
            ReleaseManifest manifest, byte[] payload) {
        try { return verifier.verifyInstallation(tenantId, releaseKeyId, manifest, payload, clock.instant()); }
        catch (IllegalArgumentException invalid) { throw new ConflictException("JOURNEY_RELEASE_INVALID", invalid.getMessage()); }
    }

    private RuntimeAck ready(ReleaseManifest manifest, ArtifactReference artifact) {
        RuntimeAck unsigned = new RuntimeAck(manifest.manifestId(), manifest.generation(), runtimeId,
                manifest.cell(), RuntimeAck.Status.READY, buildDigest, Set.of(artifact.abi()),
                Set.of(artifact.artifactId()), capacity, clock.instant(), ackKeys.activeKeyId(), "");
        return RuntimeAckSigner.sign(ackKeys.activePrivateKey(), manifest.tenantId().value(), unsigned);
    }

    @Transactional
    public ActiveView activate(ActivationDirective directive) {
        var scope = TenantContextHolder.requireCurrent();
        scope.requirePermission("runtime:activate");
        return applyActivation(scope.tenantId().value(), directive, false);
    }

    @Transactional
    public ActiveView activateFromCoordinator(ActivationDirective directive) {
        return applyActivation(directive.tenantId().value(), directive, true);
    }

    private ActiveView applyActivation(String tenantId, ActivationDirective directive, boolean replayTolerant) {
        StoredGeneration installed = generation(tenantId, directive, directive.generation());
        try {
            verifier.verifyActivation(tenantId, directive, installed.manifest(),
                    replayTolerant ? directive.activatedAt() : clock.instant());
        } catch (IllegalArgumentException invalid) {
            throw new ConflictException("JOURNEY_ACTIVATION_INVALID", invalid.getMessage());
        }
        ensureSlot(directive);
        Slot current = slot(directive, true);
        // HTTP和Coordinator共用持久门禁；旧回执也不能绕过组合对同一物理slot的接管。
        if (current.bundleManaged()) throw com.acme.marketing.contracts.release.ReleaseBundleProtocolError.SLOT_MANAGED.conflict();
        if (directive.activationSequence() < current.activationSequence()) {
            if (replayTolerant) return view(installed, directive);
            throw new ConflictException("ACTIVATION_SEQUENCE_STALE", "journey activation directive is stale");
        }
        if (directive.activationSequence() == current.activationSequence() && current.activationSequence() > 0) {
            if (!directive.signature().equals(current.signature())) {
                throw new ConflictException("ACTIVATION_SEQUENCE_CONFLICT", "activation sequence was reused");
            }
            return view(installed, directive);
        }
        repository.saveActivation(new JourneyRepository.ActivationWrite(tenantId, directive.environment(),
                directive.cell(), directive.namespace(), directive.activationSequence(), directive.generation(),
                directive.signature(), json(directive), format(clock.instant())));
        repository.updateRuntimeSlot(new JourneyRepository.RuntimeSlotActivationWrite(tenantId,
                directive.environment(), directive.cell(), directive.namespace(), directive.generation(),
                directive.activationSequence(), directive.signature(), json(directive), format(clock.instant())));
        return view(installed, directive);
    }

    private void ensureSlot(ActivationDirective directive) {
        repository.tryCreateRuntimeSlot(new JourneyRepository.RuntimeSlotWrite(directive.tenantId().value(),
                directive.environment(), directive.cell(), directive.namespace(), 0, 0, "", "",
                format(clock.instant())));
    }

    private Slot slot(ActivationDirective directive, boolean lock) {
        JourneyRepository.RuntimeSlotRow row = repository.findRuntimeSlot(directive.tenantId().value(),
                directive.environment(), directive.cell(), directive.namespace(), lock)
                .orElseThrow(() -> new IllegalStateException("journey runtime slot was not created"));
        return new Slot(row.desiredGeneration(), row.activationSequence(), row.directiveSignature(), row.bundleManaged());
    }

    private StoredGeneration generation(String tenantId, ReleaseManifest slot, long generation) {
        return generation(tenantId, slot.environment(), slot.cell(), slot.namespace(), generation);
    }

    private StoredGeneration generation(String tenantId, ActivationDirective slot, long generation) {
        return generation(tenantId, slot.environment(), slot.cell(), slot.namespace(), generation);
    }

    private StoredGeneration generation(String tenantId, String environment, String cell, String namespace,
            long generation) {
        JourneyRepository.GenerationRow row = repository.findGeneration(
                        tenantId, environment, cell, namespace, generation)
                .orElseThrow(() -> new NotFoundException("JOURNEY_GENERATION_NOT_WARM",
                        "journey generation is not installed"));
        return new StoredGeneration(row.releaseKeyId(), read(row.manifestJson(), ReleaseManifest.class),
                row.artifactId(), row.artifactPayload());
    }

    private static void assertPlan(JourneyPlan plan, ArtifactReference reference) {
        if (!plan.journeyId().equals(reference.definitionId()) || plan.version() != reference.definitionVersion()
                || plan.nodes().size() > 500 || plan.maxStepsPerSignal() > 1_000 || plan.maxIterations() > 100
                || plan.stateTtl().compareTo(java.time.Duration.ofDays(365)) > 0) {
            throw new ConflictException("JOURNEY_PLAN_INVALID", "journey plan identity or limits are invalid");
        }
    }

    private void installDefinition(String tenantId, JourneyPlan plan, String releaseKeyId) {
        var installed = repository.findPlanJson(tenantId, plan.journeyId(), plan.version());
        String encoded = json(plan);
        if (installed.isPresent()) {
            JourneyPlan existing = read(installed.orElseThrow(), JourneyPlan.class);
            if (!existing.equals(plan)) {
                throw new ConflictException("JOURNEY_VERSION_IMMUTABLE",
                        "journey id and version already identify another plan");
            }
            return;
        }
        boolean saved = repository.trySaveDefinition(new JourneyRepository.DefinitionWrite(tenantId,
                plan.journeyId(), plan.version(), encoded, "ACTIVE", "release:" + releaseKeyId,
                format(clock.instant())));
        if (!saved) {
            var winner = repository.findPlanJson(tenantId, plan.journeyId(), plan.version());
            if (winner.isEmpty() || !read(winner.orElseThrow(), JourneyPlan.class).equals(plan)) {
                throw new ConflictException("JOURNEY_VERSION_IMMUTABLE",
                        "journey id and version were concurrently assigned to another plan");
            }
        }
    }

    private ActiveView view(StoredGeneration generation, ActivationDirective directive) {
        JourneyPlan plan = read(generation.payload(), JourneyPlan.class);
        return new ActiveView(generation.artifactId(), plan.journeyId(), plan.version(), directive.generation(),
                directive.activationSequence(), directive.expiresAt());
    }

    private static byte[] decode(String value) {
        try { return Base64.getDecoder().decode(value); }
        catch (IllegalArgumentException malformed) { throw new IllegalArgumentException("artifactBase64 is invalid", malformed); }
    }
    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (JacksonException failure) { throw new IllegalStateException("journey release cannot be serialized", failure); }
    }
    private <T> T read(byte[] value, Class<T> type) {
        try { return mapper.readValue(value, type); }
        catch (JacksonException failure) { throw new ConflictException("JOURNEY_PLAN_INVALID", "journey plan cannot be decoded"); }
    }
    private <T> T read(String value, Class<T> type) {
        try { return mapper.readValue(value, type); }
        catch (JacksonException failure) { throw new IllegalStateException("stored journey release is invalid", failure); }
    }

    public record WarmRequest(String releaseKeyId, ReleaseManifest manifest, String artifactBase64) { }
    public record WarmView(String artifactId, String journeyId, long journeyVersion, RuntimeAck ack) { }
    public record ActiveView(String artifactId, String journeyId, long journeyVersion, long generation,
            long activationSequence, Instant expiresAt) { }
    private record Slot(long desiredGeneration, long activationSequence, String signature, boolean bundleManaged) { }
    private record StoredGeneration(String releaseKeyId, ReleaseManifest manifest, String artifactId,
            byte[] payload) {
        private StoredGeneration { payload = payload.clone(); }
        @Override public byte[] payload() { return payload.clone(); }
    }
}
