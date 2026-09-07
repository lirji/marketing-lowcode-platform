package com.acme.marketing.decision;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.acme.marketing.decision.infrastructure.persistence.mapper.DecisionMapper;
import com.acme.marketing.decision.infrastructure.persistence.mapper.DecisionMapper.AudienceMembershipWrite;
import com.acme.marketing.decision.infrastructure.persistence.mapper.DecisionMapper.CommandWrite;
import com.acme.marketing.decision.infrastructure.persistence.mapper.DecisionMapper.KillSwitchWrite;
import com.acme.marketing.decision.infrastructure.persistence.mapper.DecisionMapper.RuntimeGenerationKey;
import com.acme.marketing.decision.infrastructure.persistence.mapper.DecisionMapper.RuntimeGenerationWrite;
import com.acme.marketing.decision.infrastructure.persistence.mapper.DecisionMapper.RuntimeSlotKey;
import com.acme.marketing.decision.infrastructure.persistence.mapper.DecisionMapper.RuntimeSlotQuery;
import com.acme.marketing.decision.infrastructure.persistence.mapper.DecisionMapper.RuntimeSlotWrite;
import com.acme.marketing.testsupport.MySqlIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** 验证决策服务所有 MyBatis 语句能够在真实 MySQL 上装载和执行。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DecisionPersistenceIntegrationTest extends MySqlIntegrationTest {
    private static final String NOW = "2026-09-07T01:00:00Z";

    @Autowired private DecisionMapper mapper;

    @Test
    void mapperStatementsCoverCommandsRuntimeProjectionAndKillSwitch() {
        String suffix = UUID.randomUUID().toString();
        String tenantId = "tenant-" + suffix;
        verifyCommands(tenantId);
        verifyRuntimeState(tenantId);
        verifyAudienceProjection(tenantId);
        verifyKillSwitch(tenantId);
    }

    private void verifyCommands(String tenantId) {
        assertEquals(1, mapper.insertCommand(new CommandWrite(tenantId, "command-123", "hash", "PROCESSING",
                null, NOW, "2026-09-08T01:00:00Z")));
        assertEquals("hash", mapper.selectCommandForUpdate(tenantId, "command-123").payloadHash());
        assertEquals(1, mapper.completeCommand(tenantId, "command-123", "{}"));
        assertEquals("COMPLETED", mapper.selectCommandForUpdate(tenantId, "command-123").stateName());
        assertEquals(1, mapper.deleteExpiredCommand(tenantId, "command-123", "2026-09-09T01:00:00Z"));
    }

    private void verifyRuntimeState(String tenantId) {
        byte[] payload = "artifact".getBytes(StandardCharsets.UTF_8);
        assertEquals(1, mapper.insertRuntimeGeneration(new RuntimeGenerationWrite(tenantId, "local", "cell-a",
                "decision", "main", 1, "release-key", "{}", payload, "manifest-signature", NOW)));
        var generation = mapper.selectRuntimeGeneration(new RuntimeGenerationKey(
                tenantId, "local", "cell-a", "main", 1));
        assertNotNull(generation);
        assertArrayEquals(payload, generation.artifactPayload());

        RuntimeSlotWrite initial = new RuntimeSlotWrite(tenantId, "local", "cell-a", "decision", "main",
                0, 0, "", "", NOW);
        assertEquals(1, mapper.insertRuntimeSlot(initial));
        RuntimeSlotKey key = new RuntimeSlotKey(tenantId, "local", "cell-a", "decision", "main");
        assertEquals(0, mapper.selectRuntimeSlotForUpdate(key).desiredGeneration());
        RuntimeSlotWrite active = new RuntimeSlotWrite(tenantId, "local", "cell-a", "decision", "main",
                1, 2, "directive-signature", "{}", NOW);
        assertEquals(1, mapper.updateRuntimeSlot(active));
        assertEquals(1, mapper.selectRuntimeSlot(key).desiredGeneration());
        assertEquals(tenantId, mapper.selectDesiredPointers(new RuntimeSlotQuery("local", "cell-a", "main"))
                .getFirst().tenantId());
    }

    private void verifyAudienceProjection(String tenantId) {
        AudienceMembershipWrite first = new AudienceMembershipWrite(tenantId, "audience-1", "subject-hash",
                true, 1, "2026-09-08T01:00:00Z", NOW);
        assertEquals(1, mapper.insertAudienceMembership(first));
        AudienceMembershipWrite newer = new AudienceMembershipWrite(tenantId, "audience-1", "subject-hash",
                false, 2, "2026-09-09T01:00:00Z", "2026-09-07T02:00:00Z");
        assertEquals(1, mapper.updateAudienceMembershipIfNewer(newer));
        assertEquals(0, mapper.updateAudienceMembershipIfNewer(first));
        var row = mapper.selectAudienceMembershipsChangedSince(NOW).getFirst();
        assertEquals(2, row.membershipVersion());
        assertEquals(false, row.memberValue());
    }

    private void verifyKillSwitch(String tenantId) {
        KillSwitchWrite first = new KillSwitchWrite(tenantId, "main", 1, false, "initial",
                "signature-1", "{}", NOW);
        assertEquals(1, mapper.insertKillSwitch(first));
        assertEquals(1, mapper.selectKillSwitchForUpdate(tenantId, "main").switchSequence());
        KillSwitchWrite newer = new KillSwitchWrite(tenantId, "main", 2, true, "emergency",
                "signature-2", "{}", "2026-09-07T02:00:00Z");
        assertEquals(1, mapper.updateKillSwitch(newer));
        assertEquals(2, mapper.selectKillSwitchForUpdate(tenantId, "main").switchSequence());
        assertEquals(tenantId, mapper.selectKillSwitchDirectives("main").getFirst().tenantId());
    }
}
