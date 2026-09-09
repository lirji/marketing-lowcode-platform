package com.acme.marketing.referral;

import static org.junit.jupiter.api.Assertions.*;
import com.acme.marketing.contracts.artifact.ArtifactAttestation;
import com.acme.marketing.contracts.release.ArtifactReference;
import com.acme.marketing.contracts.release.ReleaseManifest;
import com.acme.marketing.contracts.release.ReleaseManifestSigner;
import com.acme.marketing.platform.crypto.Digests;
import com.acme.marketing.platform.crypto.Ed25519;
import com.acme.marketing.platform.identity.TenantId;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** 使用真实 Ed25519 签名验证不可信发布输入；不以 mock 验签替代信任边界。 */
class ReferralReleaseVerifierTest {
    static final Instant NOW = Instant.parse("2026-09-09T00:00:00Z");
    static final KeyPair RELEASE = Ed25519.generateKeyPair();
    static final KeyPair COMPILER = Ed25519.generateKeyPair();
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final String BODY = """
        {"definitionId":"def-1","scope":{"organizationId":"org","shopId":"shop"},
         "binding":{"attribution":"FIRST_VALID_BIND","maxBindAgeSeconds":3600,"inviteeScope":"NEW_CUSTOMER"},
         "policy":{"startsAt":"2026-09-09T00:00:00Z","endsAt":"2026-09-10T00:00:00Z",
          "settlementEndsAt":"2026-09-11T00:00:00Z","qualificationWindowSeconds":3600,
          "observationSeconds":0,"lateArrivalGraceSeconds":0,"goalType":"FIRST_ORDER_SETTLED",
          "minNetAmountMinor":100,"currency":"CNY","rewards":[{"ruleId":"rule-1","role":"INVITER",
          "mode":"PER_RELATION","threshold":1,"benefitDefinitionVersion":"benefit-v1","skuVersion":"sku-v1",
          "quantity":1,"perSubjectLimit":1,"campaignLimit":100}]}}
        """;

    ReferralReleaseVerifier verifier() {
        return new ReferralReleaseVerifier(Map.of("release", RELEASE.getPublic()),
                Map.of("compiler", COMPILER.getPublic()), "test", "cell", "default");
    }
    byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    ArtifactReference artifact(byte[] payload) {
        String checksum = "sha256:" + Digests.sha256Hex(payload);
        String source = "sha256:" + "b".repeat(64);
        String signature = ArtifactAttestation.sign(COMPILER.getPrivate(), "tenant-a", "artifact-1", "def-1", 7,
                ReferralReleaseVerifier.TYPE, ReferralReleaseVerifier.ABI, checksum, source);
        return new ArtifactReference("artifact-1", ReferralReleaseVerifier.TYPE, "https://invalid.example/not-fetched",
                checksum, source, "compiler", signature, ReferralReleaseVerifier.ABI, "def-1", 7);
    }
    ReleaseManifest manifest(ArtifactReference artifact) {
        return sign(new ReleaseManifest("manifest-1", new TenantId("tenant-a"), "test", "cell", "referral",
                "default", 2, 1, List.of(), List.of(1L), List.of(artifact), Map.of(), 0,
                NOW, NOW.plusSeconds(600), "admin", List.of("approval-1"), NOW.minusSeconds(1), ""));
    }
    ReleaseManifest sign(ReleaseManifest value) { return new ReleaseManifestSigner().sign(value, RELEASE.getPrivate()); }
    ReleaseManifest change(ReleaseManifest value, Consumer<ObjectNode> edit) {
        ObjectNode tree = JSON.valueToTree(value); edit.accept(tree);
        return JSON.treeToValue(tree, ReleaseManifest.class);
    }
    void invalidBody(String body) {
        byte[] payload = bytes(body);
        assertThrows(IllegalArgumentException.class, () -> verifier().verifyInstallation("tenant-a", "release",
                manifest(artifact(payload)), payload, NOW));
    }

    @Test void validPlanBindsDefinitionVersionAndUsesDefensiveBytes() {
        byte[] payload = bytes(BODY);
        var verified = verifier().verifyInstallation("tenant-a", "release", manifest(artifact(payload)), payload, NOW);
        assertEquals(7, verified.reference().definitionVersion());
        assertEquals("def-1", verified.plan().definitionId());
        assertEquals("sku-v1", verified.plan().policy().rewards().getFirst().skuVersion());
        payload[0] = 0;
        byte[] result = verified.payload(); result[0] = 0;
        assertEquals('{', verified.payload()[0]);
        assertThrows(UnsupportedOperationException.class, () -> verified.plan().policy().rewards().clear());
    }

