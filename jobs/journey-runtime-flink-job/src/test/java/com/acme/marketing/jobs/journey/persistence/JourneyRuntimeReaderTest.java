package com.acme.marketing.jobs.journey.persistence;

import static org.junit.jupiter.api.Assertions.*;

import com.acme.marketing.jobs.journey.JourneyInfrastructureException;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.Map;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.jdbc.ScriptRunner;
import org.junit.jupiter.api.Test;
import org.testcontainers.mysql.MySQLContainer;

class JourneyRuntimeReaderTest {
    @Test
    void mapperReadsTenantScopedCurrentAndHistoricalRowsAndDatabaseFailureIsRecoverable() throws Exception {
        try (var mysql = new MySQLContainer("mysql:8.4.11").withTmpFs(Map.of("/var/lib/mysql", "rw"))) {
            mysql.start();
            try (var connection = DriverManager.getConnection(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())) {
                ScriptRunner runner = new ScriptRunner(connection);
                runner.setLogWriter(null);
                runner.setStopOnError(true);
                for (String file : new String[]{"V1__journey.sql", "V2__table_comments.sql"}) {
                    try (var reader = Resources.getResourceAsReader("journey-schema/" + file)) { runner.runScript(reader); }
                }
                try (var statement = connection.createStatement()) {
                    statement.executeUpdate("INSERT INTO mk_journey_runtime_generation VALUES ('t','local','cell','main',1,'key','{}','artifact',X'0102','sig','2026-01-01T00:00:00Z')");
                    statement.executeUpdate("INSERT INTO mk_journey_runtime_slot VALUES ('t','local','cell','main',1,2,'sig','{}','2026-01-01T00:00:00Z')");
                    statement.executeUpdate("INSERT INTO mk_journey_runtime_activation VALUES ('t','local','cell','main',2,1,'sig','{}','2026-01-01T00:00:00Z')");
                    statement.executeUpdate("INSERT INTO mk_journey_runtime_kill_switch VALUES ('t','main',1,true,'pause','sig','{}','2026-01-01T00:00:00Z')");
                }
                connection.commit();
            }
            var reader = new JourneyRuntimeReader(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
            assertTrue(reader.killSwitch("t", "main").enabled());
            assertNull(reader.killSwitch("other", "main"));
            assertEquals(2, reader.current("t", "local", "cell", "main").activationSequence());
            assertNull(reader.current("t", "local", "wrong-cell", "main"));
            var historical = reader.activated("t", "local", "cell", "main", 1, 2, "artifact");
            assertArrayEquals(new byte[]{1, 2}, historical.payload());
            assertNull(reader.activated("t", "local", "cell", "main", 1, 3, "artifact"));
            mysql.stop();
            assertTimeoutPreemptively(Duration.ofSeconds(10), () -> assertThrows(JourneyInfrastructureException.class,
                    () -> reader.killSwitch("t", "main")));
        }
    }

    @Test
    void urlCannotDisableTheConfiguredTimeouts() {
        assertEquals("jdbc:mysql://localhost/db?useSSL=false&connectTimeout=2000&socketTimeout=3000",
                JourneyRuntimeReader.boundedUrl("jdbc:mysql://localhost/db?connectTimeout=0&useSSL=false&socketTimeout=0", 2000, 3000));
    }
}
