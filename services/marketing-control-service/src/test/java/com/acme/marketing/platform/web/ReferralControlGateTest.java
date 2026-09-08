package com.acme.marketing.platform.web;

import com.acme.marketing.control.application.*;
import com.acme.marketing.lowcode.model.*;
import com.acme.marketing.platform.crypto.*;
import com.acme.marketing.platform.error.ConflictException;
import com.acme.marketing.platform.identity.*;
import java.time.Clock;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 不启动 Spring 或数据库，验证注册新方言不能绕过未交付的仿真和发布门禁。 */
class ReferralControlGateTest {
    @Test void registryAppendsFivePureNodesAndSimulationFailsClosed() {
        assertEquals(5, new DefaultNodeRegistry().all().stream().filter(n -> n.dialects().contains(Dialect.REFERRAL_POLICY)).count());
        var graph = new GraphDefinition("fixture", Dialect.REFERRAL_POLICY, "1.0.0", List.of(), List.of(), Map.of(), Map.of());
        assertEquals("REFERRAL_SIMULATION_NOT_AVAILABLE", assertThrows(ConflictException.class,
                () -> new GraphSimulationService().simulate(graph, Map.of())).code());
        var old = new GraphDefinition("old", Dialect.OFFER_DECISION_DAG, "1.0.0",
                List.of(new GraphNode("e", "offer.end", "1.0.0", Map.of())), List.of(), Map.of(), Map.of());
        assertEquals(100, new GraphSimulationService().simulate(old, Map.of("amountMinor", "100")).payableMinor());
    }
    @Test void invalidReferralCannotValidateAndApprovalStaysClosed() {
        var repository = mock(ControlRepository.class);
        var mapper = new ObjectMapper();
        var graph = new GraphDefinition("fixture", Dialect.REFERRAL_POLICY, "1.0.0", List.of(), List.of(), Map.of(), Map.of());
        when(repository.findDefinition("tenant-a", "fixture", 1)).thenReturn(Optional.of(
                new ControlRepository.DefinitionRow("campaign", "fixture", 1, "REFERRAL_POLICY", mapper.writeValueAsString(graph),
                        "hash", "VALIDATED", "fixture", "2026-09-01T00:00:00Z", "2026-09-01T00:00:00Z")));
        when(repository.findCampaignOwnership("tenant-a", "campaign", false)).thenReturn(Optional.of(
                new ControlRepository.CampaignOwnershipRow("org", "shop")));
        var service = new ControlApplicationService(repository, mapper, Clock.systemUTC(), new DefaultNodeRegistry(),
                new GraphSimulationService(), null);
        TenantContextHolder.set(new TenantScope(new TenantId("tenant-a"), Set.of("*"), Set.of("*"), "fixture", Set.of("*")));
        try {
            assertFalse(service.validate("fixture", 1).valid());
            assertEquals("REFERRAL_POLICY_INVALID", service.validate("fixture", 1).issues().getFirst().code());
            assertEquals("REFERRAL_GOVERNANCE_NOT_AVAILABLE", assertThrows(ConflictException.class,
                    () -> service.submit("fixture", 1)).code());
            verify(repository, never()).saveApprovalCase(any());
            verify(repository, never()).updateDefinitionStatus(anyString(), anyString(), anyLong(), anyString(), anyString());
        } finally { TenantContextHolder.clear(); }
    }
    @Test void referralRuntimeAndDefinitionCannotBeStaged() {
        var repository = mock(ControlRepository.class);
        var service = new ReleaseApplicationService(repository, new ObjectMapper(), Clock.systemUTC(),
                new SigningKeyRing("fixture", Ed25519.generateKeyPair()), null, null, null,
                1, 1, 100, "", "activation-fixture", "switch-fixture");
        TenantContextHolder.set(new TenantScope(new TenantId("tenant-a"), Set.of("*"), Set.of("*"), "fixture", Set.of("release:write")));
        try {
            var request = new ReleaseApplicationService.StageReleaseRequest("fixture", 1, "test", "cell", "referral",
                    "fixture", List.of(), Map.of(), 0, null, List.of());
            assertEquals("REFERRAL_RELEASE_NOT_AVAILABLE", assertThrows(ConflictException.class, () -> service.stage(request)).code());
            when(repository.findDefinition("tenant-a", "fixture", 1)).thenReturn(Optional.of(
                    new ControlRepository.DefinitionRow("campaign", "fixture", 1, "REFERRAL_POLICY", "{}", "hash", "APPROVED", "fixture", "time", "time")));
            var disguised = new ReleaseApplicationService.StageReleaseRequest("fixture", 1, "test", "cell", "decision",
                    "fixture", List.of(), Map.of(), 0, null, List.of());
            assertEquals("REFERRAL_RELEASE_NOT_AVAILABLE", assertThrows(ConflictException.class, () -> service.stage(disguised)).code());
            verify(repository, never()).findDefinitionScopes(anyString(), anyString(), anyLong(), anyBoolean());
        } finally { TenantContextHolder.clear(); }
    }
}
