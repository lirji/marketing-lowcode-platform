package com.acme.marketing.decision;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    @Autowired private com.acme.marketing.decision.runtime.AudienceMembershipStore memberships;
    @Autowired private com.acme.marketing.decision.application.DecisionCommandRepository commands;
    @Autowired private org.springframework.transaction.PlatformTransactionManager transactionManager;
    @Autowired private tools.jackson.databind.ObjectMapper objectMapper;

    @Test
    @SuppressWarnings("unchecked")
    void decisionTransactionSeesCommittedRevocationAndDoesNotReuseMybatisSessionCache() throws Exception {
        String tenant = "txn-" + UUID.randomUUID();
        var time = new MutableClock(java.time.Instant.parse(NOW));
        var projection = new com.acme.marketing.decision.runtime.AudienceMembershipProjection(memberships, time);
        projection.update(tenant, "a", "subject", true, 1, time.instant().plusSeconds(100));
        projection.update(tenant, "b", "subject", true, 1, time.instant().plusSeconds(100));
        var decisions = org.mockito.Mockito.mock(com.acme.marketing.decision.application.DecisionApplicationService.class);
        var cart = new com.acme.marketing.decision.model.Cart("CNY", java.util.List.of(
                new com.acme.marketing.decision.model.CartLine("line", "sku", "shop", "", "", 1,
                        new com.acme.marketing.decision.model.Money("CNY", 100),
                        com.acme.marketing.decision.model.Money.zero("CNY"), false)));
        try (var writer = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            org.mockito.Mockito.when(decisions.evaluate(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                    .thenAnswer(invocation -> {
                        assertTrue(projection.isMember(tenant, "a", "subject", time.instant()));
                        assertTrue(projection.isMember(tenant, "b", "subject", time.instant()));
                        writer.submit(() -> memberships.saveIfNewer(tenant, "b",
                                com.acme.marketing.platform.crypto.Digests.sha256Hex("subject"), false, 2,
                                time.instant().plusSeconds(100), time.instant())).get(5, java.util.concurrent.TimeUnit.SECONDS);
                        time.now = time.now.plusSeconds(1);
                        assertFalse(projection.isMember(tenant, "b", "subject", time.instant()));
                        return new com.acme.marketing.decision.application.DecisionApplicationService.DecisionResponse(
                                "request", "quote", 1, new com.acme.marketing.decision.pricing.PricingEngine().price(cart, java.util.List.of()),
                                "", java.util.List.of(), 0, time.instant());
                    });
            var executor = new com.acme.marketing.decision.application.DecisionRequestExecutor(commands, objectMapper,
                    time, decisions, transactionManager, org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class), "JDBC");
            var scope = new com.acme.marketing.platform.identity.TenantScope(
                    new com.acme.marketing.platform.identity.TenantId(tenant), java.util.Set.of("*"), java.util.Set.of("*"), "test", java.util.Set.of("*"));
            executor.execute(scope, new com.acme.marketing.decision.application.DecisionApplicationService.DecisionRequest(
                    "request", "request-" + UUID.randomUUID(), "org", "order", "subject", "WEB", time.instant(), cart, java.util.Set.of("a", "b")));
        }
    }

    @Test
    void boundedProjectionObservesRevocationsAcrossReplicasWithoutTimestampCursor() {
        String tenant = "cache-" + UUID.randomUUID();
        var time = new MutableClock(java.time.Instant.parse(NOW));
        var first = new com.acme.marketing.decision.runtime.AudienceMembershipProjection(
                memberships, time, 2, java.time.Duration.ofSeconds(1));
        var second = new com.acme.marketing.decision.runtime.AudienceMembershipProjection(
                memberships, time, 2, java.time.Duration.ofSeconds(1));
        var expiry = time.instant().plusSeconds(3600);
        first.update(tenant, "a", "subject", true, 1, expiry);
        assertTrue(first.isMember(tenant, "a", "subject", time.instant()));
        assertTrue(second.isMember(tenant, "a", "subject", time.instant()));
        // 同时间戳的撤销，以及迟到旧时间戳的更新，不再依赖全局 updated_at 水位。
        first.update(tenant, "a", "subject", false, 2, expiry);
        assertFalse(first.isMember(tenant, "a", "subject", time.instant()));
        time.now = time.now.plusSeconds(1);
        assertFalse(second.isMember(tenant, "a", "subject", time.instant()));
        memberships.saveIfNewer(tenant, "a", com.acme.marketing.platform.crypto.Digests.sha256Hex("subject"),
                true, 3, expiry, time.instant().minusSeconds(30));
        time.now = time.now.plusSeconds(1);
        assertTrue(second.isMember(tenant, "a", "subject", time.instant()));
        // 旧事件写入被数据库拒绝；当前实例也不能把该旧状态当成缓存真相。
        second.update(tenant, "a", "subject", false, 2, expiry);
        assertTrue(second.isMember(tenant, "a", "subject", time.instant()));
        assertFalse(second.isMember("another-tenant", "a", "subject", time.instant()));
        assertFalse(second.isMember(tenant, "different-audience", "subject", time.instant()));
        // 超过容量后再次查询仍可回源，不会因内存淘汰永久失去资格。
        assertTrue(second.isMember(tenant, "a", "subject", time.instant()));
        time.now = expiry;
        assertFalse(second.isMember(tenant, "a", "subject", time.instant()));
    }

    private static final class MutableClock extends java.time.Clock {
        private java.time.Instant now;
        MutableClock(java.time.Instant now) { this.now = now; }
        @Override public java.time.ZoneId getZone() { return java.time.ZoneOffset.UTC; }
        @Override public java.time.Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public java.time.Instant instant() { return now; }
    }

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
        var row = mapper.selectAudienceMembership(tenantId, "audience-1", "subject-hash");
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
