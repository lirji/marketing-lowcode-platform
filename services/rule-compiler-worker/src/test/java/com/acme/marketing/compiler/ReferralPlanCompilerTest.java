package com.acme.marketing.compiler;

import com.acme.marketing.referral.ReferralPlanCompiler;
import com.acme.marketing.compiler.application.RuleCompilerService;
import com.acme.marketing.compiler.infrastructure.InMemoryArtifactStore;
import com.acme.marketing.contracts.artifact.ArtifactAttestation;
import com.acme.marketing.lowcode.compiler.CanonicalGraphHasher;
import com.acme.marketing.lowcode.model.*;
import com.acme.marketing.platform.crypto.Ed25519;
import com.acme.marketing.platform.crypto.SigningKeyRing;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

/** 编译边界与签名回归；图中数字仅是纯测试 fixture，不代表生产已确认配置。 */
class ReferralPlanCompilerTest {
    @Test void rawEnumOrdinalsBooleansAndNullCannotBypassReferralChecks() {
        var mapper = new ObjectMapper();
        var reader = new com.acme.marketing.compiler.application.ReferralCompileRequestReader(mapper);
        String json = mapper.writeValueAsString(request(RuleCompilerService.Format.REFERRAL_PLAN, 1, graph()));
        for (String value : List.of(Integer.toString(RuleCompilerService.Format.REFERRAL_PLAN.ordinal()), "true", "null")) {
            String malformed = json.replace("\"format\":\"REFERRAL_PLAN\"", "\"format\":" + value);
            assertNotEquals(json, malformed);
            assertThrows(IllegalArgumentException.class, () -> reader.read(mapper.readTree(malformed)));
        }
        for (String value : List.of(Integer.toString(Dialect.REFERRAL_POLICY.ordinal()), "true", "null")) {
            String malformed = json.replace("\"dialect\":\"REFERRAL_POLICY\"", "\"dialect\":" + value);
            assertNotEquals(json, malformed);
            assertThrows(IllegalArgumentException.class, () -> reader.read(mapper.readTree(malformed)));
        }
    }
    @Test void rawReferralConfigCannotCoerceJsonNumbersOrBooleansIntoStrings() {
        var mapper = new ObjectMapper();
        var reader = new com.acme.marketing.compiler.application.ReferralCompileRequestReader(mapper);
        String json = mapper.writeValueAsString(request(RuleCompilerService.Format.REFERRAL_PLAN, 1, graph()));
        assertEquals(graph(), reader.read(mapper.readTree(json)).graph());
        for (String value : List.of("1", "true", "{}", "[]", "null")) {
            String malformed = json.replace("\"quantity\":\"1\"", "\"quantity\":" + value);
            assertNotEquals(json, malformed);
            assertThrows(IllegalArgumentException.class, () -> reader.read(mapper.readTree(malformed)));
        }
        var legacy = new GraphDefinition("definition-a", Dialect.OFFER_DECISION_DAG, "1.0.0",
                List.of(new GraphNode("n", "offer.fixed", "1.0.0", Map.of("amountMinor", "1"))), List.of(), Map.of(), Map.of());
        String oldJson = mapper.writeValueAsString(request(RuleCompilerService.Format.GRAPH, 1, legacy))
                .replace("\"amountMinor\":\"1\"", "\"amountMinor\":1");
        assertEquals("1", reader.read(mapper.readTree(oldJson)).graph().nodes().getFirst().config().get("amountMinor"));
    }
    @Test void ambiguousLegacyDelimiterExamplesCompileToDistinctSignedSourceDigests() {
        var left = referenceGraph("a;skuVersion=b", "c");
        var right = referenceGraph("a", "b;skuVersion=c");
        assertNotEquals(new ReferralPlanCompiler().compile(left), new ReferralPlanCompiler().compile(right));
        var keys = new SigningKeyRing("fixture", Ed25519.generateKeyPair());
        var service = new RuleCompilerService(new InMemoryArtifactStore(), new ObjectMapper(),
                Clock.fixed(Instant.parse("2026-09-08T00:00:00Z"), ZoneOffset.UTC), keys);
        var a = service.compile("tenant-a", request(RuleCompilerService.Format.REFERRAL_PLAN, 1, left));
        var b = service.compile("tenant-a", request(RuleCompilerService.Format.REFERRAL_PLAN, 1, right));
        assertTrue(a.valid()); assertTrue(b.valid());
        var artifactA = service.artifact("tenant-a", a.artifactId());
        var artifactB = service.artifact("tenant-a", b.artifactId());
        assertNotEquals(artifactA.sourceDigest(), artifactB.sourceDigest());
        assertEquals(new CanonicalGraphHasher().semanticHash(left), artifactA.sourceDigest());
        assertTrue(ArtifactAttestation.verify(keys.publicKey("fixture"), "tenant-a", artifactA.reference("fixture://a")));
        assertTrue(ArtifactAttestation.verify(keys.publicKey("fixture"), "tenant-a", artifactB.reference("fixture://b")));
    }
    private static GraphDefinition referenceGraph(String ruleId, String skuVersion) {
        var g = graph(); var nodes = new ArrayList<>(g.nodes()); var node = nodes.get(3);
        var config = new HashMap<>(node.config()); config.put("ruleId", ruleId); config.put("skuVersion", skuVersion);
        nodes.set(3, new GraphNode(node.id(), node.stableTypeId(), node.semanticVersion(), config));
        return copy(g, nodes, g.edges());
    }
    @Test void rejectsDuplicateJsonRolesBeforeMapConversion() {
        var builder = tools.jackson.databind.json.JsonMapper.builder();
        new com.acme.marketing.compiler.infrastructure.CompilerConfiguration().compilerStrictJson().customize(builder);
        var mapper = builder.build();
        assertThrows(tools.jackson.core.JacksonException.class,
                () -> mapper.readValue("{\"role\":\"INVITER\",\"role\":\"INVITEE\"}", Map.class));
        assertEquals("INVITER", mapper.readValue("{\"role\":\"INVITER\"}", Map.class).get("role"));
    }
    @Test void movingParserToSpiPreservesPreviouslyCompiledPayloadBytes() {
        // 来自前阶段已通过隔离快照的旧application parser实际输出，非本实现重新计算的期望值。
        byte[] payload = new ObjectMapper().writeValueAsBytes(new ReferralPlanCompiler().compile(graph()));
        assertEquals("sha256:59efbe76ce2cfe47622da7067532f49695aaf53aa4e371c1ed01c2bccf5e6972",
                "sha256:" + com.acme.marketing.platform.crypto.Digests.sha256Hex(payload));
    }
    @Test void lowersTypedPolicyAndPreservesBindingScope() {
        var result = new ReferralPlanCompiler().compile(graph());
        assertEquals("definition-a", result.definitionId());
        assertEquals("org-fixture", result.scope().organizationId());
        assertEquals("shop-fixture", result.scope().shopId());
        assertEquals(100, result.binding().maxBindAgeSeconds());
        assertEquals(ReferralPlanCompiler.Attribution.FIRST_VALID_BIND, result.binding().attribution());
        assertEquals(2, result.policy().rewards().size());
        assertEquals(100, result.policy().minNetAmountMinor());
        assertEquals(1, result.policy().rewards().getFirst().quantity());
    }
    @Test void graphArrayOrderDoesNotChangePlanOrCanonicalSourceHash() {
        var g = graph();
        var nodes = new ArrayList<>(g.nodes()); Collections.reverse(nodes);
        var edges = new ArrayList<>(g.edges()); Collections.reverse(edges);
        var reordered = copy(g, nodes, edges);
        assertEquals(new ReferralPlanCompiler().compile(g), new ReferralPlanCompiler().compile(reordered));
        assertEquals(new CanonicalGraphHasher().semanticHash(g), new CanonicalGraphHasher().semanticHash(reordered));
    }
    @Test void rejectsMissingUnknownOrScriptFields() {
        for (var type : List.of("referral.start", "referral.bind", "referral.qualify", "referral.reward", "referral.end")) {
            assertInvalid(config(type, "script", "System.exit(0)"));
        }
        assertInvalid(config("referral.reward", "role", "INVITER,INVITEE"));
        assertInvalid(config("referral.reward", "role", "ADMIN"));
        assertInvalid(config("referral.reward", "skuVersion", ""));
        assertInvalid(config("referral.reward", "benefitDefinitionVersion", null));
        assertInvalid(config("referral.qualify", "lateArrivalGraceSeconds", null));
        assertInvalid(config("referral.bind", "attribution", "LAST_CLICK"));
        assertInvalid(config("referral.bind", "inviteeScope", "ANYONE"));
    }
    @Test void rejectsMalformedNumbersDatesAndSharedPolicyViolations() {
        for (var quantity : List.of("2", "1.0", "+1", "01", "-1", "2147483648", "9223372036854775808"))
            assertInvalid(config("referral.reward", "quantity", quantity));
        assertInvalid(config("referral.qualify", "qualificationWindowSeconds", "0"));
        assertInvalid(config("referral.start", "startsAt", "tomorrow"));
        assertInvalid(config("referral.start", "endsAt", "2025-01-01T00:00:00Z"));
        assertInvalid(config("referral.reward", "campaignLimit", "0"));
        assertInvalid(config("referral.reward", "ruleId", "same-id"));
        assertInvalid(config("referral.bind", "maxBindAgeSeconds", "9223372036854775807"));
    }
    @Test void rejectsCyclesBranchesDisconnectedAndWrongNodeOrder() {
        var g = graph();
        var cycle = new ArrayList<>(g.edges()); cycle.add(new GraphEdge("cycle", "r2", "next", "b", "in"));
        assertInvalid(copy(g, g.nodes(), cycle));
        var branch = new ArrayList<>(g.edges()); branch.add(new GraphEdge("branch", "b", "next", "r2", "in"));
        assertInvalid(copy(g, g.nodes(), branch));
        assertInvalid(copy(g, g.nodes(), g.edges().subList(1, g.edges().size())));
        var nodes = new ArrayList<>(g.nodes());
        nodes.set(1, new GraphNode("b", "referral.qualify", "1.0.0", g.nodes().get(2).config()));
        nodes.set(2, new GraphNode("q", "referral.bind", "1.0.0", g.nodes().get(1).config()));
        assertInvalid(copy(g, nodes, g.edges()));
    }
    @Test void rejectsWrongDialectVersionNodesPortsAndVariables() {
        var g = graph();
        assertInvalid(new GraphDefinition(g.definitionId(), Dialect.OFFER_DECISION_DAG, "1.0.0", g.nodes(), g.edges(), Map.of(), Map.of()));
        assertInvalid(new GraphDefinition(g.definitionId(), g.dialect(), "2.0.0", g.nodes(), g.edges(), Map.of(), Map.of()));
        assertInvalid(new GraphDefinition(g.definitionId(), g.dialect(), "1.0.0", g.nodes(), g.edges(), Map.of("dynamic", "1"), Map.of()));
        var nodes = new ArrayList<>(g.nodes());
        nodes.set(3, new GraphNode("r1", "journey.webhook", "1.0.0", Map.of()));
        assertInvalid(copy(g, nodes, g.edges()));
        nodes.set(3, new GraphNode("r1", "referral.reward", "2.0.0", g.nodes().get(3).config()));
        assertInvalid(copy(g, nodes, g.edges()));
        var edges = new ArrayList<>(g.edges()); edges.set(0, new GraphEdge("bad", "s", "http", "b", "in"));
        assertInvalid(copy(g, g.nodes(), edges));
    }
    @Test void signsVersionScopedArtifactAndRejectsBypassWithoutSaving() {
        var keys = new SigningKeyRing("fixture", Ed25519.generateKeyPair());
        var store = org.mockito.Mockito.spy(new InMemoryArtifactStore());
        var service = new RuleCompilerService(store, new ObjectMapper(),
                Clock.fixed(Instant.parse("2026-09-08T00:00:00Z"), ZoneOffset.UTC), keys);
        var request = request(RuleCompilerService.Format.REFERRAL_PLAN, 1, graph());
        var first = service.compile("tenant-a", request);
        assertTrue(first.valid());
        assertEquals("marketing-referral-plan/1", first.abi());
        var artifact = service.artifact("tenant-a", first.artifactId());
        assertTrue(ArtifactAttestation.verify(keys.publicKey("fixture"), "tenant-a", artifact.reference("fixture://artifact")));
        assertEquals(new CanonicalGraphHasher().semanticHash(graph()), artifact.sourceDigest());
        assertEquals(new ReferralPlanCompiler().compile(graph()), new ObjectMapper().readValue(
                artifact.payload(), ReferralPlanCompiler.CompiledReferralPlan.class));
        assertEquals(first.artifactId(), service.compile("tenant-a", request).artifactId());
        assertNotEquals(first.artifactId(), service.compile("tenant-a", request(RuleCompilerService.Format.REFERRAL_PLAN, 2, graph())).artifactId());
        assertFalse(service.compile("tenant-a", request(RuleCompilerService.Format.GRAPH, 1, graph())).valid());
        assertFalse(service.compile("tenant-a", request(RuleCompilerService.Format.REFERRAL_PLAN, 1, config("referral.reward", "quantity", "2"))).valid());
        assertFalse(service.compile("tenant-a", new RuleCompilerService.CompileRequest("other-definition", 1,
                RuleCompilerService.Format.REFERRAL_PLAN, null, null, null, graph())).valid());
        org.mockito.Mockito.verify(store, org.mockito.Mockito.times(3)).putIfAbsent(org.mockito.ArgumentMatchers.any());
    }
    private static RuleCompilerService.CompileRequest request(RuleCompilerService.Format format, long version, GraphDefinition graph) {
        return new RuleCompilerService.CompileRequest("definition-a", version, format, null, null, null, graph);
    }
    static GraphDefinition graph() {
        var nodes = List.of(
                new GraphNode("s", "referral.start", "1.0.0", Map.of("startsAt", "2026-09-01T00:00:00Z", "endsAt", "2026-09-10T00:00:00Z", "settlementEndsAt", "2026-09-20T00:00:00Z", "organizationId", "org-fixture", "shopId", "shop-fixture")),
                new GraphNode("b", "referral.bind", "1.0.0", Map.of("attribution", "FIRST_VALID_BIND", "maxBindAgeSeconds", "100", "inviteeScope", "NEW_CUSTOMER")),
                new GraphNode("q", "referral.qualify", "1.0.0", Map.of("goalType", "FIRST_ORDER_SETTLED", "qualificationWindowSeconds", "100", "minNetAmountMinor", "100", "currency", "CNY", "observationSeconds", "10", "lateArrivalGraceSeconds", "20")),
                reward("r1", "inviter", "INVITER"), reward("r2", "invitee", "INVITEE"),
                new GraphNode("e", "referral.end", "1.0.0", Map.of()));
        var edges = new ArrayList<GraphEdge>();
        for (int i = 0; i < nodes.size() - 1; i++) edges.add(new GraphEdge("edge" + i, nodes.get(i).id(), "next", nodes.get(i + 1).id(), "in"));
        return new GraphDefinition("definition-a", Dialect.REFERRAL_POLICY, "1.0.0", nodes, edges, Map.of(), Map.of());
    }
    private static GraphNode reward(String node, String id, String role) {
        return new GraphNode(node, "referral.reward", "1.0.0", Map.of("ruleId", id, "role", role, "mode", "PER_RELATION", "threshold", "1", "benefitDefinitionVersion", "benefit-v1", "skuVersion", "sku-v1", "quantity", "1", "perSubjectLimit", "10", "campaignLimit", "100"));
    }
    private static GraphDefinition config(String type, String key, String value) {
        var g = graph(); var nodes = new ArrayList<GraphNode>();
        for (var node : g.nodes()) {
            var config = new HashMap<>(node.config());
            if (node.stableTypeId().equals(type)) { if (value == null) config.remove(key); else config.put(key, value); }
            nodes.add(new GraphNode(node.id(), node.stableTypeId(), node.semanticVersion(), config));
        }
        return copy(g, nodes, g.edges());
    }
    private static GraphDefinition copy(GraphDefinition g, List<GraphNode> nodes, List<GraphEdge> edges) {
        return new GraphDefinition(g.definitionId(), g.dialect(), g.dialectVersion(), nodes, edges, g.variables(), g.annotations());
    }
    private static void assertInvalid(GraphDefinition graph) {
        assertThrows(IllegalArgumentException.class, () -> new ReferralPlanCompiler().compile(graph));
    }
}
