package com.acme.marketing.journeyservice;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.acme.marketing.contracts.artifact.ArtifactAttestation;
import com.acme.marketing.contracts.release.*;
import com.acme.marketing.platform.crypto.Digests;
import com.acme.marketing.platform.identity.TenantId;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyPair;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.ObjectMapper;

/** 两层验收共用正式安装和指令接口，挑战绑定本次部署，但夹具不签发执行回执。 */
record JourneyProbeHttpFixture(int port, String tenant, ObjectMapper mapper, KeyPair release, KeyPair compiler, HttpClient http) {
    JourneyBundleExecutionProbe install(String id, String jobId, Instant now, Instant issuedAt) throws Exception {
        byte[] payload = mapper.writeValueAsBytes(new com.acme.marketing.journey.JourneyPlan("definition", 1, "end",
                Map.of("end", new com.acme.marketing.journey.JourneyNode("end", com.acme.marketing.journey.JourneyNode.Type.END, Map.of(), Map.of())), 5, 1, Duration.ofDays(1)));
        var member = member(now, payload, "journey", "main");
        var installed = send(request("/api/v1/journey-runtime/manifest").PUT(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(
                Map.of("releaseKeyId", "release-key", "manifest", member, "artifactBase64", Base64.getEncoder().encodeToString(payload))))));
        assertEquals(200, installed.statusCode(), installed.body());
        var members = List.of(member, member(now, new byte[]{4}, "decision", "other"));
        var bundle = ReleaseBundleManifestSigner.sign(release.getPrivate(), new ReleaseBundleManifest("bundle", new TenantId(tenant), "campaign", 1,
                "local", "cell-a", ReleaseBundleManifest.contentHash(tenant, "campaign", members), members, now, now.plusSeconds(600), "release-key", ""));
        var directive = ReleaseBundleActivationSigner.sign(release.getPrivate(), new ReleaseBundleActivationDirective("directive", bundle, 1, "ACTIVATE", "org", "shop", now, "operator", "release-key", ""));
        var received = send(request("/api/v1/journey-runtime/release-bundle-directives").POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(directive))));
        assertEquals(200, received.statusCode(), received.body());
        return JourneyBundleExecutionProbeSigner.sign(release.getPrivate(), new JourneyBundleExecutionProbe(id, new TenantId(tenant), "campaign", "local", "cell-a", "main",
                member.manifestId(), 1, directive.signature(), jobId, 2, "participation", issuedAt, issuedAt.plusSeconds(120), "release-key", ""));
    }
    private ReleaseManifest member(Instant now, byte[] payload, String runtime, String namespace) {
        String id = runtime + "-1", checksum = "sha256:" + Digests.sha256Hex(payload), source = "sha256:" + "b".repeat(64);
        String signature = ArtifactAttestation.sign(compiler.getPrivate(), tenant, id, "definition", 1, "JOURNEY_PLAN", "marketing-journey-plan/1", checksum, source);
        var artifact = new ArtifactReference(id, "JOURNEY_PLAN", "s3://artifacts/" + id, checksum, source, "compiler-key", signature, "marketing-journey-plan/1", "definition", 1);
        return new ReleaseManifestSigner().sign(new ReleaseManifest(id, new TenantId(tenant), "local", "cell-a", runtime, namespace, 1, 0, List.of(), List.of(), List.of(artifact),
                Map.of(), 0, now, now.plusSeconds(600), "author", List.of("case"), now, ""), release.getPrivate());
    }
    HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json").header("X-Dev-Tenant-Id", tenant).header("X-Dev-Actor-Id", "probe-test")
                .header("X-Dev-Organization-Ids", "*").header("X-Dev-Shop-Ids", "*").header("X-Dev-Permissions", "*");
    }
    HttpResponse<String> send(HttpRequest.Builder request) throws Exception { return http.send(request.build(), HttpResponse.BodyHandlers.ofString()); }
}
