package com.acme.marketing.benefit;

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
class BenefitPaginationIntegrationTest extends MySqlIntegrationTest {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    private final String tenant = "page-" + UUID.randomUUID();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    @Test void traversesLatestVersionsWithoutDuplicatesWhenEarlierBenefitGetsANewVersion() throws Exception {
        for (int i = 0; i < 110; i++) {
            seed(tenant, "benefit-" + String.format("%03d", i), 1, "历史名称", "ACTIVE");
            seed(tenant, "benefit-" + String.format("%03d", i), 2, "名称" + i, "ACTIVE");
        }
        seed(tenant + "-other", "foreign-only", 1, "别的租户", "ACTIVE");
        assertEquals(25, page("").path("items").size());
        List<String> ids = new ArrayList<>(); String cursor = null; int pages = 0;
        do {
            var page = page("limit=25" + (cursor == null ? "" : "&cursor=" + encode(cursor)));
            assertTrue(page.path("items").size() <= 25);
            for (var row : page.path("items")) { ids.add(row.path("benefitId").asString()); assertEquals(2, row.path("version").asInt()); }
            pages++;
            if (!page.path("hasMore").asBoolean()) { assertTrue(page.path("nextCursor").isNull()); break; }
            String next = page.path("nextCursor").asString(); assertNotEquals(cursor, next); cursor = next;
            if (pages == 1) seed(tenant, "benefit-000", 3, "更新名称不移动ID位置", "ACTIVE");
        } while (pages < 10);
        assertEquals(5, pages); assertEquals(110, ids.size()); assertEquals(110, new HashSet<>(ids).size());
        var refreshed = page("limit=1"); assertEquals(3, refreshed.path("items").get(0).path("version").asInt());
        String plan = jdbc.queryForObject("EXPLAIN FORMAT=JSON SELECT current.benefit_id FROM mk_benefit_definition current WHERE current.tenant_id=? AND current.benefit_id>? AND current.version_no=(SELECT MAX(latest.version_no) FROM mk_benefit_definition latest WHERE latest.tenant_id=current.tenant_id AND latest.benefit_id=current.benefit_id) ORDER BY current.benefit_id LIMIT 26", String.class, tenant, "benefit-024");
        assertTrue(plan.contains("PRIMARY"), plan);
    }

    @Test void searchesEveryVisibleFieldBeforeLimitIncludingLiteralSymbolsAndBeijingDate() throws Exception {
        for (int i = 0; i < 55; i++) seed(tenant, "benefit-" + String.format("%03d", i), 1, "其他权益", "ACTIVE");
        seed(tenant, "benefit-z-last", 1, "已废弃的旧名称", "DRAFT");
        seed(tenant, "benefit-z-last", 73, "Needle 100%_Plus", "RETIRED");
        jdbc.update("UPDATE mk_benefit_definition SET benefit_sku_id=? WHERE tenant_id=? AND benefit_id=? AND version_no=?", "special-sku-binding", tenant, "benefit-z-last", 73);
        for (String query : List.of("nEeDlE", "%_", "benefit-z-last", "73", "special-sku-binding", "已退役", "RETIRED")) {
            var page = page("limit=1&q=" + encode(query));
            assertEquals("benefit-z-last", page.path("items").get(0).path("benefitId").asString(), query);
            assertEquals(73, page.path("items").get(0).path("version").asInt());
            assertFalse(page.path("hasMore").asBoolean());
        }
        jdbc.update("UPDATE mk_benefit_definition SET benefit_sku_id=NULL WHERE tenant_id=? AND benefit_id=?", tenant, "benefit-000");
        assertEquals("benefit-000", page("q=" + encode("未绑定 SKU")).path("items").get(0).path("benefitId").asString());
        assertEquals(0, page("q=" + encode("已废弃的旧名称")).path("items").size());
        assertEquals(0, page("q=" + encode("2026/10/18")).path("items").size());
        var date = page("limit=2&q=" + encode(" 2026/10/19 00:30:00 "));
        assertEquals("2026/10/19 00:30:00", date.path("query").asString());
        assertEquals(2, date.path("items").size()); assertTrue(date.path("hasMore").asBoolean());
        // 游标可以来自另一个筛选；存在性校验不依赖当前搜索命中，但始终受租户约束。
        assertEquals(1, page("cursor=benefit-054&q=" + encode("已退役")).path("items").size());
    }

    @Test void rejectsInvalidQueriesForeignCursorsAndMissingPermission() throws Exception {
        seed(tenant + "-other", "foreign-only", 1, "不可见", "ACTIVE");
        for (String query : List.of("limit=0", "limit=101", "cursor=", "cursor=" + "x".repeat(129), "q=" + "x".repeat(129), "q=%00")) {
            var response = send(request(query)); assertEquals(400, response.statusCode(), query);
            assertEquals("BENEFIT_QUERY_INVALID", mapper.readTree(response.body()).path("code").asString());
        }
        for (String cursor : List.of("foreign-only", "unknown")) {
            var response = send(request("cursor=" + cursor)); assertEquals(400, response.statusCode());
            assertEquals("BENEFIT_CURSOR_INVALID", mapper.readTree(response.body()).path("code").asString());
        }
        assertEquals(403, send(request("").setHeader("X-Dev-Permissions", "benefit:write")).statusCode());
        assertEquals(0, page("").path("items").size());
    }

    @Test void preservesDetailLookupForTheValidBenefitIdPage() throws Exception {
        seed(tenant, "page", 1, "原有权益详情", "DRAFT");
        var response = send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/benefits/page"))
                .header("X-Dev-Tenant-Id", tenant).header("X-Dev-Actor-Id", "tester").header("X-Dev-Permissions", "benefit:read"));
        assertEquals(200, response.statusCode());
        assertEquals("page", mapper.readTree(response.body()).path("benefitId").asString());
        assertEquals("page", page("").path("items").get(0).path("benefitId").asString());
    }

    private void seed(String owner, String id, int version, String name, String status) {
        jdbc.update("INSERT INTO mk_benefit_definition(tenant_id,benefit_id,version_no,name_text,status_name,resource_key,benefit_sku_id,policy_json,created_by,created_at) VALUES(?,?,?,?,?,?,?,?,?,?)",
                owner, id, version, name, status, "resource-key", "sku-common", "{}", "fixture", "2026-10-18T16:30:00Z");
    }
    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private HttpRequest.Builder request(String query) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/benefits/catalog/page?" + query)).timeout(Duration.ofSeconds(10))
                .header("X-Dev-Tenant-Id", tenant).header("X-Dev-Actor-Id", "tester").header("X-Dev-Permissions", "benefit:read");
    }
    private HttpResponse<String> send(HttpRequest.Builder request) throws Exception { return client.send(request.GET().build(), HttpResponse.BodyHandlers.ofString()); }
    private JsonNode page(String query) throws Exception { var response = send(request(query)); assertEquals(200, response.statusCode(), response.body()); return mapper.readTree(response.body()); }
}
