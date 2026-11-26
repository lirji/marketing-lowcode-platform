package com.acme.marketing.control;

import static org.junit.jupiter.api.Assertions.*;

import com.acme.marketing.testsupport.MySqlIntegrationTest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import com.acme.marketing.contracts.release.ArtifactReference;
import com.acme.marketing.contracts.release.ReleaseManifest;
import com.acme.marketing.platform.identity.TenantId;
import com.acme.marketing.control.application.ControlRepository;
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
class ReleasePaginationIntegrationTest extends MySqlIntegrationTest {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired ControlRepository repository;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final String tenant = "release-page-" + UUID.randomUUID();
    private static final String EARLY = "2026-11-01T00:00:00.000Z";
    private static final String LATE = "2026-11-02T00:00:00.000Z";

    @Test void filtersScopeAndStatusBeforeLimitAndTraversesAllTiedRows() throws Exception {
        seed("org-hidden", "org-b", "shop-a", "STAGED", 105, LATE);
        seed("shop-hidden", "org-a", "shop-b", "STAGED", 105, LATE);
        seed("closed", "org-a", "shop-a", "ACTIVE", 120, LATE);
        seed("visible", "org-a", "shop-a", "STAGED", 110, EARLY);
        var legacy = body(send(request("/api/v1/releases")));
        assertEquals(50, legacy.size());
        for (var row : legacy) assertTrue(row.path("manifest").path("manifestId").asString().startsWith("closed-"));
        List<String> ids = new ArrayList<>(); String cursor = null;
        do {
            var page = body(send(request("/api/v1/releases/page?state=STAGED&limit=25" + (cursor == null ? "" : "&cursor=" + cursor))));
            assertEquals("STAGED", page.path("state").asString());
            assertTrue(page.path("items").size() <= 25);
            for (var row : page.path("items")) ids.add(row.path("manifest").path("manifestId").asString());
            assertEquals(page.path("hasMore").asBoolean(), !page.path("nextCursor").isNull());
            cursor = page.path("nextCursor").isNull() ? null : page.path("nextCursor").asString();
        } while (cursor != null);
        assertEquals(110, ids.size()); assertEquals(110, new HashSet<>(ids).size());
        assertEquals("visible-109", ids.getFirst()); assertEquals("visible-000", ids.getLast());
        var approved = body(send(request("/api/v1/releases/page?state=ACTIVE&limit=100")));
        assertEquals(100, approved.path("items").size()); assertTrue(approved.path("hasMore").asBoolean());
        var older = body(send(request("/api/v1/releases/page?state=ACTIVE&cursor=" + approved.path("nextCursor").asString())));
        assertEquals(20, older.path("items").size()); assertFalse(older.path("hasMore").asBoolean());
        var indexes = jdbc.queryForList("EXPLAIN SELECT manifest_id FROM mk_release_manifest WHERE tenant_id=? AND state_name='STAGED' ORDER BY created_at DESC,manifest_id DESC LIMIT 26", tenant);
        assertTrue(indexes.toString().contains("ix_release_tenant_state_created_manifest"));
    }

    @Test void immutableCursorSurvivesStatusChangesAndNewerInsertions() throws Exception {
        seed("stable", "org-a", "shop-a", "STAGED", 6, EARLY);
        var first = body(send(request("/api/v1/releases/page?state=STAGED&limit=2")));
        String cursor = first.path("nextCursor").asString(); assertEquals("stable-004", cursor);
        jdbc.update("UPDATE mk_release_manifest SET state_name='ACTIVE' WHERE tenant_id=? AND manifest_id=?", tenant, cursor);
        seed("newer", "org-a", "shop-a", "STAGED", 1, LATE);
        var next = body(send(request("/api/v1/releases/page?state=STAGED&limit=100&cursor=" + cursor)));
        assertEquals(4, next.path("items").size()); assertEquals("stable-003", next.path("items").get(0).path("manifest").path("manifestId").asString());
        var refreshed = body(send(request("/api/v1/releases/page?state=STAGED&limit=2")));
        assertEquals("newer-000", refreshed.path("items").get(0).path("manifest").path("manifestId").asString());
    }

