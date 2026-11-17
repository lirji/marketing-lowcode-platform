package com.acme.marketing.journeyservice;

import static org.junit.jupiter.api.Assertions.*;

import com.acme.marketing.contracts.artifact.ArtifactAttestation;
import com.acme.marketing.contracts.release.*;
import com.acme.marketing.platform.crypto.Digests;
import com.acme.marketing.platform.crypto.Ed25519;
import com.acme.marketing.platform.crypto.SigningKeyRing;
import com.acme.marketing.platform.identity.TenantId;
import com.acme.marketing.testsupport.MySqlIntegrationTest;
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
import java.util.UUID;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.ObjectMapper;

/** 真实HTTP和MySQL验证组合准备、应用上下文重启恢复及活动指针隔离。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class JourneyBundlePreparationIntegrationTest extends MySqlIntegrationTest {
    private static final KeyPair RELEASE = Ed25519.generateKeyPair();
    private static final KeyPair COMPILER = Ed25519.generateKeyPair();
    private static final KeyPair ACK = Ed25519.generateKeyPair();
    private static final String PREPARE = "/api/v1/journey-runtime/release-bundles:prepare";
    private static ReleaseBundlePrepareRequest restartRequest;
    private static String restartTenant;
    @DynamicPropertySource static void keys(DynamicPropertyRegistry registry) {
        registry.add("marketing.release.trusted-key-id", () -> "release-key");
        registry.add("marketing.release.public-key-base64", () -> encoded(RELEASE.getPublic().getEncoded()));
        registry.add("marketing.compiler.trusted-key-id", () -> "compiler-key");
        registry.add("marketing.compiler.public-key-base64", () -> encoded(COMPILER.getPublic().getEncoded()));
        registry.add("marketing.runtime-ack.signing-key-id", () -> "ack-key");
        registry.add("marketing.runtime-ack.private-key-base64", () -> encoded(ACK.getPrivate().getEncoded()));
        registry.add("marketing.runtime-ack.public-key-base64", () -> encoded(ACK.getPublic().getEncoded()));
    }
    @LocalServerPort int port;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired @Qualifier("journeyRuntimeAckSigningKeyRing") SigningKeyRing ackKeys;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final String tenant = "bundle-" + UUID.randomUUID();
    private final Instant now = Instant.now().minusSeconds(1);

    @Test @Order(1) @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void preparesNewMemberWhileOldActiveGenerationRemainsUnchanged() throws Exception {
        var old = installed(1); activate(old);
        var next = installed(2); var request = bundle(next);
        var result = send(post(PREPARE, request));
        assertAck(result, request, tenant);
        assertAck(send(post(PREPARE, request)), request, tenant);
        assertEquals(1L, active(tenant));
        assertEquals(2, jdbc.queryForObject("select count(*) from mk_journey_runtime_generation where tenant_id=?", Integer.class, tenant));
        restartRequest = request; restartTenant = tenant;
    }

    @Test @Order(2)
    void recreatedApplicationReadsPreparedGenerationFromDurableStore() throws Exception {
        assertNotNull(restartRequest);
        assertAck(send(post(PREPARE, restartRequest).setHeader("X-Dev-Tenant-Id", restartTenant)), restartRequest, restartTenant);
        assertEquals(1L, active(restartTenant));
        assertEquals(2, jdbc.queryForObject("select count(*) from mk_journey_runtime_generation where tenant_id=?", Integer.class, restartTenant));
    }

    @Test @Order(3)
    void refusesMissingGenerationAndCorruptedDurableBytesEvenAfterWarm() throws Exception {
        var missing = member(1, payload(1), "journey", "main");
        assertEquals(404, send(post(PREPARE, bundle(missing))).statusCode());
        var member = installed(1);
        jdbc.update("update mk_journey_runtime_generation set artifact_payload=? where tenant_id=? and generation_no=?", new byte[] {1, 2, 3}, tenant, 1);
        assertEquals(409, send(post(PREPARE, bundle(member))).statusCode());
        assertEquals(0, jdbc.queryForObject("select count(*) from mk_journey_runtime_slot where tenant_id=?", Integer.class, tenant));
    }

    @Test @Order(4)
    void rejectsWrongTenantUnknownMemberAndSignatureBeforePreparing() throws Exception {
        var member = installed(1); var request = bundle(member);
        assertCode(409, "RELEASE_BUNDLE_PROOF_INVALID", send(post(PREPARE, request).setHeader("X-Dev-Tenant-Id", "other")));
        assertCode(409, "RELEASE_BUNDLE_MEMBER_MISMATCH", send(post(PREPARE, new ReleaseBundlePrepareRequest(request.bundle(), "missing-member"))));
        var b = request.bundle();
        var forged = new ReleaseBundleManifest("altered-bundle", b.tenantId(), b.campaignId(), b.version(), b.environment(), b.cell(),
                b.contentHash(), b.members(), b.createdAt(), b.expiresAt(), b.signatureKeyId(), b.signature());
        assertCode(409, "RELEASE_BUNDLE_PROOF_INVALID", send(post(PREPARE, new ReleaseBundlePrepareRequest(forged, member.manifestId()))));
        assertEquals(403, send(post(PREPARE, request).setHeader("X-Dev-Permissions", "runtime:ack")).statusCode());
        assertEquals(0, jdbc.queryForObject("select count(*) from mk_journey_runtime_slot where tenant_id=?", Integer.class, tenant));
    }

    @Test @Order(5)
    void boundsRequestAndAuthorizesBeforeParsingIt() throws Exception {
        String oversized = " ".repeat(ReleaseBundlePrepareRequest.MAX_REQUEST_BYTES + 1);
        assertCode(400, "RELEASE_BUNDLE_PREPARE_INVALID", send(raw(PREPARE, oversized)));
        assertEquals(403, send(raw(PREPARE, oversized).setHeader("X-Dev-Permissions", "runtime:ack")).statusCode());
        assertCode(400, "RELEASE_BUNDLE_PREPARE_INVALID", send(raw(PREPARE, "null")));
        assertCode(400, "RELEASE_BUNDLE_PREPARE_INVALID", send(raw(PREPARE, "{} {}")));
    }

    @Test @Order(6)
    void refusesCorruptedInstalledJourneyDefinition() throws Exception {
        var member = installed(1);
        var changed = new com.acme.marketing.journey.JourneyPlan("definition", 1, "changed",
                Map.of("changed", new com.acme.marketing.journey.JourneyNode("changed", com.acme.marketing.journey.JourneyNode.Type.END, Map.of(), Map.of())), 5, 1, Duration.ofDays(1));
        jdbc.update("update mk_journey_definition set plan_json=? where tenant_id=? and journey_id=? and version_no=?", mapper.writeValueAsString(changed), tenant, "definition", 1);
        assertCode(409, "RELEASE_BUNDLE_MEMBER_MISMATCH", send(post(PREPARE, bundle(member))));
    }

    private ReleaseManifest installed(long generation) throws Exception {
        byte[] payload = payload(generation); var member = member(generation, payload, "journey", "main");
        var response = send(request("/api/v1/journey-runtime/manifest").PUT(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(
                Map.of("releaseKeyId", "release-key", "manifest", member, "artifactBase64", encoded(payload))))));
        assertEquals(200, response.statusCode(), response.body()); return member;
    }
    private void activate(ReleaseManifest member) throws Exception {
        var unsigned = new ActivationDirective("activation-1", member.tenantId(), member.manifestId(), "local", "cell-a", "journey",
                "main", 1, member.generation(), member.generation(), 0, member.signature(), now, member.expiresAt(), "operator", "release-key", "");
        var signed = ActivationDirectiveSigner.sign(RELEASE.getPrivate(), unsigned);
        var response = send(request("/api/v1/journey-runtime/activation").PUT(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(signed))));
        assertEquals(200, response.statusCode(), response.body());
    }
    private long active(String tenantId) {
        return jdbc.queryForObject("select desired_generation from mk_journey_runtime_slot where tenant_id=?", Long.class, tenantId);
    }
    private ReleaseBundlePrepareRequest bundle(ReleaseManifest member) {
        var other = member(member.generation(), new byte[] {4}, "decision", "other");
        var members = List.of(member, other);
        var unsigned = new ReleaseBundleManifest("bundle-" + member.generation(), new TenantId(tenant), "campaign", member.generation(),
                "local", "cell-a", ReleaseBundleManifest.contentHash(tenant, "campaign", members), members, now,
                now.plusSeconds(600), "release-key", "");
        return new ReleaseBundlePrepareRequest(ReleaseBundleManifestSigner.sign(RELEASE.getPrivate(), unsigned), member.manifestId());
    }
    private ReleaseManifest member(long generation, byte[] payload, String runtime, String namespace) {
        String id = runtime + "-" + generation;
        String checksum = "sha256:" + Digests.sha256Hex(payload), source = "sha256:" + "b".repeat(64);
        String signature = ArtifactAttestation.sign(COMPILER.getPrivate(), tenant, id, "definition", generation,
                "JOURNEY_PLAN", "marketing-journey-plan/1", checksum, source);
        var reference = new ArtifactReference(id, "JOURNEY_PLAN", "s3://artifacts/" + id, checksum, source, "compiler-key",
                signature, "marketing-journey-plan/1", "definition", generation);
        return new ReleaseManifestSigner().sign(new ReleaseManifest(id, new TenantId(tenant), "local", "cell-a", runtime, namespace,
                generation, 0, List.of(), List.of(), List.of(reference), Map.of(), 0, now, now.plusSeconds(600), "author", List.of("case"), now, ""), RELEASE.getPrivate());
    }
    private void assertAck(HttpResponse<String> response, ReleaseBundlePrepareRequest request, String expectedTenant) {
        assertEquals(200, response.statusCode(), response.body());
        var ack = mapper.readValue(response.body(), ReleaseBundleAck.class);
        assertTrue(ReleaseBundleAckSigner.verify(ackKeys.publicKeys().get(ack.runtimeAck().signatureKeyId()), expectedTenant, ack));
        assertEquals(request.bundle().bundleId(), ack.bundleId()); assertEquals(request.bundle().contentHash(), ack.contentHash());
        assertEquals(request.member().signature(), ack.memberSignature()); assertEquals(request.member().generation(), ack.runtimeAck().generation());
    }
    private HttpRequest.Builder post(String path, Object body) { return raw(path, mapper.writeValueAsString(body)); }
    private HttpRequest.Builder raw(String path, String body) { return request(path).POST(HttpRequest.BodyPublishers.ofString(body)); }
    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json").header("X-Dev-Tenant-Id", tenant).header("X-Dev-Actor-Id", "runtime-test")
                .header("X-Dev-Organization-Ids", "*").header("X-Dev-Shop-Ids", "*").header("X-Dev-Permissions", "*");
    }
    private HttpResponse<String> send(HttpRequest.Builder request) throws Exception { return http.send(request.build(), HttpResponse.BodyHandlers.ofString()); }
    private void assertCode(int status, String code, HttpResponse<String> response) {
        assertEquals(status, response.statusCode(), response.body()); assertEquals(code, mapper.readTree(response.body()).path("code").asString());
    }
    private static String encoded(byte[] value) { return Base64.getEncoder().encodeToString(value); }
    private byte[] payload(long generation) {
        return mapper.writeValueAsBytes(new com.acme.marketing.journey.JourneyPlan("definition", generation, "end",
                Map.of("end", new com.acme.marketing.journey.JourneyNode("end", com.acme.marketing.journey.JourneyNode.Type.END, Map.of(), Map.of())),
                5, 1, Duration.ofDays(1)));
    }
}
