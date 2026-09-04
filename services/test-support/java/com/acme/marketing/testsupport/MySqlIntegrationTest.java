package com.acme.marketing.testsupport;

import java.time.Duration;
import java.util.Map;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.mysql.MySQLContainer;

/** Shared real-MySQL fixture for service integration tests. */
public abstract class MySqlIntegrationTest {
    private static final String EXTERNAL_URL = System.getProperty("marketing.test.mysql.url", "");
    private static final MySQLContainer MYSQL = startContainerWhenNeeded();

    @DynamicPropertySource
    static void mysqlProperties(DynamicPropertyRegistry registry) {
        if (!EXTERNAL_URL.isBlank()) {
            String username = System.getProperty("marketing.test.mysql.username", "root");
            String password = System.getProperty("marketing.test.mysql.password", "");
            registry.add("spring.datasource.url", () -> EXTERNAL_URL);
            registry.add("spring.datasource.username", () -> username);
            registry.add("spring.datasource.password", () -> password);
            registry.add("spring.flyway.user", () -> username);
            registry.add("spring.flyway.password", () -> password);
            return;
        }
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.flyway.user", MYSQL::getUsername);
        registry.add("spring.flyway.password", MYSQL::getPassword);
    }

    private static MySQLContainer startContainerWhenNeeded() {
        if (!EXTERNAL_URL.isBlank()) {
            return null;
        }
        MySQLContainer container = new MySQLContainer("mysql:8.4.11")
                .withDatabaseName("marketing_test")
                .withUsername("test")
                .withPassword("test")
                .withTmpFs(Map.of("/var/lib/mysql", "rw"))
                .withStartupTimeoutSeconds(300)
                .withStartupTimeout(Duration.ofMinutes(5));
        container.start();
        return container;
    }
}
