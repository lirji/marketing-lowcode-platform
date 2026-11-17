package com.acme.marketing.jobs.journey;

import static org.junit.jupiter.api.Assertions.*;

import com.acme.marketing.jobs.support.JsonCodec;
import com.acme.marketing.platform.error.ConflictException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.jdbc.ScriptRunner;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.mysql.MySQLContainer;

/** 用真实Journey迁移和MyBatis查询验证组合执行，不用旧单清单active行冒充组合生效。 */
class JdbcBundleJourneyPlanResolverTest {
    private static final Instant NOW = Instant.parse("2026-11-18T00:00:00Z");
    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.11")
            .withCommand("--performance-schema=OFF", "--innodb-buffer-pool-size=64M", "--max-connections=20")
            .withTmpFs(Map.of("/var/lib/mysql", "rw"))
            .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withMemory(512L * 1024 * 1024).withMemorySwap(512L * 1024 * 1024))
            .withStartupTimeout(Duration.ofMinutes(5));
    @BeforeAll static void schema() throws Exception {
        MYSQL.start();
        try (var connection = connection()) {
            var runner = new ScriptRunner(connection); runner.setLogWriter(null); runner.setStopOnError(true);
            for (String file : new String[]{"V1__journey.sql", "V2__table_comments.sql", "V3__bundle_directive_receipts.sql", "V4__bundle_managed_runtime_slot.sql"})
                try (var reader = Resources.getResourceAsReader("journey-schema/" + file)) { runner.runScript(reader); }
        }
    }
    @AfterAll static void close() { MYSQL.stop(); }

    @Test void resolvesSignedHistoricalMemberWithoutStandaloneActivationAndAfterExpiry() throws Exception {
        var fixture = installed(); var resolver = resolver();
        assertEquals(fixture.plan, resolve(resolver, fixture, NOW));
        assertEquals(fixture.plan, resolve(resolver, fixture, NOW.plusSeconds(3600)));
        assertThrows(IllegalArgumentException.class, () -> resolver.resolve(fixture.tenant, fixture.reference, NOW, true));
        assertEquals(fixture.start(), JsonCodec.read(JsonCodec.write(fixture.start()), JourneyJobInput.class));
        assertEquals(0, count("mk_journey_runtime_activation", fixture.tenant));
        assertEquals(0, count("mk_journey_runtime_slot", fixture.tenant));
    }

    @Test void untrustedIdentityOrReferenceRejectsWithoutTreatingBadReferenceAsDbOutage() throws Exception {
        var fixture = installed(); var resolver = resolver();
        for (var ref : java.util.List.of(new JourneyJobInput.PlanReference("unknown", 2, 1, "journey", 2),
                new JourneyJobInput.PlanReference(fixture.reference.artifactId(), 99, 1, "journey", 2),
                new JourneyJobInput.PlanReference(fixture.reference.artifactId(), 2, 99, "journey", 2),
                new JourneyJobInput.PlanReference(fixture.reference.artifactId(), 2, 1, "other", 2),
                new JourneyJobInput.PlanReference(fixture.reference.artifactId(), 2, 1, "journey", 99)))
            assertThrows(ConflictException.class, () -> resolver.resolveBound(fixture.tenant, fixture.enrollment, "subject", ref, fixture.participation, NOW, true));
        assertThrows(ConflictException.class, () -> resolver.resolveBound(fixture.tenant, "replacement", "subject", fixture.reference, fixture.participation, NOW, true));
        assertEquals(fixture.plan, resolve(resolver, fixture, NOW));
        assertThrows(ConflictException.class, () -> resolver.resolveBound(fixture.tenant, fixture.enrollment, "other", fixture.reference, fixture.participation, NOW, false));
        assertThrows(ConflictException.class, () -> resolver.resolveBound("other", fixture.enrollment, "subject", fixture.reference, fixture.participation, NOW, true));
    }

    @Test void absentSourceIsRecoverableAndSameProofWorksOnceProjectionArrives() throws Exception {
        var fixture = fixture(); var resolver = resolver();
        assertThrows(JourneyInfrastructureException.class, () -> resolve(resolver, fixture, NOW));
        try (var connection = connection()) { fixture.install(connection); }
        assertEquals(fixture.plan, resolve(resolver, fixture, NOW));
    }

    @Test void corruptionIsRecoverableAndCacheCannotMixCampaignsWithSameSequenceAndGeneration() throws Exception {
        var fixture = installed(); var resolver = resolver();
        assertEquals(fixture.plan, resolve(resolver, fixture, NOW));
        var other = new BundleJourneyFixture(fixture.tenant, NOW, "other-campaign", 2);
        try (var connection = connection()) { other.record(connection); }
        assertEquals(other.plan, resolve(resolver, other, NOW));
        update("UPDATE mk_runtime_bundle_directive SET directive_json='{}' WHERE tenant_id=? AND campaign_id='other-campaign'", fixture.tenant);
        assertEquals(fixture.plan, resolve(resolver, fixture, NOW.plusSeconds(1)));
        assertThrows(JourneyInfrastructureException.class, () -> resolve(resolver, other, NOW.plusSeconds(301)));
        update("UPDATE mk_runtime_bundle_directive SET directive_json='null' WHERE tenant_id=? AND campaign_id='other-campaign'", fixture.tenant);
        assertThrows(JourneyInfrastructureException.class, () -> resolve(resolver(), other, NOW));
        update("UPDATE mk_journey_runtime_generation SET manifest_json='null' WHERE tenant_id=?", fixture.tenant);
        assertThrows(JourneyInfrastructureException.class, () -> resolve(resolver(), fixture, NOW));
        try (var connection = connection(); var statement = connection.prepareStatement("UPDATE mk_journey_runtime_generation SET manifest_json=? WHERE tenant_id=?")) {
            statement.setString(1, JsonCodec.write(fixture.member)); statement.setString(2, fixture.tenant); statement.executeUpdate();
        }
        update("UPDATE mk_journey_runtime_generation SET artifact_payload=X'00' WHERE tenant_id=?", fixture.tenant);
        assertThrows(JourneyInfrastructureException.class, () -> resolve(resolver(), fixture, NOW));
    }

    @Test void trustRolesAndCurrentKillSwitchRemainMandatoryForCachedHistoricalPlans() throws Exception {
        var fixture = installed(); var resolver = resolver();
        assertEquals(fixture.plan, resolve(resolver, fixture, NOW));
        var missingParticipation = new JdbcJourneyPlanResolver(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword(),
                "release", BundleJourneyFixture.encoded(BundleJourneyFixture.RELEASE), "", "compiler", BundleJourneyFixture.encoded(BundleJourneyFixture.COMPILER), "", "local", "cell", "main");
        assertThrows(ConflictException.class, () -> resolve(missingParticipation, fixture, NOW));
        var wrongParticipation = new JdbcJourneyPlanResolver(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword(),
                "release", BundleJourneyFixture.encoded(BundleJourneyFixture.RELEASE), "", "compiler", BundleJourneyFixture.encoded(BundleJourneyFixture.COMPILER), "", "local", "cell", "main",
                "participation", BundleJourneyFixture.encoded(BundleJourneyFixture.RELEASE), "");
        assertThrows(ConflictException.class, () -> resolve(wrongParticipation, fixture, NOW));
        try (var connection = connection(); var statement = connection.prepareStatement("INSERT INTO mk_journey_runtime_kill_switch VALUES (?,'main',1,TRUE,'pause','signature','{}',?)")) {
            statement.setString(1, fixture.tenant); statement.setString(2, NOW.toString()); statement.executeUpdate();
        }
        assertThrows(JourneyExecutionPausedException.class, () -> resolve(resolver, fixture, NOW.plusSeconds(1)));
    }

    @Test void executionProbeUsesInstalledBundleBytesWithoutCreatingStandaloneActivation() throws Exception {
        var fixture = installed(); var resolver = resolver();
        probe(resolver, fixture, NOW);
        probe(resolver, fixture, NOW.plusSeconds(1));
        assertEquals(0, count("mk_journey_runtime_activation", fixture.tenant));
        assertEquals(0, count("mk_journey_runtime_slot", fixture.tenant));
        assertThrows(ConflictException.class, () -> probe(resolver, fixture, NOW.plusSeconds(120)));
        assertThrows(ConflictException.class, () -> resolver.verifyExecutionProbe(fixture.probe(), "2".repeat(32), 2, digest(), NOW));
        assertThrows(ConflictException.class, () -> resolver.verifyExecutionProbe(fixture.probe(), "1".repeat(32), 3, digest(), NOW));
        assertThrows(ConflictException.class, () -> resolver.verifyExecutionProbe(fixture.probe("1".repeat(32), 2, "absent"), "1".repeat(32), 2, digest(), NOW));
        // 命中摘要缓存后也不能更换字段、复用签名或绕过当前kill。
        var forged = JsonCodec.read(JsonCodec.write(fixture.probe()).replace("\"probeId\":\"probe\"", "\"probeId\":\"forged\""),
                com.acme.marketing.contracts.release.JourneyBundleExecutionProbe.class);
        assertThrows(ConflictException.class, () -> resolver.verifyExecutionProbe(forged, "1".repeat(32), 2, digest(), NOW));
        try (var connection = connection(); var statement = connection.prepareStatement("INSERT INTO mk_journey_runtime_kill_switch VALUES (?,'main',1,TRUE,'pause','signature','{}',?)")) {
            statement.setString(1, fixture.tenant); statement.setString(2, NOW.toString()); statement.executeUpdate();
        }
        assertThrows(JourneyExecutionPausedException.class, () -> probe(resolver, fixture, NOW.plusSeconds(2)));
    }

    @Test void executionProbeRefusesMissingOrDamagedSourcesAndRetestsAfterRecovery() throws Exception {
        var fixture = fixture(); var resolver = resolver();
        assertThrows(JourneyInfrastructureException.class, () -> probe(resolver, fixture, NOW));
        try (var connection = connection()) { fixture.install(connection); }
        probe(resolver, fixture, NOW);
        update("UPDATE mk_journey_runtime_generation SET artifact_payload=X'00' WHERE tenant_id=?", fixture.tenant);
        assertThrows(IllegalArgumentException.class, () -> probe(resolver(), fixture, NOW));
        update("UPDATE mk_runtime_bundle_directive SET directive_json='null' WHERE tenant_id=?", fixture.tenant);
        assertThrows(ConflictException.class, () -> probe(resolver(), fixture, NOW));
    }

    @Test void executionProbeRequiresParticipationTrustAndActualNamespace() throws Exception {
        var fixture = installed();
        var missing = new JdbcJourneyPlanResolver(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword(),
                "release", BundleJourneyFixture.encoded(BundleJourneyFixture.RELEASE), "", "compiler", BundleJourneyFixture.encoded(BundleJourneyFixture.COMPILER), "", "local", "cell", "main");
        assertThrows(ConflictException.class, () -> probe(missing, fixture, NOW));
        var other = new JdbcJourneyPlanResolver(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword(),
                "release", BundleJourneyFixture.encoded(BundleJourneyFixture.RELEASE), "", "compiler", BundleJourneyFixture.encoded(BundleJourneyFixture.COMPILER), "", "local", "cell", "other",
                "participation", BundleJourneyFixture.encoded(BundleJourneyFixture.PARTICIPATION), "");
        assertThrows(ConflictException.class, () -> probe(other, fixture, NOW));
    }

    private static String digest() { return "sha256:" + "a".repeat(64); }
    private static void probe(JdbcJourneyPlanResolver resolver, BundleJourneyFixture fixture, Instant now) {
        resolver.verifyExecutionProbe(fixture.probe(), "1".repeat(32), 2, digest(), now);
    }

    private static JdbcJourneyPlanResolver resolver() {
        return new JdbcJourneyPlanResolver(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword(),
                "release", BundleJourneyFixture.encoded(BundleJourneyFixture.RELEASE), "", "compiler", BundleJourneyFixture.encoded(BundleJourneyFixture.COMPILER), "", "local", "cell", "main",
                "participation", BundleJourneyFixture.encoded(BundleJourneyFixture.PARTICIPATION), "");
    }
    private static com.acme.marketing.journey.JourneyPlan resolve(JdbcJourneyPlanResolver resolver, BundleJourneyFixture fixture, Instant at) {
        return resolver.resolveBound(fixture.tenant, fixture.enrollment, "subject", fixture.reference, fixture.participation, at, true);
    }
    private static BundleJourneyFixture fixture() { return new BundleJourneyFixture("bundle-" + UUID.randomUUID(), NOW, "campaign", 2); }
    private static BundleJourneyFixture installed() throws Exception {
        var value = fixture(); try (var connection = connection()) { value.install(connection); } return value;
    }
    private static Connection connection() throws Exception { return DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()); }
    private static void update(String sql, String tenant) throws Exception {
        try (var connection = connection(); var statement = connection.prepareStatement(sql)) { statement.setString(1, tenant); statement.executeUpdate(); }
    }
    private static int count(String table, String tenant) throws Exception {
        try (var connection = connection(); var statement = connection.prepareStatement("SELECT count(*) FROM " + table + " WHERE tenant_id=?")) {
            statement.setString(1, tenant); try (var rows = statement.executeQuery()) { rows.next(); return rows.getInt(1); }
        }
    }
}
