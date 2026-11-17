package com.acme.marketing.jobs.journey;

import com.acme.marketing.contracts.artifact.ArtifactAttestation;
import com.acme.marketing.contracts.release.*;
import com.acme.marketing.jobs.support.JsonCodec;
import com.acme.marketing.journey.JourneyNode;
import com.acme.marketing.journey.JourneyPlan;
import com.acme.marketing.platform.crypto.Digests;
import com.acme.marketing.platform.crypto.Ed25519;
import com.acme.marketing.platform.identity.TenantId;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/** 隔离测试使用真实签名/编译证明，制品写入专属MySQL，不代表生产上游或实际发布。 */
final class BundleJourneyFixture {
    static final KeyPair RELEASE = Ed25519.generateKeyPair();
    static final KeyPair COMPILER = Ed25519.generateKeyPair();
    static final KeyPair PARTICIPATION = Ed25519.generateKeyPair();
    final String tenant;
    final Instant now;
    final JourneyPlan plan;
    final byte[] payload;
    final ReleaseManifest member;
    final ReleaseBundleActivationDirective directive;
    final ReleaseBundleParticipation participation;
    final JourneyJobInput.PlanReference reference;
    final String enrollment;

    BundleJourneyFixture(String tenant, Instant now, String campaign, long generation) {
        this.tenant = tenant; this.now = now;
        plan = new JourneyPlan("journey", generation, "start", Map.of(
                "start", new JourneyNode("start", JourneyNode.Type.TRIGGER, Map.of(), Map.of("next", "wait")),
                "wait", new JourneyNode("wait", JourneyNode.Type.WAIT_TIMER, Map.of("delaySeconds", "5"), Map.of("elapsed", "end")),
                "end", new JourneyNode("end", JourneyNode.Type.END, Map.of(), Map.of())), 20, 3, Duration.ofDays(1));
        payload = JsonCodec.write(plan).getBytes(StandardCharsets.UTF_8);
        member = member("journey", "main", generation);
        var members = List.of(member, member("decision", "other", generation));
        var bundle = ReleaseBundleManifestSigner.sign(RELEASE.getPrivate(), new ReleaseBundleManifest("bundle-" + campaign,
                new TenantId(tenant), campaign, generation, "local", "cell", ReleaseBundleManifest.contentHash(tenant, campaign, members),
                members, now, now.plusSeconds(600), "release", ""));
        directive = ReleaseBundleActivationSigner.sign(RELEASE.getPrivate(), new ReleaseBundleActivationDirective("directive", bundle,
                1, "ACTIVATE", "org", "", now, "operator", "release", ""));
        participation = ReleaseBundleParticipationSigner.sign(PARTICIPATION.getPrivate(), new ReleaseBundleParticipation("binding-" + campaign,
                new TenantId(tenant), campaign, "local", "cell", "order/1", ReleaseBundleParticipation.subjectHash(new TenantId(tenant), "subject"),
                directive.directiveId(), 1, directive.signature(), bundle.bundleId(), generation, bundle.contentHash(), now, "participation", ""));
        reference = new JourneyJobInput.PlanReference(member.artifacts().getFirst().artifactId(), generation, 1, plan.journeyId(), plan.version());
        enrollment = participation.enrollmentId(member.manifestId());
    }

    JourneyJobInput start() {
        return new JourneyJobInput(tenant, enrollment, "subject", reference,
                new JourneyJobInput.SignalPayload(JourneyJobInput.SignalPayload.Type.START, "start", "", "", now.toEpochMilli(), Map.of()), participation);
    }

    void install(Connection connection) throws Exception {
        try (var statement = connection.prepareStatement("INSERT INTO mk_journey_runtime_generation (tenant_id,environment_name,cell_id,namespace_name,generation_no,release_key_id,manifest_json,artifact_id,artifact_payload,manifest_signature,warmed_at) VALUES (?,'local','cell','main',?,'release',?,?,?,?,?)")) {
            statement.setString(1, tenant); statement.setLong(2, member.generation()); statement.setString(3, JsonCodec.write(member));
            statement.setString(4, reference.artifactId()); statement.setBytes(5, payload); statement.setString(6, member.signature());
            statement.setString(7, now.toString()); statement.executeUpdate();
        }
        record(connection);
    }

    void record(Connection connection) throws Exception {
        try (var statement = connection.prepareStatement("INSERT INTO mk_runtime_bundle_directive (tenant_id,environment_name,cell_id,namespace_name,campaign_id,activation_sequence,directive_id,directive_json,recorded_by,recorded_at) VALUES (?,'local','cell','main',?,1,'directive',?,'test',?)")) {
            statement.setString(1, tenant); statement.setString(2, directive.bundle().campaignId());
            statement.setString(3, JsonCodec.write(directive)); statement.setString(4, now.toString()); statement.executeUpdate();
        }
    }

    JourneyBundleExecutionProbe probe() { return probe("1".repeat(32), 2, "participation"); }
    JourneyBundleExecutionProbe probe(String jobId, int parallelism, String participationKeyId) {
        return JourneyBundleExecutionProbeSigner.sign(RELEASE.getPrivate(), new JourneyBundleExecutionProbe("probe",
                new TenantId(tenant), directive.bundle().campaignId(), "local", "cell", "main", member.manifestId(),
                directive.activationSequence(), directive.signature(), jobId, parallelism, participationKeyId,
                now, now.plusSeconds(120), "release", ""));
    }

    static String encoded(KeyPair pair) { return Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()); }
    private ReleaseManifest member(String runtime, String namespace, long generation) {
        String id = runtime + "-" + generation, checksum = "sha256:" + Digests.sha256Hex(payload), source = "sha256:" + "b".repeat(64);
        String attestation = ArtifactAttestation.sign(COMPILER.getPrivate(), tenant, id, plan.journeyId(), generation,
                "JOURNEY_PLAN", "marketing-journey-plan/1", checksum, source);
        var artifact = new ArtifactReference(id, "JOURNEY_PLAN", "memory:test", checksum, source, "compiler", attestation,
                "marketing-journey-plan/1", plan.journeyId(), generation);
        return new ReleaseManifestSigner().sign(new ReleaseManifest(id, new TenantId(tenant), "local", "cell", runtime, namespace,
                generation, 0, List.of(), List.of(), List.of(artifact), Map.of(), 0, now, now.plusSeconds(600), "author", List.of("approval"), now, ""), RELEASE.getPrivate());
    }
}
