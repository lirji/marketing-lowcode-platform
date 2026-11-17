package com.acme.marketing.contracts.release;

import static org.junit.jupiter.api.Assertions.*;

import com.acme.marketing.platform.crypto.Ed25519;
import com.acme.marketing.platform.error.ConflictException;
import com.acme.marketing.platform.identity.TenantId;
import java.security.KeyPair;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** 参与事实验证历史绑定，不能成为跨主体、跨用途或跳过Control信任的通行证。 */
class ReleaseBundleParticipationTest {
    private final KeyPair memberKey = Ed25519.generateKeyPair();
    private final KeyPair controlKey = Ed25519.generateKeyPair();
    private final KeyPair decisionKey = Ed25519.generateKeyPair();
    private final Instant now = Instant.parse("2026-11-17T00:00:00Z");
    private final TenantId tenant = new TenantId("tenant");
    private final ObjectMapper mapper = new ObjectMapper();
    private final PinnedReleaseBundleActivationVerifier controlVerifier = new PinnedReleaseBundleActivationVerifier(
            Map.of("member", memberKey.getPublic(), "control", controlKey.getPublic()));
    private final PinnedReleaseBundleParticipationVerifier verifier = new PinnedReleaseBundleParticipationVerifier(
            Map.of("decision", decisionKey.getPublic()), controlVerifier);

    @Test void wireRoundTripPreservesHistoricalBindingAfterRulesExpire() {
        var directive = directive(); var proof = signed(directive, now);
        String json = mapper.writeValueAsString(proof);
        assertEquals(proof, mapper.readValue(json, ReleaseBundleParticipation.class));
        assertEquals("tenant", mapper.readTree(json).path("tenantId").path("value").asString());
        assertFalse(json.contains("subject-secret"));
        assertDoesNotThrow(() -> verifier.requireValid(context(), proof, directive, now.plusSeconds(86400)));
        assertThrows(ConflictException.class, () -> controlVerifier.requireValid("tenant", directive, now.plusSeconds(86400)));
    }

    @Test void eachProofFieldIsCryptographicallyBound() {
        var directive = directive(); var proof = signed(directive, now);
        for (var change : Map.ofEntries(Map.entry("bindingId", "other"), Map.entry("campaignId", "other"),
                Map.entry("environment", "other"), Map.entry("cell", "other"), Map.entry("participationId", "other"),
                Map.entry("subjectHash", "a".repeat(64)), Map.entry("directiveId", "other"),
                Map.entry("activationSignature", directive.bundle().signature()), Map.entry("bundleId", "other"),
                Map.entry("contentHash", "b".repeat(64)), Map.entry("admittedAt", now.plusSeconds(1).toString()),
                Map.entry("signatureKeyId", "other"), Map.entry("signature", directive.signature())).entrySet()) {
            ObjectNode changed = tree(proof); changed.put(change.getKey(), change.getValue());
            assertFalse(ReleaseBundleParticipationSigner.verify(decisionKey.getPublic(), read(changed)), change.getKey());
        }
        for (String field : List.of("activationSequence", "bundleVersion")) {
            var changed = tree(proof); changed.put(field, 2);
            assertFalse(ReleaseBundleParticipationSigner.verify(decisionKey.getPublic(), read(changed)), field);
        }
        var changed = tree(proof); ((ObjectNode) changed.path("tenantId")).put("value", "other");
        assertFalse(ReleaseBundleParticipationSigner.verify(decisionKey.getPublic(), read(changed)));
        assertFalse(ReleaseBundleParticipationSigner.verify(null, proof));
        assertFalse(ReleaseBundleParticipationSigner.verify(decisionKey.getPublic(), null));
    }

