package com.acme.marketing.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.acme.marketing.contracts.artifact.ArtifactAttestation;
import com.acme.marketing.contracts.release.RuntimeAck;
import com.acme.marketing.contracts.release.RuntimeAckSigner;
import com.acme.marketing.lowcode.compiler.CanonicalGraphHasher;
import com.acme.marketing.lowcode.model.Dialect;
import com.acme.marketing.lowcode.model.GraphDefinition;
import com.acme.marketing.lowcode.model.GraphEdge;
import com.acme.marketing.lowcode.model.GraphNode;
import com.acme.marketing.testsupport.MySqlIntegrationTest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyPair;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Base64;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import com.acme.marketing.control.application.BenefitReleaseGate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(ControlFlowIntegrationTest.ReleaseGateTestConfiguration.class)
class ControlFlowIntegrationTest extends MySqlIntegrationTest {
    private static final KeyPair COMPILER_KEYS = com.acme.marketing.platform.crypto.Ed25519.generateKeyPair();
    private static final KeyPair RUNTIME_KEYS = com.acme.marketing.platform.crypto.Ed25519.generateKeyPair();

    @DynamicPropertySource
    static void compilerTrust(DynamicPropertyRegistry properties) {
        properties.add("marketing.compiler.trusted-key-id", () -> "compiler-test");
        properties.add("marketing.compiler.public-key-base64", () -> Base64.getEncoder()
                .encodeToString(COMPILER_KEYS.getPublic().getEncoded()));
        properties.add("marketing.runtime-ack.trusted-key-id", () -> "runtime-test");
        properties.add("marketing.runtime-ack.public-key-base64", () -> Base64.getEncoder()
                .encodeToString(RUNTIME_KEYS.getPublic().getEncoded()));
    }
    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private JdbcTemplate jdbc;

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final AtomicInteger commandSequence = new AtomicInteger();

