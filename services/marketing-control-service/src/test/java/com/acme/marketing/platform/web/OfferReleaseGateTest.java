package com.acme.marketing.platform.web;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.acme.marketing.contracts.release.ArtifactReference;
import com.acme.marketing.contracts.release.ReleaseManifest;
import com.acme.marketing.control.application.ControlRepository;
import com.acme.marketing.control.application.ReleaseApplicationService;
import com.acme.marketing.decision.runtime.OfferPolicyGraphCompiler;
import com.acme.marketing.lowcode.model.Dialect;
import com.acme.marketing.lowcode.model.GraphDefinition;
import com.acme.marketing.lowcode.model.GraphNode;
import com.acme.marketing.platform.crypto.Ed25519;
import com.acme.marketing.platform.crypto.SigningKeyRing;
import com.acme.marketing.platform.error.ConflictException;
import com.acme.marketing.platform.identity.TenantId;
import com.acme.marketing.platform.identity.TenantScope;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class OfferReleaseGateTest {
    @Test
    void historicalApprovedAndStagedSourcesCannotBypassCurrentSemantics() {
        var mapper = new ObjectMapper();
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        for (String type : List.of("offer.condition", "offer.percentage")) {
            var graph = new GraphDefinition("offer", Dialect.OFFER_DECISION_DAG, "1.0.0",
                    List.of(new GraphNode("n", type, "1.0.0", Map.of("basisPoints", "1000", "benefitDefinitionVersion", "benefit@1"))),
                    List.of(), Map.of(), Map.of());
            var artifact = new ArtifactReference("offer_policy-old", "OFFER_POLICY", "memory:old", "sha256:" + "a".repeat(64),
                    "sha256:" + "b".repeat(64), "key", "signature", "marketing-offer-policy/1", "offer", 1);
            var repository = mock(ControlRepository.class);
            when(repository.findDefinitionScopes(eq("t"), eq("offer"), eq(1L), anyBoolean()))
                    .thenReturn(List.of(new ControlRepository.ResourceScopeRow("org", "shop")));
            when(repository.countApprovedCase("t", "approval", "offer", 1)).thenReturn(1);
            when(repository.findApprovedGraphJson("t", "offer", 1)).thenReturn(Optional.of(mapper.writeValueAsString(graph)));
            var manifest = new ReleaseManifest("manifest", new TenantId("t"), "test", "cell", "decision", "main", 1, 0,
                    List.of(), List.of(), List.of(artifact), Map.of(), 0, now, now.plusSeconds(3600), "actor", List.of("approval"), now, "sig");
            when(repository.findManifest("t", "manifest", true))
                    .thenReturn(Optional.of(new ControlRepository.ManifestRow(mapper.writeValueAsString(manifest), "STAGED")));
            var service = new ReleaseApplicationService(repository, mapper, Clock.fixed(now, java.time.ZoneOffset.UTC),
                    new SigningKeyRing("test", Ed25519.generateKeyPair()), null, null, null, 1, 1, 100, "", "activation", "kill");
            var request = new ReleaseApplicationService.StageReleaseRequest("offer", 1, "test", "cell", "decision", "main",
                    List.of(artifact), Map.of(), 0, now, List.of("approval"));
            TenantContextHolder.set(new TenantScope(new TenantId("t"), Set.of("*"), Set.of("*"), "actor", Set.of("*")));
            try {
                if ("offer.percentage".equals(type)) {
                    assertEquals("OFFER_RECOMPILE_REQUIRED", assertThrows(ConflictException.class, () -> service.stage(request)).code());
                    assertEquals("OFFER_RECOMPILE_REQUIRED", assertThrows(ConflictException.class, () -> service.activate("manifest")).code());
                    assertFalse(OfferPolicyGraphCompiler.requiresRecompilation(graph, OfferPolicyGraphCompiler.ARTIFACT_ID_PREFIX + "new"));
                } else {
                    assertThrows(IllegalArgumentException.class, () -> service.stage(request));
                    assertThrows(IllegalArgumentException.class, () -> service.activate("manifest"));
                }
                verify(repository, never()).updateManifestState(anyString(), anyString(), anyString());
                verify(repository, never()).saveOutbox(any());
            } finally { TenantContextHolder.clear(); }
        }
    }
}
