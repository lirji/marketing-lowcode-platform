package com.acme.marketing.control;

import static org.junit.jupiter.api.Assertions.*;

import com.acme.marketing.testsupport.MySqlIntegrationTest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

/** 真实MySQL排序规则下验证活动列表与TenantScope的精确范围语义一致。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CampaignScopeIntegrationTest extends MySqlIntegrationTest {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    private final String tenant = "campaign-scope-" + UUID.randomUUID();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    @Test void wildcardPermissionsIncludeAllAndPartialScopesWithoutDroppingTypeFilter() throws Exception {
        seed();
        assertEquals(Set.of("a1", "a2", "a-null", "a-empty", "b1", "case-org", "case-shop"), ids(request("*", "*")));
        assertEquals(Set.of("a1", "a2", "a-null", "a-empty", "case-shop"), ids(request("org-a", "*")));
        assertEquals(Set.of("a1", "a-null", "a-empty", "b1", "case-org"), ids(request("*", "shop-a")));
        assertEquals(Set.of("a2"), ids(request("*", "*", "?campaignType=REFERRAL")));
    }

    @Test void concreteAndEmptyScopesPreserveCaseSensitiveIsolation() throws Exception {
        seed();
        assertEquals(Set.of("a1", "a-null", "a-empty"), ids(request("org-a", "shop-a")));
        assertEquals(Set.of("case-org"), ids(request("ORG-A", "shop-a")));
        assertEquals(Set.of("case-shop", "a-null", "a-empty"), ids(request("org-a", "SHOP-A")));
        assertEquals(Set.of("a-null", "a-empty"), ids(request("org-a", "")));
        assertEquals(Set.of(), ids(request("", "*")));
        assertEquals(Set.of(), ids(request("unknown", "*")));
        assertEquals(Set.of(), ids(request("*", "*").setHeader("X-Dev-Tenant-Id", "other-tenant")));
        var denied = send(request("*", "*").setHeader("X-Dev-Permissions", "release:read"));
        assertEquals(403, denied.statusCode()); assertEquals("PERMISSION_DENIED", mapper.readTree(denied.body()).path("code").asString());
    }

    /** 仅写入隔离测试库，每个测试租户独立，避免依赖已有活动。 */
    private void seed() {
        insert("a1", "org-a", "shop-a", "STANDARD"); insert("a2", "org-a", "shop-b", "REFERRAL");
        insert("a-null", "org-a", null, "STANDARD"); insert("a-empty", "org-a", "", "STANDARD");
        insert("b1", "org-b", "shop-a", "STANDARD"); insert("case-org", "ORG-A", "shop-a", "STANDARD");
        insert("case-shop", "org-a", "SHOP-A", "STANDARD");
    }
    private void insert(String id, String org, String shop, String type) {
        jdbc.update("INSERT INTO mk_campaign (tenant_id,campaign_id,name,objective,status,organization_id,shop_id,created_at,updated_at,campaign_type) VALUES (?,?,?,?,?,?,?,?,?,?)",
                tenant, id, id, "scope test", "DRAFT", org, shop, "2026-11-01T00:00:00.000Z", "2026-11-01T00:00:00.000Z", type);
    }
    private HttpRequest.Builder request(String organizations, String shops) { return request(organizations, shops, ""); }
    private HttpRequest.Builder request(String organizations, String shops, String query) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/campaigns"+query)).timeout(Duration.ofSeconds(20))
                .header("X-Dev-Tenant-Id", tenant).header("X-Dev-Actor-Id", "scope-reader").header("X-Dev-Organization-Ids", organizations)
                .header("X-Dev-Shop-Ids", shops).header("X-Dev-Permissions", "campaign:read").GET();
    }
    private HttpResponse<String> send(HttpRequest.Builder request) throws Exception { return http.send(request.build(), HttpResponse.BodyHandlers.ofString()); }
    private Set<String> ids(HttpRequest.Builder request) throws Exception {
        var response = send(request); assertEquals(200, response.statusCode(), response.body());
        Set<String> ids = new HashSet<>(); for (var item : mapper.readTree(response.body())) ids.add(item.path("id").asString()); return ids;
    }
}