    @Test void trustedIngressContextMustMatchAllDimensionsExactly() {
        var directive = directive(); var proof = signed(directive, now);
        var contexts = List.of(
                new PinnedReleaseBundleParticipationVerifier.ExpectedContext(new TenantId("other"), "campaign", "local", "cell", "order/1", "subject-secret"),
                new PinnedReleaseBundleParticipationVerifier.ExpectedContext(tenant, "other", "local", "cell", "order/1", "subject-secret"),
                new PinnedReleaseBundleParticipationVerifier.ExpectedContext(tenant, "campaign", "prod", "cell", "order/1", "subject-secret"),
                new PinnedReleaseBundleParticipationVerifier.ExpectedContext(tenant, "campaign", "local", "other", "order/1", "subject-secret"),
                new PinnedReleaseBundleParticipationVerifier.ExpectedContext(tenant, "campaign", "local", "cell", "Order/1", "subject-secret"),
                new PinnedReleaseBundleParticipationVerifier.ExpectedContext(tenant, "campaign", "local", "cell", "order/1 ", "subject-secret"),
                new PinnedReleaseBundleParticipationVerifier.ExpectedContext(tenant, "campaign", "local", "cell", "order/1", "another-subject"));
        for (var context : contexts) assertRejected(context, proof, directive, now);
        assertRejected(null, proof, directive, now);
        assertRejected(context(), null, directive, now);
        assertRejected(context(), proof, null, now);
        assertRejected(context(), proof, directive, null);
    }

    @Test void validProofSignatureCannotHideMismatchedSourceIndexes() {
        var directive = directive(); var proof = signed(directive, now);
        for (var change : Map.of("directiveId", "other", "bundleId", "other", "contentHash", "b".repeat(64),
                "activationSignature", directive.bundle().signature()).entrySet()) {
            var altered = tree(proof); altered.put(change.getKey(), change.getValue());
            var resigned = ReleaseBundleParticipationSigner.sign(decisionKey.getPrivate(), read(altered));
            assertTrue(ReleaseBundleParticipationSigner.verify(decisionKey.getPublic(), resigned));
            assertRejected(context(), resigned, directive, now);
        }
        for (String field : List.of("activationSequence", "bundleVersion")) {
            var altered = tree(proof); altered.put(field, 2);
            assertRejected(context(), ReleaseBundleParticipationSigner.sign(decisionKey.getPrivate(), read(altered)), directive, now);
        }
    }

    @Test void replacingControlScopeOrActionCannotReuseAnExistingBinding() {
        var directive = directive(); var proof = signed(directive, now);
        for (var change : Map.of("organizationId", "other", "shopId", "other", "action", "ROLLBACK", "authorizedBy", "other").entrySet()) {
            var altered = tree(directive); altered.put(change.getKey(), change.getValue());
            var resigned = ReleaseBundleActivationSigner.sign(controlKey.getPrivate(), mapper.readValue(altered.toString(), ReleaseBundleActivationDirective.class));
            assertDoesNotThrow(() -> controlVerifier.requireValid("tenant", resigned, now));
            assertRejected(context(), proof, resigned, now);
        }
    }

    @Test void rolesAndSignaturePurposesAreSeparateEvenWhenTheSameKeyIsTrusted() {
        var directive = directive(); var proof = signed(directive, now);
        var controlSigned = ReleaseBundleParticipationSigner.sign(controlKey.getPrivate(), proof);
        assertRejected(context(), controlSigned, directive, now);
        var sameKeyVerifier = new PinnedReleaseBundleParticipationVerifier(Map.of("decision", controlKey.getPublic()), controlVerifier);
        for (String signature : List.of(directive.signature(), directive.bundle().signature(), directive.bundle().members().getFirst().signature())) {
            var altered = tree(proof); altered.put("signature", signature);
            assertThrows(ConflictException.class, () -> sameKeyVerifier.requireValid(context(), read(altered), directive, now));
        }
        var withoutDecision = new PinnedReleaseBundleParticipationVerifier(Map.of(), controlVerifier);
        assertThrows(ConflictException.class, () -> withoutDecision.requireValid(context(), proof, directive, now));
        var withoutMember = new PinnedReleaseBundleParticipationVerifier(Map.of("decision", decisionKey.getPublic()),
                new PinnedReleaseBundleActivationVerifier(Map.of("control", controlKey.getPublic())));
        assertThrows(ConflictException.class, () -> withoutMember.requireValid(context(), proof, directive, now.plusSeconds(86400)));
        var withoutControl = new PinnedReleaseBundleParticipationVerifier(Map.of("decision", decisionKey.getPublic()),
                new PinnedReleaseBundleActivationVerifier(Map.of("member", memberKey.getPublic())));
        assertThrows(ConflictException.class, () -> withoutControl.requireValid(context(), proof, directive, now));
    }