    @Test
    void authorValidateSimulateAndApproveWithFourEyes() throws Exception {
        JsonNode campaign = post("/api/v1/campaigns", "actor-author", "idem-campaign-001",
                Map.of("name", "Double 11", "objective", "GMV", "organizationId", "org-a", "shopId", "shop-a"));
        String campaignId = campaign.get("id").asString();

        GraphDefinition graph = offerGraph();
        JsonNode definition = post("/api/v1/definitions", "actor-author", null,
                Map.of("campaignId", campaignId, "graph", graph));
        assertEquals("DRAFT", definition.get("status").asString());
        JsonNode latest = get("/api/v1/definitions/latest?campaignId=" + campaignId
                + "&dialect=OFFER_DECISION_DAG", "actor-reader");
        assertEquals("offer-main", latest.get("graph").get("definitionId").asString());
        JsonNode stored = get("/api/v1/definitions/offer-main/versions/1", "actor-reader");
        assertEquals(3, stored.get("graph").get("nodes").size());

        JsonNode validation = post("/api/v1/definitions/offer-main/versions/1:validate",
                "actor-author", null, Map.of());
        assertTrue(validation.get("valid").asBoolean());
        JsonNode simulation = post("/api/v1/definitions/offer-main/versions/1:simulate",
                "actor-author", null, Map.of("amountMinor", "10000"));
        assertEquals(1_000, simulation.get("discountMinor").asLong());

        JsonNode approval = post("/api/v1/definitions/offer-main/versions/1:submit",
                "actor-author", null, Map.of());
        String caseId = approval.get("caseId").asString();
        JsonNode queue = get("/api/v1/approvals", "actor-business");
        assertTrue(queue.isArray());
        assertEquals(caseId, queue.get(0).get("caseId").asString());
        post("/api/v1/approvals/" + caseId + "/decisions", "actor-business", null,
                Map.of("role", "BUSINESS"));
        post("/api/v1/approvals/" + caseId + "/decisions", "actor-compliance", null,
                Map.of("role", "COMPLIANCE"));
        JsonNode approved = post("/api/v1/approvals/" + caseId + "/decisions", "actor-finance", null,
                Map.of("role", "FINANCE"));
        assertEquals("APPROVED", approved.get("status").asString());

        JsonNode terms = get("/api/v1/definitions/offer-main/versions/1/terms", "actor-reader");
        assertTrue(terms.get("contentHash").asString().startsWith("sha256:"));

        Map<String, Object> releaseRequest = new LinkedHashMap<>();
        releaseRequest.put("definitionId", "offer-main");
        releaseRequest.put("definitionVersion", 1);
        releaseRequest.put("environment", "prod");
        releaseRequest.put("cell", "cell-a");
        releaseRequest.put("runtime", "decision");
        releaseRequest.put("namespace", "main");
        String checksum = "sha256:" + "a".repeat(64);
        String sourceDigest = new CanonicalGraphHasher().semanticHash(graph);
        String artifactSignature = ArtifactAttestation.sign(COMPILER_KEYS.getPrivate(), "tenant-a", "graph-a1",
                "offer-main", 1, "OFFER_POLICY", "marketing-offer-policy/1", checksum, sourceDigest);
        releaseRequest.put("artifacts", List.of(Map.of(
                "artifactId", "graph-a1", "type", "OFFER_POLICY", "uri", "s3://artifacts/graph-a1",
                "checksum", checksum, "sourceDigest", sourceDigest, "signatureKeyId", "compiler-test",
                "signature", artifactSignature, "abi", "marketing-offer-policy/1",
                "definitionId", "offer-main", "definitionVersion", 1)));
        releaseRequest.put("schemaVersions", Map.of("graph", "1"));
        releaseRequest.put("canaryBasisPoints", 0);
        releaseRequest.put("activationAt", Instant.parse("2026-01-01T00:00:00Z"));
        releaseRequest.put("approvalCaseIds", List.of(caseId));
        JsonNode staged = post("/api/v1/releases", "actor-release", null, releaseRequest);
        assertEquals("STAGED", staged.get("state").asString());
        String manifestId = staged.get("manifest").get("manifestId").asString();
        RuntimeAck unsignedAck = new RuntimeAck(manifestId, 1, "decision-1", "cell-a",
                RuntimeAck.Status.READY, "sha256:runtime", Set.of("marketing-offer-policy/1"),
                Set.of("graph-a1"),
                1_000, Instant.now(), "runtime-test", "");
        RuntimeAck ack = RuntimeAckSigner.sign(RUNTIME_KEYS.getPrivate(), "tenant-a", unsignedAck);
        post("/api/v1/releases/" + manifestId + ":ack", "decision-runtime", null, ack);
        JsonNode active = post("/api/v1/releases/" + manifestId + ":activate", "actor-release", null, Map.of());
        assertEquals("ACTIVE", active.get("state").asString());
        assertEquals(1, active.get("readyReplicas").asInt());
        JsonNode manifests = get("/api/v1/releases", "actor-release");
        assertTrue(manifests.isArray());
        assertEquals(manifestId, manifests.get(0).get("manifest").get("manifestId").asString());
    }

    @Test
    void reviewerCanRejectWithCommentAndDefinitionBecomesEditable() throws Exception {
        JsonNode campaign = post("/api/v1/campaigns", "reject-author", "idem-reject-campaign",
                Map.of("name", "Reject flow", "objective", "QA", "organizationId", "org-a"));
        String campaignId = campaign.get("id").asString();
        GraphDefinition graph = offerGraph("offer-reject");
        post("/api/v1/definitions", "reject-author", "idem-reject-definition",
                Map.of("campaignId", campaignId, "graph", graph));
        post("/api/v1/definitions/offer-reject/versions/1:validate", "reject-author",
                "idem-reject-validate", Map.of());
        String caseId = post("/api/v1/definitions/offer-reject/versions/1:submit", "reject-author",
                "idem-reject-submit", Map.of()).get("caseId").asString();

        HttpResponse<String> selfDecision = client.send(request("/api/v1/approvals/" + caseId + "/decisions",
                "reject-author").header("Content-Type", "application/json")
                .header("Idempotency-Key", "idem-reject-self")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(
                        Map.of("role", "BUSINESS", "decision", "REJECT")))).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(409, selfDecision.statusCode());

