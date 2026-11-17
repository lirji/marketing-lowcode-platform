package com.acme.marketing.measurement;

import static org.junit.jupiter.api.Assertions.*;

import com.acme.marketing.contracts.event.MarketingFact;
import com.acme.marketing.contracts.event.MeasurementProjectionDelta;
import com.acme.marketing.measurement.application.MeasurementProjectionStore;
import com.acme.marketing.testsupport.MySqlIntegrationTest;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 隔离真实MySQL验证活动筛选不会混入其它活动、租户或冲正前的金额。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CampaignMeasurementIntegrationTest extends MySqlIntegrationTest {
    @LocalServerPort int port;
    @Autowired MeasurementProjectionStore projection;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    private final String tenant = "filter-" + UUID.randomUUID();
    private final HttpClient http = HttpClient.newHttpClient();
    private static final Instant FROM = Instant.parse("2026-11-17T00:00:00Z");
    private static final Instant TO = FROM.plusSeconds(7200);

    @Test void filterPreservesCorrectionsTimeBoundsAndTenantIsolation() throws Exception {
        seed(tenant, "a", "a-old", 1, 1000, 100, FROM.plusSeconds(1));
        seed(tenant, "a", "a-reverse", -1, -1000, -100, FROM.plusSeconds(1));
        seed(tenant, "a", "a-new", 1, 700, 70, FROM.plusSeconds(2));
        seed(tenant, "b", "b", 1, 2000, 200, FROM.plusSeconds(2));
        seed(tenant, "", "unassigned", 1, 3000, 300, FROM.plusSeconds(2));
        seed("other-" + tenant, "a", "other", 1, 9000, 900, FROM.plusSeconds(2));
        seed(tenant, "a", "after", 1, 8000, 800, TO);
        seed(tenant, "a", "before", 1, 8000, 800, FROM.minusMillis(1));
        var filtered = read("dashboard", "a");
        assertEquals("a", filtered.path("campaignId").asString());
        assertEquals(700, filtered.path("attributedRevenueMinor").asLong());
        assertEquals(70, filtered.path("fundingCostMinor").asLong());
        assertEquals(1, filtered.path("counts").path("CONVERSION").asInt());
        assertEquals(2000, read("dashboard", "b").path("attributedRevenueMinor").asLong());
        var all = read("dashboard", null);
        assertEquals("", all.path("campaignId").asString());
        assertEquals(5700, all.path("attributedRevenueMinor").asLong());
        var series = read("series", "a");
        assertEquals("a", series.path("campaignId").asString());
        assertEquals(2, series.path("points").size());
        assertEquals(700, series.path("points").get(0).path("revenueMinor").asLong());
        assertEquals(0, series.path("points").get(1).path("conversions").asLong());
        assertEquals(0, read("dashboard", "a' OR 1=1 --").path("attributedRevenueMinor").asLong());
        assertEquals(0, read("dashboard", "missing").path("attributedRevenueMinor").asLong());
        assertTrue(read("dashboard", "missing").path("counts").isEmpty());
    }

    @Test void invalidFilterAndPermissionDoNotFallBackToUnfilteredStatistics() throws Exception {
        for (String resource : List.of("dashboard", "series")) {
            for (String invalid : List.of("", " ", "a ", "x".repeat(129), "a\nb")) {
                var response = send(resource, invalid, "measurement:read");
                assertEquals(400, response.statusCode(), response.body());
                assertEquals("MEASUREMENT_CAMPAIGN_FILTER_INVALID", mapper.readTree(response.body()).path("code").asString());
            }
            assertEquals(403, send(resource, "a", "campaign:read").statusCode());
        }
    }

    @Test void campaignRangeIndexIsAvailableToTheActualQueryPlanner() {
        var rows = jdbc.queryForList("EXPLAIN SELECT SUM(revenue_delta_minor) FROM mk_dashboard_projection_delta WHERE tenant_id=? AND campaign_id=? AND occurred_at>=? AND occurred_at<?", tenant, "a", FROM.toString(), TO.toString());
        assertTrue(String.valueOf(rows.getFirst().get("possible_keys")).contains("ix_dashboard_projection_campaign_range"));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM information_schema.statistics WHERE table_schema=database() AND table_name='mk_dashboard_projection_delta' AND index_name='ix_dashboard_projection_campaign_range' AND seq_in_index=2 AND column_name='campaign_id'", Integer.class));
    }

    private void seed(String tenantId, String campaignId, String id, long count, long revenue, long cost, Instant at) {
        projection.apply(new MeasurementProjectionDelta("MEASUREMENT_PROJECTION_DELTA", id, tenantId, id, id, 1,
                count == 1 ? MeasurementProjectionDelta.Operation.APPLY : MeasurementProjectionDelta.Operation.REVERSE,
                MarketingFact.Type.CONVERSION, "business-" + id, campaignId, "", "", "masked-test-subject", count, revenue, cost,
                at.toEpochMilli(), at.toEpochMilli()), MeasurementProjectionStore.ProjectionSource.direct());
    }
    private JsonNode read(String resource, String campaignId) throws Exception {
        var response = send(resource, campaignId, "measurement:read");
        assertEquals(200, response.statusCode(), response.body());
        return mapper.readTree(response.body());
    }
    private HttpResponse<String> send(String resource, String campaignId, String permissions) throws Exception {
        String query = "?from=" + FROM + "&to=" + TO + (resource.equals("series") ? "&granularity=hour" : "")
                + (campaignId == null ? "" : "&campaignId=" + URLEncoder.encode(campaignId, StandardCharsets.UTF_8));
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/measurements/" + resource + query))
                .header("X-Dev-Tenant-Id", tenant).header("X-Dev-Organization-Ids", "test-org").header("X-Dev-Shop-Ids", "test-shop")
                .header("X-Dev-Actor-Id", "tester").header("X-Dev-Permissions", permissions).GET().build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
