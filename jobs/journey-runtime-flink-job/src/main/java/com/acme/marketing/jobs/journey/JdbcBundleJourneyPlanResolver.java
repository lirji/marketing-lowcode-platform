package com.acme.marketing.jobs.journey;

import com.acme.marketing.contracts.release.PinnedJourneyBundleExecutionVerifier;
import com.acme.marketing.contracts.release.JourneyBundleExecutionProbe;
import com.acme.marketing.platform.crypto.Digests;

import com.acme.marketing.contracts.release.PinnedReleaseBundleActivationVerifier;
import com.acme.marketing.contracts.release.PinnedReleaseBundleParticipationVerifier;
import com.acme.marketing.contracts.release.ReleaseBundleActivationDirective;
import com.acme.marketing.contracts.release.ReleaseBundleParticipation;
import com.acme.marketing.contracts.release.ReleaseBundleParticipationSigner;
import com.acme.marketing.contracts.release.ReleaseBundleProtocolError;
import com.acme.marketing.contracts.release.ReleaseManifest;
import com.acme.marketing.jobs.journey.persistence.JourneyRuntimeReader;
import com.acme.marketing.jobs.support.JsonCodec;
import com.acme.marketing.journey.JourneyPlan;
import com.acme.marketing.journey.JourneyReleaseVerifier;
import com.acme.marketing.platform.identity.TenantId;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** 只读完整授权与安装制品；参与签名固定源版本，不能退回latest或旧单清单激活表。 */
final class JdbcBundleJourneyPlanResolver {
    private static final int MAX_CACHE_ENTRIES = 128;
    private static final int MAX_AUTHORIZATION_BYTES = 1024 * 1024;
    private static final Duration CACHE_TTL = Duration.ofMinutes(5);
    private static final String RUNTIME_NAME = "journey";
    private final JourneyRuntimeReader reader;
    private final Map<String, PublicKey> participationKeys;
    private final PinnedReleaseBundleParticipationVerifier participationVerifier;
    private final JourneyReleaseVerifier releaseVerifier;
    private final PinnedJourneyBundleExecutionVerifier executionVerifier;
    private final Map<String, Instant> probeCache = new LinkedHashMap<>(16, 0.75f, true) {
        private static final long serialVersionUID = 1L;
        @Override protected boolean removeEldestEntry(Map.Entry<String, Instant> eldest) { return size() > MAX_CACHE_ENTRIES; }
    };
    private final String environment;
    private final String cell;
    private final String namespace;
    private final Map<CacheKey, CachedPlan> cache = new LinkedHashMap<>(16, 0.75f, true) {
        private static final long serialVersionUID = 1L;
        @Override protected boolean removeEldestEntry(Map.Entry<CacheKey, CachedPlan> eldest) {
            return size() > MAX_CACHE_ENTRIES;
        }
    };

    /** 三类信任各自有明确角色，不从Control密钥推导参与签发权。 */
    JdbcBundleJourneyPlanResolver(JourneyRuntimeReader reader, Map<String, PublicKey> releaseKeys,
            Map<String, PublicKey> compilerKeys, Map<String, PublicKey> participationKeys,
            String environment, String cell, String namespace) {
        this.executionVerifier = new PinnedJourneyBundleExecutionVerifier(releaseKeys, Map.of());
        this.reader = reader; this.participationKeys = Map.copyOf(participationKeys);
        this.participationVerifier = new PinnedReleaseBundleParticipationVerifier(participationKeys,
                new PinnedReleaseBundleActivationVerifier(releaseKeys));
        this.releaseVerifier = new JourneyReleaseVerifier(releaseKeys, compilerKeys, environment, cell, namespace);
        this.environment = environment; this.cell = cell; this.namespace = namespace;
    }

    /** 先验证事件主体及派生执行键，再返回可信编译计划；调用者仍须先检查当前kill-switch。 */
    JourneyPlan resolve(String tenantId, String enrollmentId, String subjectToken, JourneyJobInput.PlanReference reference,
            ReleaseBundleParticipation participation, Instant now) {
        if (now == null || reference == null || participation == null
                || !participation.tenantId().value().equals(tenantId)
                || !participation.environment().equals(environment) || !participation.cell().equals(cell)
                || !ReleaseBundleParticipationSigner.verify(participationKeys.get(participation.signatureKeyId()), participation))
            throw ReleaseBundleProtocolError.PARTICIPATION_INVALID.conflict();
        var key = new CacheKey(tenantId, participation.campaignId(), participation.activationSequence(),
                participation.activationSignature(), reference);
        CachedPlan cached = cache.get(key);
        if (cached != null && !now.isBefore(cached.loadedAt()) && now.isBefore(cached.loadedAt().plus(CACHE_TTL))) {
            validateBinding(tenantId, enrollmentId, subjectToken, reference, participation, cached.directive(), now);
            return cached.plan();
        }
        String source = reader.bundleDirective(tenantId, environment, cell, namespace, participation.campaignId(), participation.activationSequence());
        // 可信参与可能先于本地持久投影到达；缺记录/制品必须等恢复，不能把合法事实丢进DLT并推进checkpoint。
        if (source == null) throw storedFailure();
        ReleaseBundleActivationDirective directive;
        try {
            if (source.getBytes(StandardCharsets.UTF_8).length > MAX_AUTHORIZATION_BYTES) throw storedFailure();
            directive = JsonCodec.read(source, ReleaseBundleActivationDirective.class);
            if (directive == null) throw storedFailure();
        } catch (IllegalArgumentException corrupt) { throw new JourneyInfrastructureException(corrupt); }
        ReleaseManifest member = validateBinding(tenantId, enrollmentId, subjectToken, reference, participation, directive, now);
        var row = reader.bundled(tenantId, environment, cell, namespace, participation.campaignId(), reference.generation(),
                participation.activationSequence(), reference.artifactId());
        if (row == null || !source.equals(row.directiveJson())) throw storedFailure();
        ReleaseManifest installed;
        try { installed = JsonCodec.read(row.manifestJson(), ReleaseManifest.class); }
        catch (IllegalArgumentException corrupt) { throw new JourneyInfrastructureException(corrupt); }
        if (installed == null) throw storedFailure();
        if (row.generation() != member.generation() || row.activationSequence() != directive.activationSequence()
                || !row.artifactId().equals(reference.artifactId()) || !installed.equals(member)) throw storedFailure();
        JourneyPlan plan;
        try {
            var artifact = releaseVerifier.verifyInstallation(tenantId, row.releaseKeyId(), installed, row.payload(), participation.admittedAt());
            plan = JdbcJourneyPlanResolver.verifiedPlan(reference, artifact);
        } catch (IllegalArgumentException corrupt) { throw new JourneyInfrastructureException(corrupt); }
        cache.put(key, new CachedPlan(directive, plan, now));
        return plan;
    }

