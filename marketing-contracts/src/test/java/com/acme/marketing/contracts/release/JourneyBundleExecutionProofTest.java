package com.acme.marketing.contracts.release;

import static org.junit.jupiter.api.Assertions.*;

import com.acme.marketing.platform.crypto.Ed25519;
import com.acme.marketing.platform.error.ConflictException;
import com.acme.marketing.platform.identity.TenantId;
import java.security.KeyPair;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** 覆盖实际执行证据的所有签名字段、目标部署、源链和期限，不用普通READY冒充执行完成。 */
class JourneyBundleExecutionProofTest {
    private static final Instant NOW = Instant.parse("2026-11-18T00:00:00Z");
    private static final String JOB = "1".repeat(32), BUILD = "sha256:" + "2".repeat(64);
    private final KeyPair control = Ed25519.generateKeyPair(), execution = Ed25519.generateKeyPair();
    private final ObjectMapper mapper = new ObjectMapper();
    private final TenantId tenant = new TenantId("tenant");
    private final PinnedJourneyBundleExecutionVerifier verifier = new PinnedJourneyBundleExecutionVerifier(
            Map.of("control", control.getPublic()), Map.of("execution", execution.getPublic()));

    @Test void fullWireRoundTripPinsActualDeploymentAndExactSource() {
        var source = source(1); var probe = probe(source); var ack = ack(probe);
        var decodedProbe = mapper.readValue(mapper.writeValueAsString(probe), JourneyBundleExecutionProbe.class);
        var decodedAck = mapper.readValue(mapper.writeValueAsString(ack), JourneyBundleExecutionAck.class);
        assertEquals(probe, decodedProbe); assertEquals(ack, decodedAck);
        assertEquals("journey-member", verifier.requireProbe(expected(), probe, source, NOW).manifestId());
        verifier.requireAck(expected(), decodedProbe, source, decodedAck, NOW);
        assertEquals("tenant", mapper.readTree(mapper.writeValueAsString(probe)).path("tenantId").path("value").asString());
    }

    @Test void everyProbeFieldIsCoveredAndNoOtherSignaturePurposeCanReplaceIt() {
        var probe = probe(source(1)); var node = (ObjectNode) mapper.valueToTree(probe);
        for (String field : node.propertyNames()) {
            ObjectNode changed = node.deepCopy();
            switch (field) {
                case "tenantId" -> ((ObjectNode) changed.get(field)).put("value", "other");
                case "activationSequence" -> changed.put(field, 2);
                case "targetParallelism" -> changed.put(field, 3);
                case "targetJobId" -> changed.put(field, "3".repeat(32));
                case "activationSignature", "signature" -> changed.put(field, otherSignature());
                case "issuedAt" -> changed.put(field, NOW.plusSeconds(1).toString());
                case "expiresAt" -> changed.put(field, NOW.plusSeconds(121).toString());
                default -> changed.put(field, "other");
            }
            var modified = mapper.treeToValue(changed, JourneyBundleExecutionProbe.class);
            assertFalse(JourneyBundleExecutionProbeSigner.verify(control.getPublic(), modified), field);
        }
        var wrongRole = JourneyBundleExecutionProbeSigner.sign(execution.getPrivate(), probe);
        assertThrows(ConflictException.class, () -> verifier.requireProbe(expected(), wrongRole, source(1), NOW));
    }

    @Test void everyAckFieldIncludingOriginalProbeSignatureIsCovered() {
        var probe = probe(source(1)); var original = ack(probe); var node = (ObjectNode) mapper.valueToTree(original);
        for (String field : node.propertyNames()) {
            ObjectNode changed = node.deepCopy();
            switch (field) {
                case "probe" -> ((ObjectNode) changed.get(field)).put("signature", otherSignature());
                case "executionProtocol" -> {
                    changed.put(field, "legacy-ready");
                    assertThrows(tools.jackson.databind.exc.ValueInstantiationException.class, () -> mapper.treeToValue(changed, JourneyBundleExecutionAck.class)); continue;
                }
                case "jobId" -> changed.put(field, "3".repeat(32));
                case "parallelism" -> changed.put(field, 3);
                case "subtaskIndex", "attemptNumber" -> changed.put(field, 1);
                case "buildDigest" -> changed.put(field, "sha256:" + "3".repeat(64));
                case "acknowledgedAt" -> changed.put(field, NOW.plusSeconds(1).toString());
                case "signature" -> changed.put(field, otherSignature());
                default -> changed.put(field, "other");
            }
            var modified = mapper.treeToValue(changed, JourneyBundleExecutionAck.class);
            assertFalse(JourneyBundleExecutionAckSigner.verify(execution.getPublic(), modified), field);
        }
        var crossPurpose = new JourneyBundleExecutionAck(probe, original.executionProtocol(), JOB, 2, 0, 0, BUILD, NOW, "execution", probe.signature());
        assertFalse(JourneyBundleExecutionAckSigner.verify(control.getPublic(), crossPurpose));
        assertFalse(JourneyBundleExecutionAckSigner.verify(execution.getPublic(), crossPurpose));
    }

