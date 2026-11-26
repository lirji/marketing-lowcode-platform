package com.acme.marketing.control;

import static org.junit.jupiter.api.Assertions.*;

import com.acme.marketing.testsupport.MySqlIntegrationTest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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

/** 使用真实MySQL与HTTP证明范围过滤先于截断，翻页不会被同时间记录和审核更新时间扰动。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApprovalPaginationIntegrationTest extends MySqlIntegrationTest {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final String tenant = "approval-" + UUID.randomUUID();
    private static final String EARLY = "2026-11-01T00:00:00.000Z";
    private static final String LATE = "2026-11-02T00:00:00.000Z";

    @Test void filtersScopeAndStatusBeforeLimitAndTraversesAllTiedRows() throws Exception {
        seed("org-hidden", "org-b", "shop-a", "OPEN", 105, LATE);
        seed("shop-hidden", "org-a", "shop-b", "OPEN", 105, LATE);
        seed("closed", "org-a", "shop-a", "APPROVED", 120, LATE);
        seed("visible", "org-a", "shop-a", "OPEN", 110, EARLY);
        var legacy = body(send(request("/api/v1/approvals")));
        assertEquals(100, legacy.size());
        for (var row : legacy) assertTrue(row.path("caseId").asString().startsWith("closed-"));
        List<String> ids = new ArrayList<>(); String cursor = null;
        do {
            var page = body(send(request("/api/v1/approvals/page?status=OPEN&limit=25" + (cursor == null ? "" : "&cursor=" + cursor))));
            assertEquals("OPEN", page.path("status").asString());
            assertTrue(page.path("items").size() <= 25);
            for (var row : page.path("items")) ids.add(row.path("caseId").asString());
            assertEquals(page.path("hasMore").asBoolean(), !page.path("nextCursor").isNull());
            cursor = page.path("nextCursor").isNull() ? null : page.path("nextCursor").asString();
        } while (cursor != null);
        assertEquals(110, ids.size()); assertEquals(110, new HashSet<>(ids).size());
        assertEquals("visible-109", ids.getFirst()); assertEquals("visible-000", ids.getLast());
        var approved = body(send(request("/api/v1/approvals/page?status=APPROVED&limit=100")));
        assertEquals(100, approved.path("items").size()); assertTrue(approved.path("hasMore").asBoolean());
        var older = body(send(request("/api/v1/approvals/page?status=APPROVED&cursor=" + approved.path("nextCursor").asString())));
        assertEquals(20, older.path("items").size()); assertFalse(older.path("hasMore").asBoolean());
        var indexes = jdbc.queryForList("EXPLAIN SELECT case_id FROM mk_approval_case WHERE tenant_id=? AND status='OPEN' ORDER BY created_at DESC,case_id DESC LIMIT 26", tenant);
        assertTrue(indexes.toString().contains("ix_approval_tenant_status_created_case"));
    }

    @Test void immutableCursorSurvivesStatusChangesAndNewerInsertions() throws Exception {
        seed("stable", "org-a", "shop-a", "OPEN", 6, EARLY);
        var first = body(send(request("/api/v1/approvals/page?status=OPEN&limit=2")));
        String cursor = first.path("nextCursor").asString(); assertEquals("stable-004", cursor);
        jdbc.update("UPDATE mk_approval_case SET status='APPROVED',updated_at=? WHERE tenant_id=? AND case_id=?", LATE, tenant, cursor);
        seed("newer", "org-a", "shop-a", "OPEN", 1, LATE);
        var next = body(send(request("/api/v1/approvals/page?status=OPEN&limit=100&cursor=" + cursor)));
        assertEquals(4, next.path("items").size()); assertEquals("stable-003", next.path("items").get(0).path("caseId").asString());
        var refreshed = body(send(request("/api/v1/approvals/page?status=OPEN&limit=2")));
        assertEquals("newer-000", refreshed.path("items").get(0).path("caseId").asString());
    }

    @Test void rejectsInvalidOrForeignCursorsAndRechecksReadPermissions() throws Exception {
        seed("visible", "org-a", "shop-a", "OPEN", 2, EARLY);
        seed("hidden", "org-b", "shop-b", "OPEN", 1, EARLY);
        seed("org-wide", "org-a", null, "REJECTED", 1, EARLY);
        String visibleNo = "MKT-APR-261101-0000000001";
        String hiddenNo = "MKT-APR-261101-0000000002";
        jdbc.update("UPDATE mk_approval_case SET business_no=? WHERE tenant_id=? AND case_id='visible-000'", visibleNo, tenant);
        jdbc.update("UPDATE mk_approval_case SET business_no=? WHERE tenant_id=? AND case_id='hidden-000'", hiddenNo, tenant);
        String lookup = "/api/v1/approvals/page?number=";
        var byNumber = body(send(request(lookup + visibleNo))).path("items");
        assertEquals(1, byNumber.size());
        assertEquals(visibleNo, byNumber.get(0).path("businessNo").asString());
        assertEquals(byNumber, body(send(request(lookup + "visible-000"))).path("items"));
        assertTrue(body(send(request(lookup + hiddenNo))).path("items").isEmpty());
        for (String query : List.of("limit=0", "limit=101", "limit=-1", "status=", "status=unknown", "cursor=", "cursor=%20", "cursor=" + "x".repeat(65))) {
            assertCode(send(request("/api/v1/approvals/page?" + query)), 400, "APPROVAL_QUERY_INVALID");
        }
        for (String id : List.of("missing", "hidden-000")) assertCode(send(request("/api/v1/approvals/page?cursor=" + id)), 400, "APPROVAL_CURSOR_INVALID");
        assertCode(send(request("/api/v1/approvals/page?cursor=visible-000").setHeader("X-Dev-Tenant-Id", "foreign-tenant")), 400, "APPROVAL_CURSOR_INVALID");
        assertCode(send(request("/api/v1/approvals/page?cursor=visible-000").setHeader("X-Dev-Shop-Ids", "shop-b")), 400, "APPROVAL_CURSOR_INVALID");
        assertEquals(403, send(request("/api/v1/approvals/page").setHeader("X-Dev-Permissions", "campaign:read")).statusCode());
        assertEquals(200, send(request("/api/v1/approvals/page").setHeader("X-Dev-Permissions", "approval:business")).statusCode());
        assertEquals(0, body(send(request("/api/v1/approvals/page").setHeader("X-Dev-Tenant-Id", "foreign-tenant"))).path("items").size());
        assertEquals(3, body(send(request("/api/v1/approvals/page"))).path("items").size());
        // 数据库默认不区分大小写，但权限标识必须保持TenantScope的精确匹配语义。
        assertEquals(0, body(send(request("/api/v1/approvals/page").setHeader("X-Dev-Organization-Ids", "ORG-A"))).path("items").size());
        assertEquals(1, body(send(request("/api/v1/approvals/page").setHeader("X-Dev-Shop-Ids", "SHOP-A"))).path("items").size());
        assertEquals(4, body(send(request("/api/v1/approvals/page").setHeader("X-Dev-Organization-Ids", "*").setHeader("X-Dev-Shop-Ids", "*"))).path("items").size());
    }

    /** 数据仅写入Testcontainers隔离库，每个用例独立租户，不触碰共享业务库。 */
    private void seed(String prefix, String organization, String shop, String status, int count, String time) {
        jdbc.update("INSERT INTO mk_campaign (tenant_id,campaign_id,name,objective,status,organization_id,shop_id,created_at,updated_at,campaign_type) VALUES (?,?,?,?,?,?,?,?,?,?)", tenant,prefix,prefix,"pagination","DRAFT",organization,shop,time,time,"STANDARD");
        jdbc.update("INSERT INTO mk_definition_version (tenant_id,definition_id,campaign_id,version_no,dialect,graph_json,semantic_hash,status,created_by,created_at,updated_at) VALUES (?,?,?,1,'OFFER_DECISION_DAG',?,?, 'VALIDATED','author',?,?)",tenant,prefix,prefix,"{}",prefix,time,time);
        for (int i=0;i<count;i++) jdbc.update("INSERT INTO mk_approval_case (tenant_id,case_id,definition_id,definition_version,submitted_by,required_roles,status,created_at,updated_at) VALUES (?,?,?,1,'author','BUSINESS',?,?,?)",tenant,prefix+'-'+String.format("%03d",i),prefix,status,time,time);
    }
    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(20))
                .header("X-Dev-Tenant-Id",tenant).header("X-Dev-Actor-Id","reviewer").header("X-Dev-Organization-Ids","org-a")
                .header("X-Dev-Shop-Ids","shop-a").header("X-Dev-Permissions","definition:read").GET();
    }
    private HttpResponse<String> send(HttpRequest.Builder request) throws Exception { return http.send(request.build(),HttpResponse.BodyHandlers.ofString()); }
    private JsonNode body(HttpResponse<String> response) { assertEquals(200,response.statusCode(),response.body());return mapper.readTree(response.body()); }
    private void assertCode(HttpResponse<String> response,int status,String code) { assertEquals(status,response.statusCode(),response.body());assertEquals(code,mapper.readTree(response.body()).path("code").asString()); }
}