    /** 同一完整挑战只缓存小摘要；重放仍签回执，缓存绝不替代当前kill检查。 */
    void verifyExecutionProbe(JourneyBundleExecutionProbe probe,
            String jobId, int parallelism, String buildDigest, Instant now) {
        if (!environment.equals(probe.environment()) || !cell.equals(probe.cell()) || !namespace.equals(probe.namespace())
                || !jobId.equals(probe.targetJobId()) || parallelism != probe.targetParallelism()
                || !participationKeys.containsKey(probe.participationKeyId()) || now.isBefore(probe.issuedAt()) || !now.isBefore(probe.expiresAt()))
            throw ReleaseBundleProtocolError.EXECUTION_PROBE_INVALID.conflict();
        String fingerprint = Digests.sha256Hex(JsonCodec.write(probe));
        Instant cachedUntil = probeCache.get(fingerprint);
        if (cachedUntil != null && now.isBefore(cachedUntil)) return;
        String source = reader.bundleDirective(probe.tenantId().value(), environment, cell, namespace, probe.campaignId(), probe.activationSequence());
        if (source == null || source.getBytes(StandardCharsets.UTF_8).length > MAX_AUTHORIZATION_BYTES) throw storedFailure();
        var directive = JsonCodec.read(source, ReleaseBundleActivationDirective.class);
        var expected = new PinnedJourneyBundleExecutionVerifier.ExpectedDeployment(
                probe.tenantId(), probe.campaignId(), environment, cell, namespace, jobId, parallelism, buildDigest, probe.participationKeyId());
        var member = executionVerifier.requireProbe(expected, probe, directive, now);
        if (member.artifacts().size() != 1) throw ReleaseBundleProtocolError.EXECUTION_PROBE_INVALID.conflict();
        var artifactReference = member.artifacts().getFirst();
        var row = reader.bundled(probe.tenantId().value(), environment, cell, namespace, probe.campaignId(), member.generation(),
                directive.activationSequence(), artifactReference.artifactId());
        if (row == null || !source.equals(row.directiveJson()) || row.generation() != member.generation()
                || row.activationSequence() != directive.activationSequence() || !artifactReference.artifactId().equals(row.artifactId())
                || !member.equals(JsonCodec.read(row.manifestJson(), ReleaseManifest.class))) throw storedFailure();
        var artifact = releaseVerifier.verifyInstallation(probe.tenantId().value(), row.releaseKeyId(), member, row.payload(), now);
        JdbcJourneyPlanResolver.verifiedPlan(new JourneyJobInput.PlanReference(artifactReference.artifactId(), member.generation(),
                directive.activationSequence(), artifactReference.definitionId(), artifactReference.definitionVersion()), artifact);
        probeCache.put(fingerprint, probe.expiresAt());
    }

    private ReleaseManifest validateBinding(String tenantId, String enrollmentId, String subjectToken,
            JourneyJobInput.PlanReference reference, ReleaseBundleParticipation participation,
            ReleaseBundleActivationDirective directive, Instant now) {
        var member = directive.bundle().members().stream().filter(value -> RUNTIME_NAME.equals(value.runtime())
                && namespace.equals(value.namespace())).findFirst().orElseThrow(ReleaseBundleProtocolError.PARTICIPATION_INVALID::conflict);
        // 参与编号已由Decision签名核验；独立事件身份必须同时匹配主体和不可更换的派生enrollmentId。
        if (!participation.enrollmentId(member.manifestId()).equals(enrollmentId)
                || reference.activationSequence() != directive.activationSequence() || reference.generation() != member.generation()
                || member.artifacts().stream().noneMatch(artifact -> reference.artifactId().equals(artifact.artifactId())
                        && reference.journeyId().equals(artifact.definitionId()) && reference.journeyVersion() == artifact.definitionVersion()))
            throw ReleaseBundleProtocolError.PARTICIPATION_INVALID.conflict();
        participationVerifier.requireValid(new PinnedReleaseBundleParticipationVerifier.ExpectedContext(new TenantId(tenantId),
                directive.bundle().campaignId(), environment, cell, participation.participationId(), subjectToken), participation, directive, now);
        return member;
    }

    private static JourneyInfrastructureException storedFailure() {
        return new JourneyInfrastructureException(new IllegalStateException(ReleaseBundleProtocolError.ACTIVATION_STORED_INVALID.message()));
    }
    private record CacheKey(String tenant, String campaign, long sequence, String signature, JourneyJobInput.PlanReference reference) { }
    private record CachedPlan(ReleaseBundleActivationDirective directive, JourneyPlan plan, Instant loadedAt) { }
}
