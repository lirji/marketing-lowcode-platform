package com.acme.marketing.control;

import static org.junit.jupiter.api.Assertions.*;
import static com.acme.marketing.control.JourneySimulationTest.*;

import com.acme.marketing.testsupport.MySqlIntegrationTest;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

/** 真实HTTP/数据库只读验证，不启动Journey Dispatcher或写入真实业务命令。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class JourneySimulationIntegrationTest extends MySqlIntegrationTest {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    private final String tenant = "sim-" + UUID.randomUUID();
    private final HttpClient http = HttpClient.newHttpClient();
    private static final String INPUT = "{\"initialVariables\":{},\"events\":[],\"horizonSeconds\":20}";

    @BeforeEach void seed() {
        String now = Instant.now().toString();
        jdbc.update("insert into mk_campaign(tenant_id,campaign_id,name,objective,status,organization_id,shop_id,created_at,updated_at,campaign_type) values(?,?,?,?,?,?,?,?,?,?)",
                tenant, "campaign", "仿真隔离测试", "只读", "DRAFT", "org", "shop", now, now, "STANDARD");
        var graph = graph(List.of(node("s", "trigger"), node("wait", "wait-timer", Map.of("delaySeconds", "10")), node("end", "end")), List.of(edge("s", "next", "wait"), edge("wait", "elapsed", "end")), Map.of());
        jdbc.update("insert into mk_definition_version(tenant_id,definition_id,campaign_id,version_no,dialect,graph_json,semantic_hash,status,created_by,created_at,updated_at) values(?,?,?,?,?,?,?,?,?,?,?)",
                tenant, "journey-test", "campaign", 1, graph.dialect().name(), mapper.writeValueAsString(graph), "fixture", "DRAFT", "tester", now, now);
    }

    @Test void actualSavedGraphSimulatesDeterministicallyWithoutMutation() throws Exception {
        var first = send(request().POST(HttpRequest.BodyPublishers.ofString(INPUT)));
        assertEquals(200, first.statusCode(), first.body());
        var json = mapper.readTree(first.body());
        assertEquals("COMPLETED", json.path("stopReason").asString()); assertEquals(10, json.path("elapsedSeconds").asDouble());
        assertEquals(2, json.path("steps").size());
        assertEquals(json, mapper.readTree(send(request().POST(HttpRequest.BodyPublishers.ofString(INPUT))).body()));
        assertEquals("DRAFT", jdbc.queryForObject("select status from mk_definition_version where tenant_id=?", String.class, tenant));
        assertEquals(0, jdbc.queryForObject("select count(*) from mk_control_command where tenant_id=?", Integer.class, tenant));
        assertEquals(0, jdbc.queryForObject("select count(*) from mk_outbox where tenant_id=?", Integer.class, tenant));
    }

    @Test void permissionsScopeAndTenantApplyBeforeReadingBody() throws Exception {
        assertEquals(403, send(request().setHeader("X-Dev-Permissions", "definition:read").POST(HttpRequest.BodyPublishers.ofString("not json"))).statusCode());
        assertEquals(400, send(request().setHeader("X-Dev-Organization-Ids", "other").POST(HttpRequest.BodyPublishers.ofString(INPUT))).statusCode());
        assertEquals(400, send(request().setHeader("X-Dev-Shop-Ids", "other").POST(HttpRequest.BodyPublishers.ofString(INPUT))).statusCode());
        assertEquals(404, send(request().setHeader("X-Dev-Tenant-Id", "other").POST(HttpRequest.BodyPublishers.ofString(INPUT))).statusCode());
    }

    @Test void strictBodyAndChunkedLimitAreEnforced() throws Exception {
        for (String invalid : List.of(INPUT + "{}", INPUT.replace("\"events\":[]", "\"events\":[],\"events\":[]"), INPUT.replace("20", "20.5"))) {
            var response = send(request().POST(HttpRequest.BodyPublishers.ofString(invalid)));
            assertEquals(400, response.statusCode(), response.body());
            assertEquals("JOURNEY_SIMULATION_INPUT_INVALID", mapper.readTree(response.body()).path("code").asString());
        }
        byte[] large = (" ".repeat(65537)).getBytes(StandardCharsets.UTF_8);
        for (var publisher : List.of(HttpRequest.BodyPublishers.ofByteArray(large), HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(large)))) {
            var response = send(request().POST(publisher));
            assertEquals(400, response.statusCode(), response.body());
            assertEquals("JOURNEY_SIMULATION_REQUEST_TOO_LARGE", mapper.readTree(response.body()).path("code").asString());
        }
    }

    private HttpRequest.Builder request() {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/definitions/journey-test/versions/1:simulate-journey"))
                .header("Content-Type", "application/json").header("X-Dev-Tenant-Id", tenant).header("X-Dev-Organization-Ids", "org")
                .header("X-Dev-Shop-Ids", "shop").header("X-Dev-Actor-Id", "tester").header("X-Dev-Permissions", "definition:simulate");
    }
    private HttpResponse<String> send(HttpRequest.Builder request) throws Exception { return http.send(request.build(), HttpResponse.BodyHandlers.ofString()); }
}