    @Test void admissionTimeMustBelongToTheOriginalAuthorizationWindow() {
        var directive = directive();
        assertRejected(context(), signed(directive, now.minusNanos(1)), directive, now);
        assertRejected(context(), signed(directive, directive.bundle().expiresAt()), directive, now.plusSeconds(1000));
        assertDoesNotThrow(() -> verifier.requireValid(context(), signed(directive, directive.bundle().expiresAt().minusNanos(1)), directive, now.plusSeconds(1000)));
        assertDoesNotThrow(() -> verifier.requireValid(context(), signed(directive, now.plusSeconds(60)), directive, now));
        assertRejected(context(), signed(directive, now.plusSeconds(60).plusNanos(1)), directive, now);
    }

    @Test void unsignedMissingMalformedAndOversizedFieldsFailClosed() {
        var directive = directive(); var proof = signed(directive, now);
        var empty = tree(proof); empty.put("signature", ""); assertRejected(context(), read(empty), directive, now);
        for (String field : List.of("bindingId", "tenantId", "campaignId", "environment", "cell", "participationId", "subjectHash",
                "directiveId", "activationSignature", "bundleId", "contentHash", "admittedAt", "signatureKeyId")) {
            var changed = tree(proof); changed.remove(field); assertThrows(RuntimeException.class, () -> read(changed), field);
        }
        for (String field : List.of("activationSequence", "bundleVersion")) {
            var changed = tree(proof); changed.put(field, 0); assertThrows(RuntimeException.class, () -> read(changed), field);
        }
        for (var change : Map.of("participationId", "p".repeat(129), "environment", "e".repeat(33), "bindingId", "b".repeat(65),
                "subjectHash", "A".repeat(64), "signature", "%broken", "activationSignature", directive.signature() + "=").entrySet()) {
            var changed = tree(proof); changed.put(change.getKey(), change.getValue());
            assertThrows(RuntimeException.class, () -> read(changed), change.getKey());
        }
        var controlCharacter = tree(proof); controlCharacter.put("participationId", "a\nb");
        assertThrows(RuntimeException.class, () -> read(controlCharacter));
        var unknown = tree(proof); unknown.put("overrideVersion", 2);
        assertThrows(RuntimeException.class, () -> mapper.readerFor(ReleaseBundleParticipation.class)
                .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).readValue(unknown.toString()));
    }

    @Test void subjectHashIsTenantBoundCaseSensitiveAndLengthEncoded() {
        assertNotEquals(ReleaseBundleParticipation.subjectHash(tenant, "subject-secret"),
                ReleaseBundleParticipation.subjectHash(new TenantId("other"), "subject-secret"));
        assertNotEquals(ReleaseBundleParticipation.subjectHash(tenant, "subject-secret"), ReleaseBundleParticipation.subjectHash(tenant, "Subject-secret"));
        assertNotEquals(ReleaseBundleParticipation.subjectHash(new TenantId("ab"), "c"), ReleaseBundleParticipation.subjectHash(new TenantId("a"), "bc"));
        assertNotEquals(ReleaseBundleParticipation.subjectHash(tenant, "subject-secret"), ReleaseBundleParticipation.subjectHash(tenant, "subject-secret "));
        assertDoesNotThrow(() -> ReleaseBundleParticipation.subjectHash(tenant, "会员/" + "a".repeat(509)));
        assertThrows(IllegalArgumentException.class, () -> ReleaseBundleParticipation.subjectHash(tenant, "s".repeat(513)));
        assertThrows(IllegalArgumentException.class, () -> ReleaseBundleParticipation.subjectHash(tenant, " "));
        assertThrows(IllegalArgumentException.class, () -> ReleaseBundleParticipation.subjectHash(tenant, "a\nb"));
        assertThrows(IllegalArgumentException.class, () -> ReleaseBundleParticipation.subjectHash(tenant, new String(new char[] {(char) 0xD800})));
        assertThrows(IllegalArgumentException.class, () -> ReleaseBundleParticipation.subjectHash(tenant, new String(new char[] {(char) 0xDC00})));
        assertDoesNotThrow(() -> ReleaseBundleParticipation.subjectHash(tenant, "用户😀"));
    }

    private PinnedReleaseBundleParticipationVerifier.ExpectedContext context() {
        return new PinnedReleaseBundleParticipationVerifier.ExpectedContext(tenant, "campaign", "local", "cell", "order/1", "subject-secret");
    }
    private void assertRejected(PinnedReleaseBundleParticipationVerifier.ExpectedContext context, ReleaseBundleParticipation proof,
            ReleaseBundleActivationDirective directive, Instant at) {
        assertEquals("RELEASE_BUNDLE_PARTICIPATION_INVALID", assertThrows(ConflictException.class,
                () -> verifier.requireValid(context, proof, directive, at)).code());
    }
    private ObjectNode tree(Object value) { return (ObjectNode) mapper.readTree(mapper.writeValueAsString(value)); }
    private ReleaseBundleParticipation read(ObjectNode tree) { return mapper.readValue(tree.toString(), ReleaseBundleParticipation.class); }
    private ReleaseBundleParticipation signed(ReleaseBundleActivationDirective directive, Instant admittedAt) {
        var bundle = directive.bundle();
        return ReleaseBundleParticipationSigner.sign(decisionKey.getPrivate(), new ReleaseBundleParticipation("binding", tenant, bundle.campaignId(),
                bundle.environment(), bundle.cell(), "order/1", ReleaseBundleParticipation.subjectHash(tenant, "subject-secret"), directive.directiveId(),
                directive.activationSequence(), directive.signature(), bundle.bundleId(), bundle.version(), bundle.contentHash(), admittedAt, "decision", ""));
    }
    private ReleaseBundleActivationDirective directive() {
        var members = List.of(member("decision"), member("journey"));
        var bundle = ReleaseBundleManifestSigner.sign(controlKey.getPrivate(), new ReleaseBundleManifest("bundle", tenant, "campaign", 1,
                "local", "cell", ReleaseBundleManifest.contentHash("tenant", "campaign", members), members, now, now.plusSeconds(600), "control", ""));
        return ReleaseBundleActivationSigner.sign(controlKey.getPrivate(), new ReleaseBundleActivationDirective("directive", bundle, 1,
                ReleaseBundleActivationDirective.Action.ACTIVATE.code(), "org", "shop", now, "author", "control", ""));
    }
    private ReleaseManifest member(String runtime) {
        var artifact = new ArtifactReference("a-" + runtime, "OFFER", "memory:test", "sha256:" + "a".repeat(64),
                "sha256:" + "b".repeat(64), "compiler", "attestation", "abi/1", "def-" + runtime, 1);
        return new ReleaseManifestSigner().sign(new ReleaseManifest("m-" + runtime, tenant, "local", "cell", runtime,
                "main", 1, 0, List.of(), List.of(), List.of(artifact), Map.of(), 0, now, now.plusSeconds(600), "author", List.of("approval"), now, ""), memberKey.getPrivate());
    }
}
