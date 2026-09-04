package com.acme.marketing.jobs.journey;

import com.acme.marketing.contracts.release.ActivationDirective;
import com.acme.marketing.contracts.release.ReleaseManifest;
import com.acme.marketing.jobs.support.JsonCodec;
import com.acme.marketing.journey.JourneyPlan;
import com.acme.marketing.journey.JourneyReleaseVerifier;
import com.acme.marketing.platform.crypto.TrustedPublicKeys;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Resolves current starts and immutable, historically activated plans for pinned enrollments. */
final class JdbcJourneyPlanResolver implements JourneyPlanResolver {
    private static final long serialVersionUID = 1L;
    private static final int MAX_CACHE_ENTRIES = 128;
    private static final int MAX_KILL_CACHE_ENTRIES = 1_024;
    private static final Duration KILL_SWITCH_REFRESH_INTERVAL = Duration.ofSeconds(1);
    private final String databaseUrl;
    private final String databaseUser;
    private final String databasePassword;
    private final String releaseKeyId;
    private final String releasePublicKeyBase64;
    private final String additionalReleaseKeys;
    private final String compilerKeyId;
    private final String compilerPublicKeyBase64;
    private final String additionalCompilerKeys;
    private final String environment;
    private final String cell;
    private final String namespace;
    private transient Map<String, CachedPlan> cache;
    private transient Map<String, KillStatus> killSwitchCache;

    JdbcJourneyPlanResolver(String databaseUrl, String databaseUser, String databasePassword,
            String releaseKeyId, String releasePublicKeyBase64, String additionalReleaseKeys,
            String compilerKeyId, String compilerPublicKeyBase64, String additionalCompilerKeys,
            String environment, String cell, String namespace) {
        this.databaseUrl = required(databaseUrl, "journey runtime database URL");
        this.databaseUser = required(databaseUser, "journey runtime database user");
        this.databasePassword = databasePassword == null ? "" : databasePassword;
        this.releaseKeyId = normalized(releaseKeyId);
        this.releasePublicKeyBase64 = normalized(releasePublicKeyBase64);
        this.additionalReleaseKeys = normalized(additionalReleaseKeys);
        this.compilerKeyId = normalized(compilerKeyId);
        this.compilerPublicKeyBase64 = normalized(compilerPublicKeyBase64);
        this.additionalCompilerKeys = normalized(additionalCompilerKeys);
        if (TrustedPublicKeys.parse(this.releaseKeyId, this.releasePublicKeyBase64,
                this.additionalReleaseKeys).isEmpty()
                || TrustedPublicKeys.parse(this.compilerKeyId, this.compilerPublicKeyBase64,
                        this.additionalCompilerKeys).isEmpty()) {
            throw new IllegalArgumentException("journey release and compiler trust keys are required");
        }
        this.environment = required(environment, "environment");
        this.cell = required(cell, "cell");
        this.namespace = required(namespace, "namespace");
    }

    @Override
    public JourneyPlan resolve(String tenantId, JourneyJobInput.PlanReference reference, Instant now,
            boolean requireCurrentActivation) {
        if (now == null) throw new IllegalArgumentException("verification time is required");
        assertNotKilled(tenantId, now);
        if (requireCurrentActivation) {
            assertCurrent(tenantId, reference);
        }
        String cacheKey = tenantId + ':' + reference.generation() + ':' + reference.activationSequence();
        CachedPlan cached = cache().get(cacheKey);
        if (cached != null && cached.reference().equals(reference)
                && (!requireCurrentActivation || now.isBefore(cached.expiresAt()))) {
            return cached.plan();
        }
        RuntimeRow row = activated(tenantId, reference);
        if (row.generation() != reference.generation() || row.activationSequence() != reference.activationSequence()
                || !row.artifactId().equals(reference.artifactId())) {
            throw new IllegalArgumentException("journey plan reference does not bind an activated generation");
        }
        ReleaseManifest manifest = JsonCodec.read(row.manifestJson(), ReleaseManifest.class);
        ActivationDirective activation = JsonCodec.read(row.directiveJson(), ActivationDirective.class);
        JourneyReleaseVerifier verifier = new JourneyReleaseVerifier(
                TrustedPublicKeys.parse(releaseKeyId, releasePublicKeyBase64, additionalReleaseKeys),
                TrustedPublicKeys.parse(compilerKeyId, compilerPublicKeyBase64, additionalCompilerKeys),
                environment, cell, namespace);
        Instant verificationTime = requireCurrentActivation ? now : activation.activatedAt();
        JourneyReleaseVerifier.VerifiedArtifact artifact = verifier.verifyInstallation(tenantId, row.releaseKeyId(),
                manifest, row.payload(), verificationTime);
        verifier.verifyActivation(tenantId, activation, manifest, verificationTime);
        JourneyPlan plan = JsonCodec.read(new String(artifact.payload(), StandardCharsets.UTF_8), JourneyPlan.class);
        if (!reference.journeyId().equals(plan.journeyId()) || reference.journeyVersion() != plan.version()
                || !plan.journeyId().equals(artifact.reference().definitionId())
                || plan.version() != artifact.reference().definitionVersion()
                || plan.nodes().size() > 500 || plan.maxStepsPerSignal() > 1_000 || plan.maxIterations() > 100
                || plan.stateTtl().compareTo(Duration.ofDays(365)) > 0) {
            throw new IllegalArgumentException("journey plan reference, identity or limits are invalid");
        }
        Instant expiresAt = manifest.expiresAt().isBefore(activation.expiresAt())
                ? manifest.expiresAt() : activation.expiresAt();
        cache().put(cacheKey, new CachedPlan(reference, plan, expiresAt));
        return plan;
    }

