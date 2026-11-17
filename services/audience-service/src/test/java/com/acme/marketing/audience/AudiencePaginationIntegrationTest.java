package com.acme.marketing.audience;

import static org.junit.jupiter.api.Assertions.*;

import com.acme.marketing.testsupport.MySqlIntegrationTest;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 真实数据库验证搜索先于分页、每ID只读最新版本，以及越权和重复游标保护。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AudiencePaginationIntegrationTest extends MySqlIntegrationTest {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    private final String tenant = "page-" + UUID.randomUUID();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    @Test void traversesLatestVersionsWithoutDuplicatesWhenEarlierSegmentGetsANewVersion() throws Exception {
        for (int i = 0; i < 110; i++) {
            seed(tenant, "segment-" + String.format("%03d", i), 1, "历史名称", "ACTIVE");
            seed(tenant, "segment-" + String.format("%03d", i), 2, "名称" + i, "ACTIVE");
        }
        seed(tenant + "-other", "foreign-only", 1, "别的租户", "ACTIVE");
        assertEquals(25, page("").path("items").size());
        List<String> ids = new ArrayList<>(); String cursor = null; int pages = 0;
        do {
            var page = page("limit=25" + (cursor == null ? "" : "&cursor=" + encode(cursor)));
            assertTrue(page.path("items").size() <= 25);
            for (var row : page.path("items")) { ids.add(row.path("segmentId").asString()); assertEquals(2, row.path("version").asInt()); }
            pages++;
            if (!page.path("hasMore").asBoolean()) { assertTrue(page.path("nextCursor").isNull()); break; }
            String next = page.path("nextCursor").asString(); assertNotEquals(cursor, next); cursor = next;
            if (pages == 1) seed(tenant, "segment-000", 3, "更新名称不移动ID位置", "ACTIVE");
        } while (pages < 10);
        assertEquals(5, pages); assertEquals(110, ids.size()); assertEquals(110, new HashSet<>(ids).size());
        var refreshed = page("limit=1"); assertEquals(3, refreshed.path("items").get(0).path("version").asInt());
        String plan = jdbc.queryForObject("EXPLAIN FORMAT=JSON SELECT current.segment_id FROM mk_segment_definition current WHERE current.tenant_id=? AND current.segment_id>? AND current.version_no=(SELECT MAX(latest.version_no) FROM mk_segment_definition latest WHERE latest.tenant_id=current.tenant_id AND latest.segment_id=current.segment_id) ORDER BY current.segment_id LIMIT 26", String.class, tenant, "segment-024");
        assertTrue(plan.contains("PRIMARY"), plan);
    }

    @Test void searchesEveryVisibleFieldBeforeLimitIncludingLiteralSymbolsAndBeijingDate() throws Exception {
        for (int i = 0; i < 55; i++) seed(tenant, "segment-" + String.format("%03d", i), 1, "其他人群", "ACTIVE");
        seed(tenant, "segment-z-last", 1, "已废弃的旧名称", "DRAFT");
        seed(tenant, "segment-z-last", 73, "Needle 100%_Plus", "READY");
        jdbc.update("UPDATE mk_segment_definition SET rule_hash=? WHERE tenant_id=? AND segment_id=? AND version_no=?", "special-rule-fingerprint", tenant, "segment-z-last", 73);
        for (String query : List.of("nEeDlE", "%_", "segment-z-last", "73", "special-rule-fingerprint", "已就绪", "READY")) {
            var page = page("limit=1&q=" + encode(query));
            assertEquals("segment-z-last", page.path("items").get(0).path("segmentId").asString(), query);
            assertEquals(73, page.path("items").get(0).path("version").asInt());
            assertFalse(page.path("hasMore").asBoolean());
        }
        assertEquals(0, page("q=" + encode("已废弃的旧名称")).path("items").size());
        assertEquals(0, page("q=" + encode("2026/10/18")).path("items").size());
        var date = page("limit=2&q=" + encode(" 2026/10/19 00:30:00 "));
        assertEquals("2026/10/19 00:30:00", date.path("query").asString());
        assertEquals(2, date.path("items").size()); assertTrue(date.path("hasMore").asBoolean());
        // 游标可以来自另一个筛选；存在性校验不依赖当前搜索命中，但始终受租户约束。
        assertEquals(1, page("cursor=segment-054&q=" + encode("已就绪")).path("items").size());
    }

    @Test void rejectsInvalidQueriesForeignCursorsAndMissingPermission() throws Exception {
        seed(tenant + "-other", "foreign-only", 1, "不可见", "ACTIVE");
        for (String query : List.of("limit=0", "limit=101", "cursor=", "cursor=" + "x".repeat(129), "q=" + "x".repeat(129), "q=%00")) {
            var response = send(request(query)); assertEquals(400, response.statusCode(), query);
            assertEquals("AUDIENCE_QUERY_INVALID", mapper.readTree(response.body()).path("code").asString());
        }
        for (String cursor : List.of("foreign-only", "unknown")) {
            var response = send(request("cursor=" + cursor)); assertEquals(400, response.statusCode());
            assertEquals("AUDIENCE_CURSOR_INVALID", mapper.readTree(response.body()).path("code").asString());
        }
        assertEquals(403, send(request("").setHeader("X-Dev-Permissions", "audience:write")).statusCode());
        assertEquals(0, page("").path("items").size());
    }

    private void seed(String owner, String id, int version, String name, String status) {
        jdbc.update("INSERT INTO mk_segment_definition(tenant_id,segment_id,version_no,name,rule_json,rule_hash,state_name,created_by,created_at) VALUES(?,?,?,?,?,?,?,?,?)",
                owner, id, version, name, "{\"match\":\"ALL\",\"conditions\":[]}", "hash-" + id + '-' + version, status, "tester", "2026-10-18T16:30:00.000000000Z");
    }
    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private HttpRequest.Builder request(String query) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/audiences/page?" + query)).timeout(Duration.ofSeconds(10))
                .header("X-Dev-Tenant-Id", tenant).header("X-Dev-Actor-Id", "tester").header("X-Dev-Permissions", "audience:read");
    }
    private HttpResponse<String> send(HttpRequest.Builder request) throws Exception { return client.send(request.GET().build(), HttpResponse.BodyHandlers.ofString()); }
    private JsonNode page(String query) throws Exception { var response = send(request(query)); assertEquals(200, response.statusCode(), response.body()); return mapper.readTree(response.body()); }
}