    @Test void rejectsInvalidOrForeignCursorsAndRechecksReadPermissions() throws Exception {
        seed("visible", "org-a", "shop-a", "STAGED", 2, EARLY);
        seed("hidden", "org-b", "shop-b", "STAGED", 1, EARLY);
        seed("org-wide", "org-a", null, "REVOKED", 1, EARLY);
        for (String query : List.of("limit=0", "limit=101", "limit=-1", "state=", "state=unknown", "cursor=", "cursor=%20", "cursor=" + "x".repeat(65))) {
            assertCode(send(request("/api/v1/releases/page?" + query)), 400, "RELEASE_QUERY_INVALID");
        }
        for (String id : List.of("missing", "hidden-000")) assertCode(send(request("/api/v1/releases/page?cursor=" + id)), 400, "RELEASE_CURSOR_INVALID");
        assertCode(send(request("/api/v1/releases/page?cursor=visible-000").setHeader("X-Dev-Tenant-Id", "foreign-tenant")), 400, "RELEASE_CURSOR_INVALID");
        assertCode(send(request("/api/v1/releases/page?cursor=visible-000").setHeader("X-Dev-Shop-Ids", "shop-b")), 400, "RELEASE_CURSOR_INVALID");
        assertEquals(403, send(request("/api/v1/releases/page").setHeader("X-Dev-Permissions", "campaign:read")).statusCode());
        assertEquals(200, send(request("/api/v1/releases/page").setHeader("X-Dev-Permissions", "release:read")).statusCode());
        assertEquals(0, body(send(request("/api/v1/releases/page").setHeader("X-Dev-Tenant-Id", "foreign-tenant"))).path("items").size());
        assertEquals(3, body(send(request("/api/v1/releases/page"))).path("items").size());
        // 数据库默认不区分大小写，但权限标识必须保持TenantScope的精确匹配语义。
        assertEquals(0, body(send(request("/api/v1/releases/page").setHeader("X-Dev-Organization-Ids", "ORG-A"))).path("items").size());
        assertEquals(1, body(send(request("/api/v1/releases/page").setHeader("X-Dev-Shop-Ids", "SHOP-A"))).path("items").size());
        assertEquals(4, body(send(request("/api/v1/releases/page").setHeader("X-Dev-Organization-Ids", "*").setHeader("X-Dev-Shop-Ids", "*"))).path("items").size());
    }

