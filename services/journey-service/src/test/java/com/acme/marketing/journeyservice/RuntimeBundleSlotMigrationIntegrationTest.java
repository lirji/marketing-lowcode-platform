package com.acme.marketing.journeyservice;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.mysql.MySQLContainer;

/** 在隔离旧版本真实库上验证回填，不改写原指针或对无接收记录的slot扩大保护。 */
class RuntimeBundleSlotMigrationIntegrationTest {
    @Test void upgradeFencesRecordedSlotsPreservesOldPointersAndCreatesInactiveMissingSlots() {
        try (var mysql = new MySQLContainer("mysql:8.4.11")
                .withCommand("--performance-schema=OFF", "--innodb-buffer-pool-size=64M", "--max-connections=20")
                .withDatabaseName("runtime_bundle_migration").withUsername("test").withPassword("test")
                .withTmpFs(Map.of("/var/lib/mysql", "rw"))
                .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withMemory(512L * 1024 * 1024)
                        .withMemorySwap(512L * 1024 * 1024))
                .withStartupTimeout(Duration.ofMinutes(5))) {
            mysql.start();
            var source = new DriverManagerDataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
            Flyway.configure().dataSource(source).locations("classpath:db/migration").target("3").load().migrate();
            var jdbc = new JdbcTemplate(source);
            String oldTime = "2026-11-16T00:00:00Z", receivedTime = "2026-11-17T00:00:00Z";
            var slots = List.of(List.of("tenant", "test", "cell", "main"),
                    List.of("other-tenant", "test", "cell", "main"), List.of("tenant", "other-env", "cell", "main"),
                    List.of("tenant", "test", "other-cell", "main"), List.of("tenant", "test", "cell", "other-namespace"));
            for (var slot : slots) jdbc.update("insert into mk_journey_runtime_slot (tenant_id,environment_name,cell_id,namespace_name,desired_generation,activation_sequence,directive_signature,directive_json,updated_at) values (?,?,?,?,3,4,'old-signature','old-json',?)",
                    slot.get(0), slot.get(1), slot.get(2), slot.get(3), oldTime);
            for (var entry : List.of(List.of("main", "campaign"), List.of("main", "second-campaign"), List.of("missing", "campaign"))) {
                jdbc.update("insert into mk_runtime_bundle_directive (tenant_id,environment_name,cell_id,namespace_name,campaign_id,activation_sequence,directive_id,directive_json,recorded_by,recorded_at) values ('tenant','test','cell',?,?,1,'directive','{}','operator',?)",
                        entry.get(0), entry.get(1), receivedTime);
                jdbc.update("insert into mk_runtime_bundle_head (tenant_id,environment_name,cell_id,namespace_name,campaign_id,last_sequence,updated_at) values ('tenant','test','cell',?,?,1,?)",
                        entry.get(0), entry.get(1), receivedTime);
            }
            var flyway = Flyway.configure().dataSource(source).locations("classpath:db/migration").target("4").load();
            assertEquals(1, flyway.migrate().migrationsExecuted);
            for (int i = 0; i < slots.size(); i++) assertEquals(i == 0, jdbc.queryForObject(
                    "select bundle_managed from mk_journey_runtime_slot where tenant_id=? and environment_name=? and cell_id=? and namespace_name=?", Boolean.class, slots.get(i).toArray()));
            assertEquals(slots.size(), jdbc.queryForObject("select count(*) from mk_journey_runtime_slot where desired_generation=3 and activation_sequence=4 and directive_signature='old-signature' and directive_json='old-json' and updated_at=?", Integer.class, oldTime));
            assertEquals(1, jdbc.queryForObject("select count(*) from mk_journey_runtime_slot where namespace_name='missing' and desired_generation=0 and activation_sequence=0 and directive_signature='' and directive_json='' and bundle_managed=TRUE and updated_at=?", Integer.class, receivedTime));
            assertEquals(slots.size() + 1, jdbc.queryForObject("select count(*) from mk_journey_runtime_slot", Integer.class));
            assertEquals(3, jdbc.queryForObject("select count(*) from mk_runtime_bundle_directive", Integer.class));
            assertFalse(jdbc.queryForObject("select column_comment from information_schema.columns where table_schema=database() and table_name='mk_journey_runtime_slot' and column_name='bundle_managed'", String.class).isBlank());
            assertEquals(0, flyway.migrate().migrationsExecuted);
        }
    }
}