    @Test void trustedExpectedContextCannotBeTakenFromTheReceiptItself() {
        var source = source(1); var probe = probe(source); var ack = ack(probe);
        var node = (ObjectNode) mapper.valueToTree(expected());
        for (String field : node.propertyNames()) {
            ObjectNode changed = node.deepCopy();
            switch (field) {
                case "tenantId" -> ((ObjectNode) changed.get(field)).put("value", "other");
                case "parallelism" -> changed.put(field, 3);
                case "jobId" -> changed.put(field, "3".repeat(32));
                case "buildDigest" -> changed.put(field, "sha256:" + "3".repeat(64));
                default -> changed.put(field, "other");
            }
            var context = mapper.treeToValue(changed, PinnedJourneyBundleExecutionVerifier.ExpectedDeployment.class);
            assertThrows(ConflictException.class, () -> verifier.requireAck(context, probe, source, ack, NOW), field);
        }
    }

    @Test void freshProofCannotSubstituteAnotherChallengeOrSourceOrExecutionRole() {
        var source = source(1); var probe = probe(source); var original = ack(probe);
        ObjectNode node = (ObjectNode) mapper.valueToTree(probe); node.put("probeId", "another");
        var another = JourneyBundleExecutionProbeSigner.sign(control.getPrivate(), mapper.treeToValue(node, JourneyBundleExecutionProbe.class));
        assertThrows(ConflictException.class, () -> verifier.requireAck(expected(), probe, source, ack(another), NOW));
        assertThrows(ConflictException.class, () -> verifier.requireAck(expected(), probe, source(2), original, NOW));
        var wrongRole = JourneyBundleExecutionAckSigner.sign(control.getPrivate(), original);
        assertThrows(ConflictException.class, () -> verifier.requireAck(expected(), probe, source, wrongRole, NOW));
        var noExecutionTrust = new PinnedJourneyBundleExecutionVerifier(Map.of("control", control.getPublic()), Map.of());
        assertThrows(ConflictException.class, () -> noExecutionTrust.requireAck(expected(), probe, source, original, NOW));
    }

    @Test void expiryFutureAcknowledgementAndOriginalMemberSignaturesRemainMandatory() {
        var source = source(1); var probe = probe(source); var ack = ack(probe);
        assertThrows(ConflictException.class, () -> verifier.requireProbe(expected(), probe, source, NOW.minusNanos(1)));
        assertThrows(ConflictException.class, () -> verifier.requireAck(expected(), probe, source, ack, probe.expiresAt()));
        var future = JourneyBundleExecutionAckSigner.sign(execution.getPrivate(), new JourneyBundleExecutionAck(probe,
                ack.executionProtocol(), JOB, 2, 0, 0, BUILD, NOW.plusSeconds(61), "execution", ""));
        assertThrows(ConflictException.class, () -> verifier.requireAck(expected(), probe, source, future, NOW));
        ObjectNode sourceNode = (ObjectNode) mapper.valueToTree(source);
        ((ObjectNode) sourceNode.path("bundle").path("members").get(0)).put("signature", otherSignature());
        List<ReleaseManifest> changedMembers = new ArrayList<>();
        sourceNode.path("bundle").path("members").forEach(value -> changedMembers.add(mapper.treeToValue(value, ReleaseManifest.class)));
        ((ObjectNode) sourceNode.get("bundle")).put("contentHash", ReleaseBundleManifest.contentHash(tenant.value(), "campaign", changedMembers));
        var tampered = mapper.treeToValue(sourceNode, ReleaseBundleActivationDirective.class);
        var bundle = ReleaseBundleManifestSigner.sign(control.getPrivate(), tampered.bundle());
        var resigned = ReleaseBundleActivationSigner.sign(control.getPrivate(), new ReleaseBundleActivationDirective(tampered.directiveId(), bundle,
                tampered.activationSequence(), tampered.action(), tampered.organizationId(), tampered.shopId(), tampered.authorizedAt(), tampered.authorizedBy(), "control", ""));
        var matchingProbe = probe(resigned);
        assertThrows(ConflictException.class, () -> verifier.requireProbe(expected(), matchingProbe, resigned, NOW));
    }

