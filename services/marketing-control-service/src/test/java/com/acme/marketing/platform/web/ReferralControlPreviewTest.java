package com.acme.marketing.platform.web;

import com.acme.marketing.control.application.*;
import com.acme.marketing.control.domain.ApprovalCase;
import com.acme.marketing.control.interfaces.ControlController;
import com.acme.marketing.contracts.release.*;
import com.acme.marketing.lowcode.model.*;
import com.acme.marketing.platform.crypto.*;
import com.acme.marketing.platform.error.ConflictException;
import com.acme.marketing.platform.identity.*;
import com.acme.marketing.referral.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 仅内存mock和纯函数，验证控制面预览不会变成真实权威事实、审批或奖励写入。 */
class ReferralControlPreviewTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final ControlRepository repository = mock(ControlRepository.class);
    private final ControlApplicationService service = new ControlApplicationService(repository, mapper,
            Clock.fixed(Instant.parse("2026-09-01T00:00:30Z"), ZoneOffset.UTC), new DefaultNodeRegistry(), new GraphSimulationService(), null);
    @BeforeEach void bindScope() {
        TenantContextHolder.set(new TenantScope(new TenantId("tenant-a"), Set.of("*"), Set.of("*"), "operator", Set.of("*")));
        when(repository.findCampaignOwnership("tenant-a", "campaign", false)).thenReturn(Optional.of(new ControlRepository.CampaignOwnershipRow("org", "shop")));
    }
    @AfterEach void clearScope() { TenantContextHolder.clear(); }

    @Test void validatesThroughSharedParserButCannotSubmitOrApprove() {
        store(graph(), "DRAFT");
        when(repository.updateDefinitionStatus(anyString(), anyString(), anyLong(), anyString(), anyString())).thenReturn(1);
        var validation = service.validate("fixture", 1);
        assertTrue(validation.valid());
        assertEquals("REFERRAL_CATALOG_UNVERIFIED", validation.issues().getFirst().code());
        verify(repository).updateDefinitionStatus(eq("tenant-a"), eq("fixture"), eq(1L), eq("VALIDATED"), anyString());
        assertEquals("REFERRAL_GOVERNANCE_NOT_AVAILABLE", assertThrows(ConflictException.class, () -> service.submit("fixture", 1)).code());
        when(repository.findApprovalCase("tenant-a", "case", true)).thenReturn(Optional.of(new ControlRepository.ApprovalCaseRow(
                "case", "fixture", 1, "other", "BUSINESS", "OPEN", "2026-09-01T00:00:00Z")));
        assertEquals("REFERRAL_GOVERNANCE_NOT_AVAILABLE", assertThrows(ConflictException.class, () -> service.decide("case", ApprovalCase.Role.BUSINESS,
                ControlApplicationService.ApprovalDecision.APPROVE, "fixture")).code());
        verify(repository, never()).saveApprovalCase(any());
        verify(repository, never()).saveApprovalDecision(any());
    }
    @Test void invalidFullPolicyReturnsExplanationAndDoesNotChangeState() {
        store(changed("referral.reward", "quantity", "2"), "DRAFT");
        var result = service.validate("fixture", 1);
        assertFalse(result.valid());
        assertEquals("REFERRAL_POLICY_INVALID", result.issues().getFirst().code());
        assertTrue(result.issues().getFirst().message().contains("数量固定 1"));
        verify(repository, never()).updateDefinitionStatus(anyString(), anyString(), anyLong(), anyString(), anyString());
    }
    @Test void previewsBilateralAndMilestonesWithExplicitSimulationMarkerAndNoWrites() {
        store(graph(), "DRAFT");
        var result = (ReferralSimulationService.ReferralSimulation) service.simulate("fixture", 1, facts());
        assertTrue(result.simulationOnly());
        assertEquals(ReferralSimulationService.EvidenceAuthority.SIMULATED_INPUT, result.evidenceAuthority());
        assertEquals(ReferralPolicyEvaluator.State.ELIGIBLE, result.qualification().state());
        assertEquals(List.of("inviter", "invitee", "three", "five"), result.rewardCandidates().stream().map(c -> c.rule().ruleId()).toList());
        assertEquals(List.of("relation", "relation", "3", "5"), result.rewardCandidates().stream().map(ReferralPolicyEvaluator.RewardCandidate::milestoneKey).toList());
        assertFalse(mapper.valueToTree(result).has("payableMinor"));
        verify(repository).findDefinition("tenant-a", "fixture", 1);
        verify(repository).findCampaignOwnership("tenant-a", "campaign", false);
        verifyNoMoreInteractions(repository);
    }
    @Test void observationUnknownAndRefundUseTheSameEvaluator() {
        store(graph(), "DRAFT");
        var input = new HashMap<>(facts()); input.put("now", "2026-09-01T00:00:29Z"); input.put("validCount", "0");
        var waiting = preview(input);
        assertEquals(ReferralPolicyEvaluator.Reason.OBSERVATION_PENDING, waiting.qualification().reason());
        assertEquals(Instant.parse("2026-09-01T00:00:30Z"), waiting.qualification().dueAt());
        assertTrue(waiting.rewardCandidates().isEmpty());
        input.put("verified", "false"); input.put("qualifyingFactAt", ""); input.put("qualifyingFactReceivedAt", "");
        assertEquals(ReferralPolicyEvaluator.Reason.EVIDENCE_UNAVAILABLE, preview(input).qualification().reason());
        input = new HashMap<>(facts()); input.put("cumulativeRefundMinor", "1"); input.put("validCount", "0");
        assertEquals(ReferralPolicyEvaluator.Reason.NET_BELOW_THRESHOLD, preview(input).qualification().reason());
        assertTrue(preview(input).rewardCandidates().isEmpty());
    }
    @Test void registrationBeforeBindingKeepsOriginalObservationTime() {
        store(changed("referral.qualify", "goalType", "REGISTERED_NEW_CUSTOMER"), "DRAFT");
        var input = new HashMap<>(facts()); input.put("qualifyingFactAt", "2026-09-01T00:00:00Z");
        input.put("qualifyingFactReceivedAt", "2026-09-01T00:00:01Z"); input.put("now", "2026-09-01T00:00:10Z");
        input.put("firstValidOrder", "UNKNOWN");
        assertEquals(ReferralPolicyEvaluator.State.ELIGIBLE, preview(input).qualification().state());
        input.put("newCustomerAtBind", "UNKNOWN"); input.put("validCount", "0");
        assertEquals(ReferralPolicyEvaluator.State.PENDING, preview(input).qualification().state());
    }
    @Test void invalidAndRawCoercedFactsCannotReachEvaluation() {
        store(graph(), "DRAFT");
        var missing = new HashMap<>(facts()); missing.remove("now");
        assertThrows(IllegalArgumentException.class, () -> preview(missing));
        var unknown = new HashMap<>(facts()); unknown.put("authority", "REAL");
        assertThrows(IllegalArgumentException.class, () -> preview(unknown));
        var bad = new HashMap<>(facts()); bad.put("verified", "allow");
        assertThrows(IllegalArgumentException.class, () -> preview(bad));
        for (Object value : Arrays.asList(1, true, null, List.of(), Map.of())) {
            var raw = new HashMap<String, Object>(facts()); raw.put("validCount", value);
            assertThrows(IllegalArgumentException.class, () -> service.simulate("fixture", 1, raw));
        }
    }
    @Test void graphSaveChecksOriginalJsonBeforeCommandAndDuplicateFieldsFail() {
        var commands = mock(ControlCommandExecutor.class);
        var controller = new ControlController(service, new DefaultNodeRegistry(), commands, mapper);
        String json = mapper.writeValueAsString(graph());
        for (String replacement : List.of("1", "true", "null", "{}", "[]")) {
            String malformed = json.replace("\"quantity\":\"1\"", "\"quantity\":" + replacement);
            assertThrows(IllegalArgumentException.class, () -> controller.saveDefinition("fixture-key",
                    new ControlController.SaveDefinitionInput("campaign", mapper.readTree(malformed))));
        }
        verifyNoInteractions(commands);
        var builder = tools.jackson.databind.json.JsonMapper.builder();
        new com.acme.marketing.control.infrastructure.ControlJsonConfiguration().controlStrictJson().customize(builder);
        assertThrows(tools.jackson.core.JacksonException.class,
                () -> builder.build().readTree("{\"role\":\"INVITER\",\"role\":\"INVITEE\"}"));
    }
    @Test void oldPricePreviewKeepsExactWireFieldsAndValue() {
        var old = new GraphDefinition("old", Dialect.OFFER_DECISION_DAG, "1.0.0", List.of(
                new GraphNode("e", "offer.end", "1.0.0", Map.of())), List.of(), Map.of(), Map.of());
        var result = new GraphSimulationService().preview(old, Map.of("amountMinor", "100"));
        assertEquals(Set.of("subtotalMinor", "discountMinor", "payableMinor", "trace"), mapper.valueToTree(result).properties()
                .stream().map(Map.Entry::getKey).collect(java.util.stream.Collectors.toSet()));
        assertEquals(100, ((GraphSimulationService.Simulation) result).payableMinor());
    }
    @Test void historicalReferralManifestCannotAcknowledgeActivateOrRollback() {
        var now = Instant.parse("2026-09-01T00:00:00Z");
        var artifact = new ArtifactReference("artifact", "REFERRAL_PLAN", "fixture://artifact", "sha256:" + "a".repeat(64),
                "sha256:" + "b".repeat(64), "key", "signature", "marketing-referral-plan/1", "fixture", 1);
        var manifest = new ReleaseManifest("manifest", new TenantId("tenant-a"), "test", "cell", "referral", "fixture", 1, 0,
                List.of(), List.of(), List.of(artifact), Map.of(), 0, now, now.plusSeconds(1000), "operator", List.of(), now, "fixture-signature");
        when(repository.findManifest("tenant-a", "manifest", true)).thenReturn(Optional.of(new ControlRepository.ManifestRow(mapper.writeValueAsString(manifest), "STAGED")));
        when(repository.findDefinitionScopes("tenant-a", "fixture", 1, false)).thenReturn(List.of(new ControlRepository.ResourceScopeRow("org", "shop")));
        var releases = new ReleaseApplicationService(repository, mapper, Clock.fixed(now, ZoneOffset.UTC),
                new SigningKeyRing("fixture", Ed25519.generateKeyPair()), null, null, null, 1, 1, 100, "", "activation", "switch");
        assertEquals("REFERRAL_RELEASE_NOT_AVAILABLE", assertThrows(ConflictException.class, () -> releases.acknowledge("manifest", null)).code());
        assertEquals("REFERRAL_RELEASE_NOT_AVAILABLE", assertThrows(ConflictException.class, () -> releases.activate("manifest")).code());
        assertEquals("REFERRAL_RELEASE_NOT_AVAILABLE", assertThrows(ConflictException.class, () -> releases.rollback("manifest", 1)).code());
        verify(repository, never()).saveRuntimeAck(any());
    }
    private ReferralSimulationService.ReferralSimulation preview(Map<String, String> facts) {
        return (ReferralSimulationService.ReferralSimulation) service.simulate("fixture", 1, facts);
    }
    private void store(GraphDefinition graph, String status) {
        when(repository.findDefinition("tenant-a", "fixture", 1)).thenReturn(Optional.of(new ControlRepository.DefinitionRow(
                "campaign", "fixture", 1, "REFERRAL_POLICY", mapper.writeValueAsString(graph), "hash", status,
                "operator", "2026-09-01T00:00:00Z", "2026-09-01T00:00:00Z")));
    }
    private static Map<String, String> facts() {
        return Map.ofEntries(Map.entry("boundAt", "2026-09-01T00:00:10Z"), Map.entry("now", "2026-09-01T00:00:30Z"),
                Map.entry("relationId", "relation"), Map.entry("validCount", "5"), Map.entry("verified", "true"),
                Map.entry("newCustomerAtBind", "YES"), Map.entry("firstValidOrder", "YES"),
                Map.entry("qualifyingFactAt", "2026-09-01T00:00:20Z"), Map.entry("qualifyingFactReceivedAt", "2026-09-01T00:00:20Z"),
                Map.entry("settledAmountMinor", "100"), Map.entry("cumulativeRefundMinor", "0"), Map.entry("currency", "CNY"));
    }
    private static GraphDefinition changed(String type, String key, String value) {
        var graph = graph(); var nodes = new ArrayList<GraphNode>();
        for (var node : graph.nodes()) {
            var config = new HashMap<>(node.config()); if (node.stableTypeId().equals(type)) config.put(key, value);
            nodes.add(new GraphNode(node.id(), node.stableTypeId(), node.semanticVersion(), config));
        }
        return new GraphDefinition(graph.definitionId(), graph.dialect(), graph.dialectVersion(), nodes, graph.edges(), Map.of(), Map.of());
    }
    private static GraphDefinition graph() {
        var nodes = List.of(new GraphNode("s", "referral.start", "1.0.0", Map.of("startsAt", "2026-09-01T00:00:00Z", "endsAt", "2026-09-10T00:00:00Z", "settlementEndsAt", "2026-09-20T00:00:00Z", "organizationId", "org", "shopId", "shop")),
                new GraphNode("b", "referral.bind", "1.0.0", Map.of("attribution", "FIRST_VALID_BIND", "maxBindAgeSeconds", "100", "inviteeScope", "NEW_CUSTOMER")),
                new GraphNode("q", "referral.qualify", "1.0.0", Map.of("goalType", "FIRST_ORDER_SETTLED", "qualificationWindowSeconds", "100", "minNetAmountMinor", "100", "currency", "CNY", "observationSeconds", "10", "lateArrivalGraceSeconds", "20")),
                reward("r1", "inviter", "INVITER", "PER_RELATION", "1"), reward("r2", "invitee", "INVITEE", "PER_RELATION", "1"),
                reward("r3", "three", "INVITER", "MILESTONE", "3"), reward("r4", "five", "INVITER", "MILESTONE", "5"),
                new GraphNode("e", "referral.end", "1.0.0", Map.of()));
        var edges = new ArrayList<GraphEdge>(); for (int i = 0; i < nodes.size() - 1; i++) edges.add(new GraphEdge("edge" + i, nodes.get(i).id(), "next", nodes.get(i + 1).id(), "in"));
        return new GraphDefinition("fixture", Dialect.REFERRAL_POLICY, "1.0.0", nodes, edges, Map.of(), Map.of());
    }
    private static GraphNode reward(String node, String id, String role, String mode, String threshold) {
        return new GraphNode(node, "referral.reward", "1.0.0", Map.of("ruleId", id, "role", role, "mode", mode, "threshold", threshold,
                "benefitDefinitionVersion", "benefit-v1", "skuVersion", "sku-v1", "quantity", "1", "perSubjectLimit", "10", "campaignLimit", "100"));
    }
}
