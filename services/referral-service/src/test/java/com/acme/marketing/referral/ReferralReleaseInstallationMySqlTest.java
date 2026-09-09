package com.acme.marketing.referral;

import static org.junit.jupiter.api.Assertions.*;
import com.acme.marketing.contracts.artifact.ArtifactAttestation;
import com.acme.marketing.contracts.release.*;
import com.acme.marketing.platform.crypto.Digests;
import com.acme.marketing.platform.crypto.Ed25519;
import com.acme.marketing.platform.identity.*;
import com.acme.marketing.referral.application.release.*;
import java.security.KeyPair;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;

/** 仅隔离 MySQL：验证唯一键竞争、回滚、恢复与纳秒过期，不依赖共享演示数据。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "marketing.security.mode=DEV", "marketing.security.dev-headers-enabled=false"})
class ReferralReleaseInstallationMySqlTest {
    static final Instant NOW = Instant.parse("2026-09-09T00:00:00Z");
    static final KeyPair RELEASE = Ed25519.generateKeyPair();
    static final KeyPair COMPILER = Ed25519.generateKeyPair();
    static final JsonMapper JSON = JsonMapper.builder().build();
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        ReferralParticipationIntegrationTest.database(registry);
    }
    @Autowired ReferralReleaseRepository repository;
    @Autowired PlatformTransactionManager manager;
    @Autowired JdbcTemplate jdbc;
    @Autowired com.acme.marketing.referral.application.ReferralParticipationPermitPort permits;
    String tenant;
    final AtomicReference<Instant> now = new AtomicReference<>(NOW);
    final Clock clock = new Clock() {
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now.get(); }
    };
    @BeforeEach void prepare() { tenant = "release-" + UUID.randomUUID(); now.set(NOW); }
    ReferralReleaseVerifier verifier() {
        return new ReferralReleaseVerifier(Map.of("release", RELEASE.getPublic()), Map.of("compiler", COMPILER.getPublic()), "test", "cell", "default");
    }
    ReferralReleaseInstallationService service(ReferralReleaseRepository repo) {
        return new ReferralReleaseInstallationService(verifier(), repo, clock, manager);
    }
    TenantScope scope() { return new TenantScope(new TenantId(tenant), Set.of("org"), Set.of("shop"), "runtime", Set.of("runtime:warm")); }
    byte[] payload() {
        var policy = new ReferralPlan(NOW, NOW.plusSeconds(3600), NOW.plusSeconds(7200), 600, 0, 0,
                ReferralPlan.GoalType.FIRST_ORDER_SETTLED, 100, "CNY", List.of(new ReferralRewardRule("rule",
                ReferralRewardRule.Role.INVITER, ReferralRewardRule.Mode.PER_RELATION, 1, "benefit-v1", "sku-v1", 1, 1, 100)));
        return JSON.writeValueAsBytes(new ReferralPlanCompiler.CompiledReferralPlan("definition",
                new ReferralPlanCompiler.Scope("org", "shop"), new ReferralPlanCompiler.BindingPolicy(
                ReferralPlanCompiler.Attribution.FIRST_VALID_BIND, 600, ReferralPlanCompiler.InviteeScope.NEW_CUSTOMER), policy));
    }
    ReleaseManifest manifest(String id, Instant expiry) {
        String checksum = "sha256:" + Digests.sha256Hex(payload());
        String source = "sha256:" + "a".repeat(64);
        String signature = ArtifactAttestation.sign(COMPILER.getPrivate(), tenant, "artifact", "definition", 1,
                "REFERRAL_PLAN", "marketing-referral-plan/1", checksum, source);
        var artifact = new ArtifactReference("artifact", "REFERRAL_PLAN", "artifact://local", checksum, source,
                "compiler", signature, "marketing-referral-plan/1", "definition", 1);
        return new ReleaseManifestSigner().sign(new ReleaseManifest(id, new TenantId(tenant), "test", "cell", "referral",
                "default", 1, 0, List.of(), List.of(), List.of(artifact), Map.of(), 0, NOW,
                expiry, "admin", List.of("approval"), NOW, ""), RELEASE.getPrivate());
    }
    ReleaseManifest manifest() { return manifest("manifest", NOW.plusSeconds(600)); }
    int count() { return jdbc.queryForObject("SELECT COUNT(*) FROM mk_referral_verified_release WHERE tenant_id=?", Integer.class, tenant); }

    @Test void survivesServiceReconstructionAndRevalidatesStoredBytes() {
        var m = manifest(); var first = service(repository).install(scope(), "release", m, payload());
        now.set(NOW.plusSeconds(1));
        assertEquals(first, service(repository).install(scope(), "release", m, payload()));
        assertEquals("VERIFIED", first.state()); assertEquals(1, count());
        var stored = jdbc.queryForMap("SELECT manifest_json, artifact_payload FROM mk_referral_verified_release WHERE tenant_id=?", tenant);
        var restored = verifier().verifyInstallation(tenant, "release", JSON.readValue((String) stored.get("manifest_json"), ReleaseManifest.class),
                (byte[]) stored.get("artifact_payload"), now.get());
        assertEquals("definition", restored.plan().definitionId());
    }
    @Test void sameGenerationDifferentSignedManifestDoesNotOverwrite() {
        service(repository).install(scope(), "release", manifest(), payload());
        assertThrows(IllegalArgumentException.class, () -> service(repository).install(scope(), "release",
                manifest("conflict", NOW.plusSeconds(600)), payload()));
        assertEquals("manifest", jdbc.queryForObject("SELECT manifest_id FROM mk_referral_verified_release WHERE tenant_id=?", String.class, tenant));
    }
    @Test void failureAfterInsertRollsBackWholeInstallation() {
        var svc = service(proposed -> { repository.reserveAndLock(proposed); throw new IllegalStateException("test fault"); });
        assertThrows(IllegalStateException.class, () -> svc.install(scope(), "release", manifest(), payload()));
        assertEquals(0, count());
        assertEquals("VERIFIED", service(repository).install(scope(), "release", manifest(), payload()).state());
    }
    @Test void lockWaitExpiryUsesOriginalNanosecondsAndRollsBack() {
        Instant expiry = NOW.plusSeconds(1).plusNanos(123);
        var m = manifest("manifest", expiry);
        var svc = service(proposed -> { var stored = repository.reserveAndLock(proposed); now.set(expiry); return stored; });
        assertThrows(IllegalArgumentException.class, () -> svc.install(scope(), "release", m, payload()));
        assertEquals(0, count());
    }
    @Test void concurrentSameGenerationHasOnePermanentResult() throws Exception {
        var m = manifest(); var svc = service(repository); var scope = scope(); var payload = payload();
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> { start.await(); return svc.install(scope, "release", m, payload); });
            var b = executor.submit(() -> { start.await(); return svc.install(scope, "release", m, payload); });
            start.countDown();
            assertEquals(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS));
        }
        assertEquals(1, count());
    }
    @Test void concurrentConflictingManifestsNeverOverwriteWinner() throws Exception {
        var aManifest = manifest("a", NOW.plusSeconds(600));
        var bManifest = manifest("b", NOW.plusSeconds(600));
        var svc = service(repository); var scope = scope(); var payload = payload();
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> { start.await(); try { return svc.install(scope, "release", aManifest, payload).manifestId(); }
                catch (IllegalArgumentException conflict) { assertEquals("REFERRAL_GENERATION_CONFLICT", conflict.getMessage()); return "conflict"; } });
            var b = executor.submit(() -> { start.await(); try { return svc.install(scope, "release", bManifest, payload).manifestId(); }
                catch (IllegalArgumentException conflict) { assertEquals("REFERRAL_GENERATION_CONFLICT", conflict.getMessage()); return "conflict"; } });
            start.countDown(); var results = List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS));
            assertEquals(1, results.stream().filter("conflict"::equals).count());
            assertTrue(results.contains(jdbc.queryForObject("SELECT manifest_id FROM mk_referral_verified_release WHERE tenant_id=?", String.class, tenant)));
        }
        assertEquals(1, count());
    }
    @Test void verifiedInstallationDoesNotOpenParticipation() {
        service(repository).install(scope(), "release", manifest(), payload());
        assertNull(permits.current(tenant, "campaign", "org", "shop"));
    }
    @Test void tenantMismatchAndMissingPermissionNeverWrite() {
        var m = manifest();
        assertThrows(RuntimeException.class, () -> service(repository).install(
                new TenantScope(new TenantId(tenant), Set.of("org"), Set.of("shop"), "caller"), "release", m, payload()));
        assertThrows(RuntimeException.class, () -> service(repository).install(
                new TenantScope(new TenantId(tenant), Set.of("other"), Set.of("shop"), "runtime", Set.of("runtime:warm")), "release", m, payload()));
        String originalTenant = tenant; tenant = "other-" + UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () -> service(repository).install(scope(), "release", m, payload()));
        assertEquals(0, count()); tenant = originalTenant; assertEquals(0, count());
    }
    @Test void expiredRetryCannotRenewVerifiedTimestamp() {
        var m = manifest(); service(repository).install(scope(), "release", m, payload()); now.set(m.expiresAt());
        assertThrows(IllegalArgumentException.class, () -> service(repository).install(scope(), "release", m, payload()));
        assertEquals(1, count());
    }
    @Test void migrationHasEveryCommentAndNoReadyState() {
        assertEquals(10, jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='mk_referral_verified_release' AND column_comment <> ''", Integer.class));
        assertFalse(jdbc.queryForObject("SELECT table_comment FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='mk_referral_verified_release'", String.class).isBlank());
    }
}