    @Test void boundsRejectInvalidTaskCoordinatesAndUnboundedProbeLifetimes() {
        var probe = probe(source(1)); var node = (ObjectNode) mapper.valueToTree(probe);
        node.put("expiresAt", NOW.plusSeconds(301).toString());
        assertThrows(tools.jackson.databind.exc.ValueInstantiationException.class, () -> mapper.treeToValue(node, JourneyBundleExecutionProbe.class));
        node.put("expiresAt", NOW.plusSeconds(300).toString()); node.put("targetParallelism", JourneyBundleExecutionProbe.MAX_PARALLELISM);
        assertNotNull(mapper.treeToValue(node, JourneyBundleExecutionProbe.class));
        node.put("targetParallelism", JourneyBundleExecutionProbe.MAX_PARALLELISM + 1);
        assertThrows(tools.jackson.databind.exc.ValueInstantiationException.class, () -> mapper.treeToValue(node, JourneyBundleExecutionProbe.class));
        assertThrows(IllegalArgumentException.class, () -> new JourneyBundleExecutionAck(probe, JourneyBundleExecutionAck.EXECUTION_PROTOCOL,
                JOB, 2, 2, 0, BUILD, NOW, "execution", ""));
        assertThrows(IllegalArgumentException.class, () -> new JourneyBundleExecutionAck(probe, JourneyBundleExecutionAck.EXECUTION_PROTOCOL,
                JOB, 2, 0, -1, BUILD, NOW, "execution", ""));
        assertThrows(IllegalArgumentException.class, () -> new JourneyBundleExecutionAck(probe, JourneyBundleExecutionAck.EXECUTION_PROTOCOL,
                JOB, 2, 0, 0, "development", NOW, "execution", ""));
    }

    private JourneyBundleExecutionProbe probe(ReleaseBundleActivationDirective source) {
        return JourneyBundleExecutionProbeSigner.sign(control.getPrivate(), new JourneyBundleExecutionProbe("probe", tenant, "campaign", "local", "cell", "main",
                "journey-member", source.activationSequence(), source.signature(), JOB, 2, "participation", NOW, NOW.plusSeconds(120), "control", ""));
    }
    private JourneyBundleExecutionAck ack(JourneyBundleExecutionProbe probe) {
        return JourneyBundleExecutionAckSigner.sign(execution.getPrivate(), new JourneyBundleExecutionAck(probe,
                JourneyBundleExecutionAck.EXECUTION_PROTOCOL, JOB, 2, 0, 0, BUILD, NOW, "execution", ""));
    }
    private PinnedJourneyBundleExecutionVerifier.ExpectedDeployment expected() {
        return new PinnedJourneyBundleExecutionVerifier.ExpectedDeployment(tenant, "campaign", "local", "cell", "main", JOB, 2, BUILD, "participation");
    }
    private String otherSignature() { return Base64.getUrlEncoder().withoutPadding().encodeToString(Ed25519.sign(control.getPrivate(), new byte[]{1})); }
    private ReleaseBundleActivationDirective source(long sequence) {
        var members = List.of(member("journey-member", "journey"), member("decision-member", "decision"));
        var bundle = ReleaseBundleManifestSigner.sign(control.getPrivate(), new ReleaseBundleManifest("bundle", tenant, "campaign", 1,
                "local", "cell", ReleaseBundleManifest.contentHash(tenant.value(), "campaign", members), members, NOW.minusSeconds(2), NOW.plusSeconds(600), "control", ""));
        return ReleaseBundleActivationSigner.sign(control.getPrivate(), new ReleaseBundleActivationDirective("activation-" + sequence, bundle, sequence,
                "ACTIVATE", "org", "", NOW.minusSeconds(1), "operator", "control", ""));
    }
    private ReleaseManifest member(String id, String runtime) {
        var artifact = new ArtifactReference(id, "JOURNEY", "s3://artifacts/" + id, "sha256:" + "a".repeat(64), "sha256:" + "b".repeat(64),
                "compiler", "fixture", "marketing-journey/1", "definition", 1);
        return new ReleaseManifestSigner().sign(new ReleaseManifest(id, tenant, "local", "cell", runtime, "main", 1, 0,
                List.of(), List.of(), List.of(artifact), Map.of(), 0, NOW.minusSeconds(3), NOW.plusSeconds(600), "author", List.of("case"), NOW.minusSeconds(3), ""), control.getPrivate());
    }
}
