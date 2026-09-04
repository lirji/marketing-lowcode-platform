package com.acme.marketing.compiler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.acme.marketing.compiler.application.RuleCompilerService;
import com.acme.marketing.compiler.infrastructure.InMemoryArtifactStore;
import com.acme.marketing.contracts.artifact.ArtifactAttestation;
import com.acme.marketing.lowcode.model.Dialect;
import com.acme.marketing.lowcode.model.GraphDefinition;
import com.acme.marketing.lowcode.model.GraphEdge;
import com.acme.marketing.lowcode.model.GraphNode;
import com.acme.marketing.platform.crypto.Ed25519;
import com.acme.marketing.platform.crypto.SigningKeyRing;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import tools.jackson.databind.ObjectMapper;

class RuleCompilerServiceTest {
    private static final String DRL = """
            package offer
            import com.acme.marketing.decision.rule.RuleFacts
            global java.util.List resultCodes
            rule "eligible"
            when
              $facts : RuleFacts( has("amount"), longValue("amount") >= 100L )
            then
              resultCodes.add("ELIGIBLE");
            end
            """;
    private static final String DMN = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="https://www.omg.org/spec/DMN/20230324/MODEL/"
              id="definitions_offer" name="OfferModel" namespace="https://acme.example/offer">
              <inputData id="input_amount" name="amount">
                <variable id="var_amount" name="amount" typeRef="number"/>
              </inputData>
              <decision id="decision_eligible" name="Eligible">
                <variable id="var_eligible" name="Eligible" typeRef="boolean"/>
                <informationRequirement><requiredInput href="#input_amount"/></informationRequirement>
                <literalExpression id="expression_eligible"><text>amount &gt;= 100</text></literalExpression>
              </decision>
            </definitions>
            """;

    @Test
    void graphCompilationIsContentAddressedAndSigned() {
        SigningKeyRing keys = new SigningKeyRing("test-key", Ed25519.generateKeyPair());
        RuleCompilerService service = new RuleCompilerService(new InMemoryArtifactStore(), new ObjectMapper(),
                Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC), keys);
        GraphDefinition graph = new GraphDefinition("offer-a", Dialect.OFFER_DECISION_DAG, "1.0.0",
                List.of(new GraphNode("start", "offer.start", "1.0.0", Map.of())),
                List.of(), Map.of(), Map.of());
        RuleCompilerService.CompileRequest request = new RuleCompilerService.CompileRequest(
                "offer-a", 1, RuleCompilerService.Format.GRAPH, null, null, null, graph);

        RuleCompilerService.CompileReport first = service.compile("tenant-a", request);
        RuleCompilerService.CompileReport second = service.compile("tenant-a", request);

        assertTrue(first.valid());
        assertEquals(first.artifactId(), second.artifactId());
        var artifact = service.artifact("tenant-a", first.artifactId());
        assertEquals("test-key", first.signatureKeyId());
        assertTrue(ArtifactAttestation.verify(keys.publicKey("test-key"), "tenant-a",
                artifact.reference("s3://artifacts/" + artifact.artifactId())));

        RuleCompilerService.CompileReport nextVersion = service.compile("tenant-a",
                new RuleCompilerService.CompileRequest("offer-a", 2, RuleCompilerService.Format.GRAPH,
                        null, null, null, graph));
        assertTrue(!first.artifactId().equals(nextVersion.artifactId()),
                "artifact identity must include its approved definition version");
    }

    @ParameterizedTest
    @EnumSource(RuleCompilerService.Format.class)
    void everySuccessfulArtifactFormatHasAVerifiableAttestation(RuleCompilerService.Format format) {
        SigningKeyRing keys = new SigningKeyRing("rotation-key", Ed25519.generateKeyPair());
        RuleCompilerService service = new RuleCompilerService(new InMemoryArtifactStore(), new ObjectMapper(),
                Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC), keys);

        RuleCompilerService.CompileReport report = service.compile("tenant-a", request(format));

        assertTrue(report.valid(), () -> format + ": " + String.join("\n", report.messages()));
        var artifact = service.artifact("tenant-a", report.artifactId());
        assertEquals(format.name(), artifact.type());
        assertTrue(ArtifactAttestation.verify(keys.publicKey(report.signatureKeyId()), "tenant-a",
                artifact.reference("s3://artifacts/" + artifact.artifactId())));
    }

    @Test
    void rejectsExecutableDrlAndExternalDmnReferencesBeforeKieCompilation() {
        SigningKeyRing keys = new SigningKeyRing("test-key", Ed25519.generateKeyPair());
        RuleCompilerService service = new RuleCompilerService(new InMemoryArtifactStore(), new ObjectMapper(),
                Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC), keys);
        String executable = """
                package offer
                import com.acme.marketing.decision.rule.RuleFacts
                global java.util.List resultCodes
                rule "unsafe" when $facts : RuleFacts() then System.exit(0); end
                """;
        String external = """
                <definitions xmlns="https://www.omg.org/spec/DMN/20230324/MODEL/"
                  id="bad" name="bad" namespace="https://acme.example/bad">
                  <import namespace="https://attacker.invalid/model" locationURI="https://attacker.invalid/a.dmn"/>
                </definitions>
                """;

        assertTrue(!service.compile("tenant-a", new RuleCompilerService.CompileRequest("bad-drl", 1,
                RuleCompilerService.Format.DRL, "offer", null, executable, null)).valid());
        assertTrue(!service.compile("tenant-a", new RuleCompilerService.CompileRequest("bad-dmn", 1,
                RuleCompilerService.Format.DMN, "https://acme.example/bad", "bad", external, null)).valid());
    }

    private static RuleCompilerService.CompileRequest request(RuleCompilerService.Format format) {
        GraphDefinition graph = switch (format) {
            case GRAPH -> new GraphDefinition("definition-a", Dialect.AUDIENCE_EXPRESSION, "1.0.0",
                    List.of(new GraphNode("source", "audience.source", "1.0.0", Map.of())),
                    List.of(), Map.of(), Map.of());
            case OFFER_POLICY -> new GraphDefinition("definition-a", Dialect.OFFER_DECISION_DAG, "1.0.0",
                    List.of(new GraphNode("offer", "offer.fixed", "1.0.0", Map.of(
                            "benefitDefinitionVersion", "coupon-v1", "amountMinor", "100",
                            "thresholdMinor", "1000", "validFrom", "2025-01-01T00:00:00Z",
                            "validTo", "2027-01-01T00:00:00Z"))), List.of(), Map.of(), Map.of());
            case JOURNEY_PLAN -> new GraphDefinition("definition-a", Dialect.JOURNEY_STATE_MACHINE, "1.0.0",
                    List.of(new GraphNode("trigger", "journey.trigger", "1.0.0", Map.of()),
                            new GraphNode("end", "journey.end", "1.0.0", Map.of())),
                    List.of(new GraphEdge("edge-1", "trigger", "next", "end", "in")), Map.of(), Map.of());
            case DRL, DMN -> null;
        };
        String source = format == RuleCompilerService.Format.DRL ? DRL
                : format == RuleCompilerService.Format.DMN ? DMN : null;
        return new RuleCompilerService.CompileRequest("definition-a", 1, format,
                format == RuleCompilerService.Format.DMN ? "https://acme.example/offer" : "offer",
                "OfferModel", source, graph);
    }
}
