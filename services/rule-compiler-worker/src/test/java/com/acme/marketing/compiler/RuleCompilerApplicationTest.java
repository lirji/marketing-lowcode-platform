package com.acme.marketing.compiler;

import com.acme.marketing.testsupport.MySqlIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RuleCompilerApplicationTest extends MySqlIntegrationTest {

    @Test
    void applicationContextStartsWithJdbcRepositoryProxyingEnabled() {
        // Context startup is the assertion. This guards final repository classes
        // that Spring cannot proxy for persistence exception translation.
    }
}