        JsonNode rejected = post("/api/v1/approvals/" + caseId + "/decisions", "reject-reviewer",
                "idem-reject-reviewer", Map.of("role", "BUSINESS", "decision", "REJECT",
                        "comment", "pricing evidence missing"));
        assertEquals("REJECTED", rejected.get("status").asString());
        assertEquals("DRAFT", get("/api/v1/definitions/offer-reject/versions/1", "actor-reader")
                .get("status").asString());
        assertEquals(1, jdbc.queryForObject(
                "select count(*) from mk_audit where tenant_id=? and action_name=? and resource_ref like ?",
                Integer.class, "tenant-a", "APPROVAL_REJECTED", "%pricing evidence missing%"));
    }

    @Test
    void concurrentCommandsProduceOneContinuousTenantAuditChain() throws Exception {
        int commandCount = 16;
        List<Future<JsonNode>> results = new ArrayList<>();
        try (var executor = Executors.newFixedThreadPool(8)) {
            for (int index = 0; index < commandCount; index++) {
                int command = index;
                results.add(executor.submit(() -> post("/api/v1/campaigns", "concurrent-actor-" + command,
                        "audit-concurrent-" + command,
                        Map.of("name", "Concurrent campaign " + command, "objective", "GMV",
                                "organizationId", "org-a", "shopId", "shop-a"))));
            }
            for (Future<JsonNode> result : results) {
                result.get();
            }
        }

        List<AuditLink> links = jdbc.query(
                "select chain_index,previous_hash,entry_hash from mk_audit where tenant_id=? order by chain_index",
                (rs, rowNum) -> new AuditLink(rs.getLong(1), rs.getString(2), rs.getString(3)), "tenant-a");
        assertTrue(links.size() >= commandCount);
        String previousHash = "GENESIS";
        long expectedIndex = 1;
        for (AuditLink link : links) {
            assertEquals(expectedIndex, link.index());
            assertEquals(previousHash, link.previousHash());
            previousHash = link.entryHash();
            expectedIndex++;
        }
        Map<String, Object> head = jdbc.queryForMap(
                "select last_chain_index,last_entry_hash from mk_audit_head where tenant_id=?", "tenant-a");
        assertEquals((long) links.size(), ((Number) head.get("last_chain_index")).longValue());
        assertEquals(previousHash, head.get("last_entry_hash"));
    }

    private record AuditLink(long index, String previousHash, String entryHash) { }

    private GraphDefinition offerGraph() {
        return offerGraph("offer-main");
    }

    private GraphDefinition offerGraph(String definitionId) {
        return new GraphDefinition(definitionId, Dialect.OFFER_DECISION_DAG, "1.0.0",
                List.of(
                        new GraphNode("start", "offer.start", "1.0.0", Map.of()),
                        new GraphNode("discount", "offer.fixed", "1.0.0", Map.of(
                                "amountMinor", "1000", "benefitDefinitionVersion", "coupon-v1@1")),
                        new GraphNode("end", "offer.end", "1.0.0", Map.of())),
                List.of(
                        new GraphEdge("e1", "start", "next", "discount", "in"),
                        new GraphEdge("e2", "discount", "next", "end", "in")),
                Map.of(), Map.of("terms", "Spend 100 get 10 off"));
    }

    @TestConfiguration
    static class ReleaseGateTestConfiguration {
        @Bean
        @Primary
        BenefitReleaseGate benefitReleaseGate() {
            return (scope, references) -> { };
        }
    }

    private JsonNode post(String path, String actor, String idempotencyKey, Object body) throws Exception {
        HttpRequest.Builder builder = request(path, actor)
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .header("Content-Type", "application/json");
        builder.header("Idempotency-Key", idempotencyKey == null
                ? "test-command-" + commandSequence.incrementAndGet() : idempotencyKey);
        return exchange(builder.build());
    }

    private JsonNode get(String path, String actor) throws Exception {
        return exchange(request(path, actor).GET().build());
    }

    private HttpRequest.Builder request(String path, String actor) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(30))
                .header("X-Dev-Tenant-Id", "tenant-a")
                .header("X-Dev-Actor-Id", actor)
                .header("X-Dev-Organization-Ids", "org-a")
                .header("X-Dev-Shop-Ids", "shop-a")
                .header("X-Dev-Permissions", "*");
    }

    private JsonNode exchange(HttpRequest request) throws Exception {
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertTrue(response.statusCode() >= 200 && response.statusCode() < 300,
                () -> response.statusCode() + ": " + response.body());
        return mapper.readTree(response.body());
    }
}
