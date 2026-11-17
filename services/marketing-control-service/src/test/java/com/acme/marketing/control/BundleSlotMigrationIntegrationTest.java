package com.acme.marketing.control;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.mysql.MySQLContainer;

/** 从真实V9库升级，证明历史组合被保护且已生效指针和非组合slot不受影响。 */
class BundleSlotMigrationIntegrationTest {
    @Test void backfillsOnlyCompleteMatchingSlotsAndKeepsActivePointers() {
        try (var mysql = new MySQLContainer("mysql:8.4.11")
                .withCommand("--performance-schema=OFF", "--innodb-buffer-pool-size=64M", "--max-connections=20")
                .withDatabaseName("bundle_migration_test").withUsername("test").withPassword("test")
                .withTmpFs(Map.of("/var/lib/mysql", "rw"))
                .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withMemory(512L * 1024 * 1024)
                        .withMemorySwap(512L * 1024 * 1024))
                .withStartupTimeout(Duration.ofMinutes(5))) {
            mysql.start();
            var source = new DriverManagerDataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
            Flyway.configure().dataSource(source).locations("classpath:db/migration").target("9").load().migrate();
            var jdbc = new JdbcTemplate(source);
            String now = "2026-11-17T00:00:00Z";
            jdbc.update("insert into mk_campaign (tenant_id,campaign_id,name,objective,status,created_at,updated_at) values (?,?,?,?,?,?,?)",
                    "tenant", "campaign", "迁移验证", "隔离数据", "DRAFT", now, now);
            jdbc.update("insert into mk_release_bundle (tenant_id,bundle_id,campaign_id,version_no,environment_name,cell_id,state_name,content_json,content_hash,command_id,created_by,created_at) values (?,?,?,?,?,?,?,?,?,?,?,?)",
                    "tenant", "bundle", "campaign", 1, "test", "cell", "PREPARING", "{}", "a".repeat(64), "migration-key", "test", now);
            var slots = List.of(List.of("tenant", "test", "cell", "journey", "main"),
                    List.of("other-tenant", "test", "cell", "journey", "main"),
                    List.of("tenant", "other-env", "cell", "journey", "main"),
                    List.of("tenant", "test", "other-cell", "journey", "main"),
                    List.of("tenant", "test", "cell", "decision", "main"),
                    List.of("tenant", "test", "cell", "journey", "other-namespace"));
            for (var slot : slots) jdbc.update("insert into mk_release_slot (tenant_id,environment_name,cell_id,runtime_name,namespace_name,stable_generation,desired_generation,latest_generation,activation_sequence,updated_at) values (?,?,?,?,?,2,2,3,4,?)",
                    slot.get(0), slot.get(1), slot.get(2), slot.get(3), slot.get(4), now);
            jdbc.update("insert into mk_release_manifest (tenant_id,manifest_id,environment_name,cell_id,runtime_name,namespace_name,generation_no,state_name,manifest_json,created_at,bundle_id) values (?,?,?,?,?,?,?,?,?,?,?)",
                    "tenant", "member", "test", "cell", "journey", "main", 3, "STAGED", "{}", now, "bundle");
            var flyway = Flyway.configure().dataSource(source).locations("classpath:db/migration").target("10").load();
            assertEquals(1, flyway.migrate().migrationsExecuted);
            for (int i = 0; i < slots.size(); i++) {
                var slot = slots.get(i);
                assertEquals(i == 0, jdbc.queryForObject("select bundle_managed from mk_release_slot where tenant_id=? and environment_name=? and cell_id=? and runtime_name=? and namespace_name=?",
                        Boolean.class, slot.toArray()));
            }
            assertEquals(slots.size(), jdbc.queryForObject("select count(*) from mk_release_slot where stable_generation=2 and desired_generation=2 and latest_generation=3 and activation_sequence=4 and updated_at=?", Integer.class, now));
            assertEquals(0, jdbc.queryForObject("select count(*) from mk_outbox", Integer.class));
            assertFalse(jdbc.queryForObject("select column_comment from information_schema.columns where table_schema=database() and table_name='mk_release_slot' and column_name='bundle_managed'", String.class).isBlank());
            assertEquals(0, flyway.migrate().migrationsExecuted);
        }
    }
}
