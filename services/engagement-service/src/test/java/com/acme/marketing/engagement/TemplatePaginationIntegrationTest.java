package com.acme.marketing.engagement;

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
class TemplatePaginationIntegrationTest extends MySqlIntegrationTest {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    private final String tenant = "page-" + UUID.randomUUID();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    @Test void traversesLatestVersionsWithoutDuplicatesWhenEarlierTemplateGetsANewVersion() throws Exception {
        for (int i = 0; i < 110; i++) {
            seed(tenant, "template-" + String.format("%03d", i), 1, "历史名称", "ACTIVE");
            seed(tenant, "template-" + String.format("%03d", i), 2, "名称" + i, "ACTIVE");
        }
        seed(tenant + "-other", "foreign-only", 1, "别的租户", "ACTIVE");
        assertEquals(25, page("").path("items").size());
        List<String> ids = new ArrayList<>(); String cursor = null; int pages = 0;
        do {
            var page = page("limit=25" + (cursor == null ? "" : "&cursor=" + encode(cursor)));
            assertTrue(page.path("items").size() <= 25);
            for (var row : page.path("items")) { ids.add(row.path("templateId").asString()); assertEquals(2, row.path("version").asInt()); }
            pages++;
            if (!page.path("hasMore").asBoolean()) { assertTrue(page.path("nextCursor").isNull()); break; }
            String next = page.path("nextCursor").asString(); assertNotEquals(cursor, next); cursor = next;
            if (pages == 1) seed(tenant, "template-000", 3, "更新名称不移动ID位置", "ACTIVE");
        } while (pages < 10);
        assertEquals(5, pages); assertEquals(110, ids.size()); assertEquals(110, new HashSet<>(ids).size());
        var refreshed = page("limit=1"); assertEquals(3, refreshed.path("items").get(0).path("version").asInt());
        String plan = jdbc.queryForObject("EXPLAIN FORMAT=JSON SELECT current.template_id FROM mk_template_version current WHERE current.tenant_id=? AND current.template_id>? AND current.version_no=(SELECT MAX(latest.version_no) FROM mk_template_version latest WHERE latest.tenant_id=current.tenant_id AND latest.template_id=current.template_id) ORDER BY current.template_id LIMIT 26", String.class, tenant, "template-024");
        assertTrue(plan.contains("PRIMARY"), plan);
    }

    @Test void searchesDisplayedFieldsBeforeLimitAndPreservesVariableOrder() throws Exception {
        for (int i = 0; i < 55; i++) seed(tenant, "template-" + String.format("%03d", i), 1, "SMS", "DRAFT");
        seed(tenant, "template-z-last", 1, "OLD", "DRAFT");
        seed(tenant, "template-z-last", 73, "PUSH", "APPROVED");
        jdbc.update("UPDATE mk_template_version SET required_variables=? WHERE tenant_id=? AND template_id=? AND version_no=?", "zeta,coupon_needle,alpha", tenant, "template-z-last", 73);
        for (String text : List.of("template-z-last", "v73", "PuSh", "_needle", "zeta, coupon_needle, alpha", "已批准", "APPROVED")) {
            var page = page("limit=1&q=" + encode(text));
            assertEquals("template-z-last", page.path("items").get(0).path("templateId").asString(), text);
            assertFalse(page.path("hasMore").asBoolean());
            assertEquals(List.of("zeta", "coupon_needle", "alpha"), mapper.convertValue(page.path("items").get(0).path("requiredVariables"), List.class));
        }
        assertEquals(0, page("q=OLD").path("items").size());
        assertEquals(0, page("q=%25").path("items").size());
        assertEquals(0, page("q=" + encode("正文不属于列表搜索")).path("items").size());
        jdbc.update("UPDATE mk_template_version SET required_variables='' WHERE tenant_id=? AND template_id=?", tenant, "template-000");
        assertEquals("template-000", page("q=" + encode("无")).path("items").get(0).path("templateId").asString());
        assertEquals(0, page("q=" + encode("2026/10/18")).path("items").size());
        var date = page("limit=2&q=" + encode(" 2026/10/19 00:30:00 "));
        assertEquals("2026/10/19 00:30:00", date.path("query").asString());
        assertEquals(2, date.path("items").size()); assertTrue(date.path("hasMore").asBoolean());
        assertEquals(1, page("cursor=template-054&q=" + encode("已批准")).path("items").size());
        // 分页读取不修改同一ID的历史版本正文。
        assertEquals("OLD", jdbc.queryForObject("SELECT channel_name FROM mk_template_version WHERE tenant_id=? AND template_id=? AND version_no=1", String.class, tenant, "template-z-last"));
    }

    @Test void rejectsInvalidQueriesForeignCursorsAndMissingPermission() throws Exception {
        seed(tenant + "-other", "foreign-only", 1, "不可见", "ACTIVE");
        for (String query : List.of("limit=0", "limit=101", "cursor=", "cursor=" + "x".repeat(129), "q=" + "x".repeat(129), "q=%00")) {
            var response = send(request(query)); assertEquals(400, response.statusCode(), query);
            assertEquals("TEMPLATE_QUERY_INVALID", mapper.readTree(response.body()).path("code").asString());
        }
        for (String cursor : List.of("foreign-only", "unknown")) {
            var response = send(request("cursor=" + cursor)); assertEquals(400, response.statusCode());
            assertEquals("TEMPLATE_CURSOR_INVALID", mapper.readTree(response.body()).path("code").asString());
        }
        assertEquals(403, send(request("").setHeader("X-Dev-Permissions", "template:write")).statusCode());
        assertEquals(0, page("").path("items").size());
    }

    private void seed(String owner, String id, int version, String name, String status) {
        jdbc.update("INSERT INTO mk_template_version(tenant_id,template_id,version_no,channel_name,content_text,required_variables,state_name,created_by,created_at) VALUES(?,?,?,?,?,?,?,?,?)",
                owner, id, version, name, "正文不属于列表搜索", "name", status, "fixture", "2026-10-18T16:30:00Z");
    }
    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private HttpRequest.Builder request(String query) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/templates/catalog/page?" + query)).timeout(Duration.ofSeconds(10))
                .header("X-Dev-Tenant-Id", tenant).header("X-Dev-Actor-Id", "tester").header("X-Dev-Permissions", "template:read");
    }
    private HttpResponse<String> send(HttpRequest.Builder request) throws Exception { return client.send(request.GET().build(), HttpResponse.BodyHandlers.ofString()); }
    private JsonNode page(String query) throws Exception { var response = send(request(query)); assertEquals(200, response.statusCode(), response.body()); return mapper.readTree(response.body()); }
}
