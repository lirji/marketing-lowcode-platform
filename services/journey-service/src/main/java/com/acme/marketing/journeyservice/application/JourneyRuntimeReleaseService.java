package com.acme.marketing.journeyservice.application;

import static com.acme.marketing.platform.time.SqlTime.format;

import com.acme.marketing.contracts.release.ActivationDirective;
import com.acme.marketing.contracts.release.ArtifactReference;
import com.acme.marketing.contracts.release.ReleaseManifest;
import com.acme.marketing.contracts.release.RuntimeAck;
import com.acme.marketing.contracts.release.RuntimeAckSigner;
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
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class JourneyRuntimeReleaseService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final JourneyReleaseVerifier verifier;
    private final SigningKeyRing ackKeys;
    private final Clock clock;
    private final String runtimeId;
    private final String buildDigest;
    private final long capacity;

    public JourneyRuntimeReleaseService(JdbcTemplate jdbc, ObjectMapper mapper, JourneyReleaseVerifier verifier,
            @Qualifier("journeyRuntimeAckSigningKeyRing") SigningKeyRing ackKeys, Clock clock,
            @Value("${marketing.runtime.id:journey-local}") String runtimeId,
            @Value("${marketing.runtime.build-digest:development}") String buildDigest,
            @Value("${marketing.runtime.capacity:1000}") long capacity) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.verifier = verifier;
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
        JourneyReleaseVerifier.VerifiedArtifact artifact;
        try {
            artifact = verifier.verifyInstallation(scope.tenantId().value(), request.releaseKeyId(),
                    request.manifest(), payload, clock.instant());
        } catch (IllegalArgumentException invalid) {
            throw new ConflictException("JOURNEY_RELEASE_INVALID", invalid.getMessage());
        }
        JourneyPlan plan = read(payload, JourneyPlan.class);
        assertPlan(plan, artifact.reference());
        ReleaseManifest manifest = request.manifest();
        try {
            jdbc.update("insert into mk_journey_runtime_generation(tenant_id,environment_name,cell_id,namespace_name,generation_no,release_key_id,manifest_json,artifact_id,artifact_payload,manifest_signature,warmed_at) values(?,?,?,?,?,?,?,?,?,?,?)",
                    scope.tenantId().value(), manifest.environment(), manifest.cell(), manifest.namespace(),
                    manifest.generation(), request.releaseKeyId(), json(manifest), artifact.reference().artifactId(),
                    payload, manifest.signature(), format(clock.instant()));
        } catch (DuplicateKeyException duplicate) {
            StoredGeneration existing = generation(scope.tenantId().value(), manifest, manifest.generation());
            if (!existing.manifest().signature().equals(manifest.signature())
                    || !Arrays.equals(existing.payload(), payload)) {
                throw new ConflictException("JOURNEY_GENERATION_CONFLICT",
                        "generation already contains another signed journey artifact");
            }
        }
        installDefinition(scope.tenantId().value(), plan, request.releaseKeyId());
        RuntimeAck unsigned = new RuntimeAck(manifest.manifestId(), manifest.generation(), runtimeId,
                manifest.cell(), RuntimeAck.Status.READY, buildDigest, Set.of(artifact.reference().abi()),
                Set.of(artifact.reference().artifactId()), capacity, clock.instant(), ackKeys.activeKeyId(), "");
        RuntimeAck ack = RuntimeAckSigner.sign(ackKeys.activePrivateKey(), scope.tenantId().value(), unsigned);
        return new WarmView(artifact.reference().artifactId(), plan.journeyId(), plan.version(), ack);
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
        jdbc.update("insert into mk_journey_runtime_activation(tenant_id,environment_name,cell_id,namespace_name,activation_sequence,generation_no,directive_signature,directive_json,activated_at) values(?,?,?,?,?,?,?,?,?)",
                tenantId, directive.environment(), directive.cell(), directive.namespace(),
                directive.activationSequence(), directive.generation(), directive.signature(), json(directive),
                format(clock.instant()));
        jdbc.update("update mk_journey_runtime_slot set desired_generation=?,activation_sequence=?,directive_signature=?,directive_json=?,updated_at=? where tenant_id=? and environment_name=? and cell_id=? and namespace_name=?",
                directive.generation(), directive.activationSequence(), directive.signature(), json(directive),
                format(clock.instant()), tenantId, directive.environment(), directive.cell(),
                directive.namespace());
        return view(installed, directive);
    }

    private void ensureSlot(ActivationDirective directive) {
        try {
            jdbc.update("insert into mk_journey_runtime_slot(tenant_id,environment_name,cell_id,namespace_name,desired_generation,activation_sequence,directive_signature,directive_json,updated_at) values(?,?,?,?,?,?,?,?,?)",
                    directive.tenantId().value(), directive.environment(), directive.cell(), directive.namespace(),
                    0, 0, "", "", format(clock.instant()));
        } catch (DuplicateKeyException exists) {
            // Locked reread below serializes activation writers.
        }
    }

    private Slot slot(ActivationDirective directive, boolean lock) {
        List<Slot> rows = jdbc.query("select desired_generation,activation_sequence,directive_signature from mk_journey_runtime_slot where tenant_id=? and environment_name=? and cell_id=? and namespace_name=?" + (lock ? " for update" : ""),
                (rs, rowNum) -> new Slot(rs.getLong(1), rs.getLong(2), rs.getString(3)),
                directive.tenantId().value(), directive.environment(), directive.cell(), directive.namespace());
        if (rows.isEmpty()) throw new IllegalStateException("journey runtime slot was not created");
        return rows.getFirst();
    }

    private StoredGeneration generation(String tenantId, ReleaseManifest slot, long generation) {
        return generation(tenantId, slot.environment(), slot.cell(), slot.namespace(), generation);
    }

    private StoredGeneration generation(String tenantId, ActivationDirective slot, long generation) {
        return generation(tenantId, slot.environment(), slot.cell(), slot.namespace(), generation);
    }

    private StoredGeneration generation(String tenantId, String environment, String cell, String namespace,
            long generation) {
        List<StoredGeneration> rows = jdbc.query("select release_key_id,manifest_json,artifact_id,artifact_payload from mk_journey_runtime_generation where tenant_id=? and environment_name=? and cell_id=? and namespace_name=? and generation_no=?",
                (rs, rowNum) -> new StoredGeneration(rs.getString(1), read(rs.getString(2), ReleaseManifest.class),
                        rs.getString(3), rs.getBytes(4)), tenantId, environment, cell, namespace, generation);
        if (rows.isEmpty()) throw new NotFoundException("JOURNEY_GENERATION_NOT_WARM",
                "journey generation is not installed");
        return rows.getFirst();
    }

    private static void assertPlan(JourneyPlan plan, ArtifactReference reference) {
        if (!plan.journeyId().equals(reference.definitionId()) || plan.version() != reference.definitionVersion()
                || plan.nodes().size() > 500 || plan.maxStepsPerSignal() > 1_000 || plan.maxIterations() > 100
                || plan.stateTtl().compareTo(java.time.Duration.ofDays(365)) > 0) {
            throw new ConflictException("JOURNEY_PLAN_INVALID", "journey plan identity or limits are invalid");
        }
    }

    private void installDefinition(String tenantId, JourneyPlan plan, String releaseKeyId) {
        List<String> installed = jdbc.query("select plan_json from mk_journey_definition where tenant_id=? and journey_id=? and version_no=?",
                (rs, rowNum) -> rs.getString(1), tenantId, plan.journeyId(), plan.version());
        String encoded = json(plan);
        if (!installed.isEmpty()) {
            JourneyPlan existing = read(installed.getFirst(), JourneyPlan.class);
            if (!existing.equals(plan)) {
                throw new ConflictException("JOURNEY_VERSION_IMMUTABLE",
                        "journey id and version already identify another plan");
            }
            return;
        }
        try {
            jdbc.update("insert into mk_journey_definition(tenant_id,journey_id,version_no,plan_json,state_name,created_by,created_at) values(?,?,?,?,?,?,?)",
                    tenantId, plan.journeyId(), plan.version(), encoded, "ACTIVE", "release:" + releaseKeyId,
                    format(clock.instant()));
        } catch (DuplicateKeyException race) {
            List<String> winner = jdbc.query("select plan_json from mk_journey_definition where tenant_id=? and journey_id=? and version_no=?",
                    (rs, rowNum) -> rs.getString(1), tenantId, plan.journeyId(), plan.version());
            if (winner.isEmpty() || !read(winner.getFirst(), JourneyPlan.class).equals(plan)) {
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
    private record Slot(long desiredGeneration, long activationSequence, String signature) { }
    private record StoredGeneration(String releaseKeyId, ReleaseManifest manifest, String artifactId,
            byte[] payload) {
        private StoredGeneration { payload = payload.clone(); }
        @Override public byte[] payload() { return payload.clone(); }
    }
}