    private void assertNotKilled(String tenantId, Instant now) {
        KillStatus cached = killSwitchCache().get(tenantId);
        if (cached != null && !now.isBefore(cached.checkedAt())
                && now.isBefore(cached.checkedAt().plus(KILL_SWITCH_REFRESH_INTERVAL))) {
            cached.assertEnabledIsFalse();
            return;
        }
        String sql = "select enabled_value,reason_text from mk_journey_runtime_kill_switch where tenant_id=? and namespace_name=?";
        try (var connection = DriverManager.getConnection(databaseUrl, databaseUser, databasePassword);
                var statement = connection.prepareStatement(sql)) {
            statement.setString(1, tenantId);
            statement.setString(2, namespace);
            try (var result = statement.executeQuery()) {
                KillStatus status = result.next()
                        ? new KillStatus(result.getBoolean(1), result.getString(2), now)
                        : new KillStatus(false, "", now);
                killSwitchCache().put(tenantId, status);
                status.assertEnabledIsFalse();
            }
        } catch (SQLException failure) {
            // Once the short freshness window closes, a control-plane lookup failure pauses execution.
            // This keeps the hot path bounded without extending an unknown kill-switch state.
            throw new IllegalStateException("journey kill switch lookup failed", failure);
        }
    }

    private void assertCurrent(String tenantId, JourneyJobInput.PlanReference reference) {
        String sql = "select slot.desired_generation,slot.activation_sequence,generation.artifact_id from mk_journey_runtime_slot slot join mk_journey_runtime_generation generation on generation.tenant_id=slot.tenant_id and generation.environment_name=slot.environment_name and generation.cell_id=slot.cell_id and generation.namespace_name=slot.namespace_name and generation.generation_no=slot.desired_generation where slot.tenant_id=? and slot.environment_name=? and slot.cell_id=? and slot.namespace_name=? and slot.desired_generation>0";
        try (var connection = DriverManager.getConnection(databaseUrl, databaseUser, databasePassword);
                var statement = connection.prepareStatement(sql)) {
            statement.setString(1, tenantId);
            statement.setString(2, environment);
            statement.setString(3, cell);
            statement.setString(4, namespace);
            try (var result = statement.executeQuery()) {
                if (!result.next()) throw new IllegalArgumentException("active journey runtime pointer was not found");
                if (result.getLong(1) != reference.generation()
                        || result.getLong(2) != reference.activationSequence()
                        || !result.getString(3).equals(reference.artifactId())) {
                    throw new IllegalArgumentException("journey plan reference is not the current desired generation");
                }
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("journey runtime pointer lookup failed", failure);
        }
    }

    private RuntimeRow activated(String tenantId, JourneyJobInput.PlanReference reference) {
        String sql = "select generation.generation_no,activation.activation_sequence,activation.directive_json,generation.release_key_id,generation.manifest_json,generation.artifact_payload,generation.artifact_id from mk_journey_runtime_generation generation join mk_journey_runtime_activation activation on activation.tenant_id=generation.tenant_id and activation.environment_name=generation.environment_name and activation.cell_id=generation.cell_id and activation.namespace_name=generation.namespace_name and activation.generation_no=generation.generation_no where generation.tenant_id=? and generation.environment_name=? and generation.cell_id=? and generation.namespace_name=? and generation.generation_no=? and activation.activation_sequence=? and generation.artifact_id=?";
        try (var connection = DriverManager.getConnection(databaseUrl, databaseUser, databasePassword);
                var statement = connection.prepareStatement(sql)) {
            statement.setString(1, tenantId);
            statement.setString(2, environment);
            statement.setString(3, cell);
            statement.setString(4, namespace);
            statement.setLong(5, reference.generation());
            statement.setLong(6, reference.activationSequence());
            statement.setString(7, reference.artifactId());
            try (var result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new IllegalArgumentException("activated journey generation was not found");
                }
                return new RuntimeRow(result.getLong(1), result.getLong(2), result.getString(3),
                        result.getString(4), result.getString(5), result.getBytes(6), result.getString(7));
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("activated journey generation lookup failed", failure);
        }
    }

    private Map<String, CachedPlan> cache() {
        if (cache == null) {
            cache = new LinkedHashMap<>(16, 0.75f, true) {
                private static final long serialVersionUID = 1L;
                @Override protected boolean removeEldestEntry(Map.Entry<String, CachedPlan> eldest) {
                    return size() > MAX_CACHE_ENTRIES;
                }
            };
        }
        return cache;
    }

    private Map<String, KillStatus> killSwitchCache() {
        if (killSwitchCache == null) {
            killSwitchCache = new LinkedHashMap<>(16, 0.75f, true) {
                private static final long serialVersionUID = 1L;
                @Override protected boolean removeEldestEntry(Map.Entry<String, KillStatus> eldest) {
                    return size() > MAX_KILL_CACHE_ENTRIES;
                }
            };
        }
        return killSwitchCache;
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value;
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }

    private record RuntimeRow(long generation, long activationSequence, String directiveJson,
            String releaseKeyId, String manifestJson, byte[] payload, String artifactId) {
        private RuntimeRow { payload = payload.clone(); }
        @Override public byte[] payload() { return payload.clone(); }
    }
    private record CachedPlan(JourneyJobInput.PlanReference reference, JourneyPlan plan, Instant expiresAt) { }
    private record KillStatus(boolean enabled, String reason, Instant checkedAt) {
        private KillStatus {
            reason = reason == null ? "" : reason;
        }

        void assertEnabledIsFalse() {
            if (enabled) {
                throw new JourneyExecutionPausedException("marketing kill switch is enabled: " + reason);
            }
        }
    }
}