    @Test void readsOlderManifestAndFreshAckWithoutListWindowOrScopeBypass() throws Exception {
        seed("older", "org-a", "shop-a", "STAGED", 1, EARLY);
        seed("recent", "org-a", "shop-a", "ACTIVE", 60, LATE);
        seed("hidden", "org-b", "shop-b", "STAGED", 1, EARLY);
        assertFalse(body(send(request("/api/v1/releases"))).toString().contains("older-000"));
        String path = "/api/v1/releases/older-000";
        String number = "MKT-MNF-261126-0000000001";
        jdbc.update("UPDATE mk_release_manifest SET business_no=? WHERE tenant_id=? AND manifest_id='older-000'", number, tenant);
        assertEquals(body(send(request(path))), body(send(request("/api/v1/releases/" + number))));
        assertCode(send(request("/api/v1/releases/" + number).setHeader("X-Dev-Tenant-Id", "other-tenant")), 404, "MANIFEST_NOT_FOUND");
        assertEquals("older-000", body(send(request(path))).path("manifest").path("manifestId").asString());
        assertEquals(0, body(send(request(path))).path("readyReplicas").asInt());
        jdbc.update("INSERT INTO mk_runtime_ack (tenant_id,manifest_id,runtime_id,status_name,build_digest,supported_abis,warmed_artifact_ids,capacity_value,acknowledged_at,signature_key_id,signature_value) VALUES (?,?,?,'READY','test','[]','[]',7,?,'test','test')",
                tenant, "older-000", "runtime-test", Instant.now().toString());
        var ready = body(send(request(path)));
        assertEquals(1, ready.path("readyReplicas").asInt()); assertEquals(7, ready.path("readyCapacity").asInt());
        assertEquals(403, send(request(path).setHeader("X-Dev-Permissions", "campaign:read")).statusCode());
        assertCode(send(request(path).setHeader("X-Dev-Organization-Ids", "ORG-A")), 400, "INVALID_ARGUMENT");
        assertCode(send(request(path).setHeader("X-Dev-Shop-Ids", "shop-b")), 400, "INVALID_ARGUMENT");
        assertCode(send(request("/api/v1/releases/hidden-000")), 400, "INVALID_ARGUMENT");
        assertCode(send(request(path).setHeader("X-Dev-Tenant-Id", "other-tenant")), 404, "MANIFEST_NOT_FOUND");
        assertCode(send(request("/api/v1/releases/absent")), 404, "MANIFEST_NOT_FOUND");
        assertEquals(200, send(request("/api/v1/releases/hidden-000").setHeader("X-Dev-Organization-Ids", "*").setHeader("X-Dev-Shop-Ids", "*")).statusCode());
    }

    /** 数据仅写入Testcontainers隔离库，每个用例独立租户，不触碰共享业务库。 */
    private void seed(String prefix, String organization, String shop, String state, int count, String time) {
        jdbc.update("INSERT INTO mk_campaign (tenant_id,campaign_id,name,objective,status,organization_id,shop_id,created_at,updated_at,campaign_type) VALUES (?,?,?,?,?,?,?,?,?,?)", tenant,prefix,prefix,"pagination","DRAFT",organization,shop,time,time,"STANDARD");
        jdbc.update("INSERT INTO mk_definition_version (tenant_id,definition_id,campaign_id,version_no,dialect,graph_json,semantic_hash,status,created_by,created_at,updated_at) VALUES (?,?,?,1,'OFFER_DECISION_DAG',?,?, 'VALIDATED','author',?,?)",tenant,prefix,prefix,"{}",prefix,time,time);
        for (int i=0;i<count;i++) {
            String id = prefix + '-' + String.format("%03d", i);
            var artifact = new ArtifactReference("artifact-"+id,"JOURNEY_PLAN","memory:test","sha256:"+"a".repeat(64),"sha256:"+"b".repeat(64),"key","signature","marketing-journey-plan/1",prefix,1);
            var at = Instant.parse(time);
            var manifest = new ReleaseManifest(id,new TenantId(tenant),"test","cell-a","journey",prefix,i+1,i+1,List.of(),List.of(),List.of(artifact),Map.of(),0,at,at.plusSeconds(3600),"author",List.of(),at,"fixture-signature");
            repository.saveManifest(new ControlRepository.ManifestWrite(tenant,id,"test","cell-a","journey",prefix,i+1,state,mapper.writeValueAsString(manifest),time, null));
        }
    }
    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(20))
                .header("X-Dev-Tenant-Id",tenant).header("X-Dev-Actor-Id","reviewer").header("X-Dev-Organization-Ids","org-a")
                .header("X-Dev-Shop-Ids","shop-a").header("X-Dev-Permissions","release:read").GET();
    }
    private HttpResponse<String> send(HttpRequest.Builder request) throws Exception { return http.send(request.build(),HttpResponse.BodyHandlers.ofString()); }
    private JsonNode body(HttpResponse<String> response) { assertEquals(200,response.statusCode(),response.body());return mapper.readTree(response.body()); }
    private void assertCode(HttpResponse<String> response,int state,String code) { assertEquals(state,response.statusCode(),response.body());assertEquals(code,mapper.readTree(response.body()).path("code").asString()); }
}
