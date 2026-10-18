package com.acme.marketing.jobs.journey;

import com.acme.marketing.contracts.release.ActivationDirective;
import com.acme.marketing.contracts.release.ReleaseManifest;
import com.acme.marketing.jobs.support.JsonCodec;
import com.acme.marketing.journey.JourneyPlan;
import com.acme.marketing.journey.JourneyReleaseVerifier;
import com.acme.marketing.platform.crypto.TrustedPublicKeys;
import java.nio.charset.StandardCharsets;
import com.acme.marketing.jobs.journey.persistence.JourneyRuntimeReader;
import com.acme.marketing.jobs.journey.persistence.JourneyRuntimeMapper.RuntimeRow;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** 核验当前激活指针，同时允许历史已激活计划继续驱动已绑定版本的旅程。 */
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
    private transient JourneyRuntimeReader reader;
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
        var row = reader().killSwitch(tenantId, namespace);
        KillStatus status = row == null ? new KillStatus(false, "", now)
                : new KillStatus(row.enabled(), row.reason(), now);
        killSwitchCache().put(tenantId, status);
        status.assertEnabledIsFalse();
    }

    private void assertCurrent(String tenantId, JourneyJobInput.PlanReference reference) {
        var row = reader().current(tenantId, environment, cell, namespace);
        if (row == null) throw new IllegalArgumentException("active journey runtime pointer was not found");
        if (row.generation() != reference.generation() || row.activationSequence() != reference.activationSequence()
                || !row.artifactId().equals(reference.artifactId())) {
            throw new IllegalArgumentException("journey plan reference is not the current desired generation");
        }
    }

    private RuntimeRow activated(String tenantId, JourneyJobInput.PlanReference reference) {
        RuntimeRow row = reader().activated(tenantId, environment, cell, namespace, reference.generation(),
                reference.activationSequence(), reference.artifactId());
        if (row == null) throw new IllegalArgumentException("activated journey generation was not found");
        return row;
    }

    private JourneyRuntimeReader reader() {
        if (reader == null) {
            try {
                reader = new JourneyRuntimeReader(databaseUrl, databaseUser, databasePassword);
            } catch (JourneyInfrastructureException unavailable) {
                throw unavailable;
            } catch (RuntimeException invalidConfiguration) {
                // 配置/XML 错误属于整个作业的故障，不能逐条转成坏消息并推进消费位点。
                throw new JourneyInfrastructureException(invalidConfiguration);
            }
        }
        return reader;
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