    @TestFactory Stream<DynamicTest> signedButInvalidManifestRejected() {
        Map<String, Consumer<ObjectNode>> edits = Map.ofEntries(
                Map.entry("wrong tenant", n -> n.putObject("tenantId").put("value", "tenant-b")),
                Map.entry("wrong environment", n -> n.put("environment", "prod")),
                Map.entry("wrong cell", n -> n.put("cell", "other")),
                Map.entry("wrong namespace", n -> n.put("namespace", "other")),
                Map.entry("wrong runtime", n -> n.put("runtime", "journey")),
                Map.entry("expired boundary", n -> n.put("expiresAt", NOW.toString())),
                Map.entry("future issued", n -> { n.put("createdAt", NOW.plusSeconds(1).toString()); n.put("activationAt", NOW.plusSeconds(1).toString()); }),
                Map.entry("activation before creation", n -> n.put("activationAt", NOW.minusSeconds(2).toString())),
                Map.entry("activation at expiry", n -> n.put("activationAt", NOW.plusSeconds(600).toString())),
                Map.entry("canary traffic", n -> n.put("canaryBasisPoints", 10)),
                Map.entry("canary generations", n -> n.putArray("canaryGenerations").add(2)),
                Map.entry("missing approvals", n -> n.putArray("approvalCaseIds")),
                Map.entry("blank approval", n -> n.putArray("approvalCaseIds").add(" ")),
                Map.entry("extra artifact", n -> { var a = n.withArray("artifacts"); a.add(a.get(0).deepCopy()); }));
        return edits.entrySet().stream().map(entry -> DynamicTest.dynamicTest(entry.getKey(), () -> {
            byte[] payload = bytes(BODY);
            var value = sign(change(manifest(artifact(payload)), entry.getValue()));
            assertThrows(IllegalArgumentException.class, () -> verifier().verifyInstallation("tenant-a", "release", value, payload, NOW));
        }));
    }

    @TestFactory Stream<DynamicTest> artifactAttestationAndAbiBindEveryReference() {
        return Map.of("definitionId", "other", "definitionVersion", "8", "sourceDigest", "sha256:" + "c".repeat(64),
                "signature", "broken", "signatureKeyId", "unknown", "type", "JOURNEY_PLAN", "abi", "marketing-referral-plan/2")
                .entrySet().stream().map(entry -> DynamicTest.dynamicTest(entry.getKey(), () -> {
                    byte[] payload = bytes(BODY);
                    ObjectNode tree = JSON.valueToTree(artifact(payload));
                    if (entry.getKey().equals("definitionVersion")) tree.put(entry.getKey(), 8);
                    else tree.put(entry.getKey(), entry.getValue());
                    var value = manifest(JSON.treeToValue(tree, ArtifactReference.class));
                    assertThrows(IllegalArgumentException.class, () -> verifier().verifyInstallation("tenant-a", "release", value, payload, NOW));
                }));
    }

    @TestFactory Stream<DynamicTest> signedMalformedPlansNeverCoerceOrDefault() {
        return Stream.of(
                BODY.replace("\"definitionId\":\"def-1\"", "\"definitionId\":\"different\""),
                BODY.replace("\"scope\":", "\"extra\":true,\"scope\":"),
                BODY.replace("\"organizationId\":\"org\"", "\"organizationId\":\" \""),
                BODY.replace("\"scope\":{\"organizationId\":\"org\",\"shopId\":\"shop\"}", "\"scope\":null"),
                BODY.replace("\"observationSeconds\":0,", ""),
                BODY.replace("\"observationSeconds\":0", "\"observationSeconds\":null"),
                BODY.replace("\"observationSeconds\":0", "\"observationSeconds\":\"0\""),
                BODY.replace("\"observationSeconds\":0", "\"observationSeconds\":0.2"),
                BODY.replace("\"observationSeconds\":0", "\"observationSeconds\":0,\"observationSeconds\":1"),
                BODY.replace("\"maxBindAgeSeconds\":3600", "\"maxBindAgeSeconds\":0"),
                BODY.replace("\"maxBindAgeSeconds\":3600", "\"maxBindAgeSeconds\":9223372036854775807"),
                BODY.replace("FIRST_VALID_BIND", "LAST_CLICK"),
                BODY.replace("\"quantity\":1", "\"quantity\":2"),
                BODY.replace("\"skuVersion\":\"sku-v1\"", "\"skuVersion\":\"\""),
                BODY.replace("2026-09-10T00:00:00Z", "2026-09-08T00:00:00Z"),
                BODY + " {}", "null", "{}")
                .map(body -> DynamicTest.dynamicTest("invalid plan " + Digests.sha256Hex(bytes(body)).substring(0, 8), () -> invalidBody(body)));
    }

    @Test void unknownOrWrongTrustKeyAndUnsignedMutationReject() {
        byte[] payload = bytes(BODY); var value = manifest(artifact(payload));
        assertThrows(IllegalArgumentException.class, () -> verifier().verifyInstallation("tenant-a", "unknown", value, payload, NOW));
        var wrong = new ReferralReleaseVerifier(Map.of("release", COMPILER.getPublic()), Map.of("compiler", COMPILER.getPublic()), "test", "cell", "default");
        assertThrows(IllegalArgumentException.class, () -> wrong.verifyInstallation("tenant-a", "release", value, payload, NOW));
        assertThrows(IllegalArgumentException.class, () -> verifier().verifyInstallation("tenant-b", "release", value, payload, NOW));
        assertThrows(IllegalArgumentException.class, () -> verifier().verifyInstallation("tenant-a", "release",
                change(value, n -> n.put("generation", 3)), payload, NOW));
        var empty = new ReferralReleaseVerifier(Map.of(), Map.of(), "test", "cell", "default");
        assertThrows(IllegalArgumentException.class, () -> empty.verifyInstallation("tenant-a", "release", value, payload, NOW));
    }

    @Test void corruptEmptyNullAndOversizedBytesReject() {
        byte[] payload = bytes(BODY); var value = manifest(artifact(payload));
        payload[0] = 0;
        for (byte[] bad : new byte[][] {payload, new byte[0], null, new byte[1_048_577]})
            assertThrows(IllegalArgumentException.class, () -> verifier().verifyInstallation("tenant-a", "release", value, bad, NOW));
    }
}
