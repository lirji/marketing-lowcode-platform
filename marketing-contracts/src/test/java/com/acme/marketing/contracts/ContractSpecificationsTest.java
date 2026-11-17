package com.acme.marketing.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

class ContractSpecificationsTest {
    private static final Path RESOURCES = Path.of("src/main/resources").toAbsolutePath().normalize();
    private static final Set<String> API_PATHS = Set.of(
            "/api/v1/client-errors",
            "/api/v1/campaigns",
            "/api/v1/campaigns/{campaignId}/release-bundles",
            "/api/v1/campaigns/{campaignId}/release-bundles/latest",
            "/api/v1/campaigns/{campaignId}/release-bundles/versions/{version}",
            "/api/v1/campaigns/{campaignId}/release-bundles/versions/{version}/manifest",
            "/api/v1/campaigns/{campaignId}/release-bundles/versions/{version}/acks",
            "/api/v1/campaigns/{campaignId}/release-bundles/versions/{version}/readiness",
            "/api/v1/releases/{manifestId}/rollback-candidates",
            "/api/v1/referral-rewards/{rewardId}:reevaluate",
            "/api/v1/referral-campaigns/{campaignId}/participants",
            "/api/v1/referral-campaigns/{campaignId}/relations",
            "/api/v1/referral-campaigns/{campaignId}/rewards",
            "/api/v1/referral-campaigns/{campaignId}/summary", "/api/v1/definitions",
            "/api/v1/definitions/latest", "/api/v1/definitions/{definitionId}/versions/{version}",
            "/api/v1/definitions/{definitionId}/versions/{version}:validate",
            "/api/v1/definitions/{definitionId}/versions/{version}:simulate",
            "/api/v1/definitions/{definitionId}/versions/{version}:simulate-journey",
            "/api/v1/definitions/{definitionId}/versions/{version}:submit",
            "/api/v1/definitions/{definitionId}/versions/{version}/terms",
            "/api/v1/approvals", "/api/v1/approvals/page", "/api/v1/approvals/{caseId}/decisions", "/api/v1/registries/nodes",
            "/api/v1/releases", "/api/v1/releases/page", "/api/v1/releases/{manifestId}", "/api/v1/releases/{manifestId}:ack",
            "/api/v1/releases/{manifestId}:activate", "/api/v1/releases/{manifestId}:rollback",
            "/api/v1/releases/desired", "/api/v1/releases/kill-switches/{namespace}",
            "/api/v1/compile", "/api/v1/dmn/analyze", "/api/v1/campaigns/{campaignId}/dmn-drafts",
            "/api/v1/campaigns/{campaignId}/dmn-drafts/latest", "/api/v1/campaigns/{campaignId}/dmn-drafts/versions/{version}", "/api/v1/dmn/evaluate", "/api/v1/artifacts/{artifactId}", "/api/v1/fields", "/api/v1/audiences", "/api/v1/audiences/page",
            "/api/v1/audiences/{segmentId}/versions/{version}:preview",
            "/api/v1/audiences/{segmentId}/versions/{version}/snapshots",
            "/api/v1/audiences/snapshots/{snapshotId}/memberships",
            "/api/v1/audiences/snapshots/{snapshotId}/memberships/{subjectToken}",
            "/api/v1/decisions:evaluate", "/api/v1/decisions:batch-evaluate",
            "/api/v1/decisions/bundle:evaluate", "/api/v1/decisions/bundle:batch-evaluate", "/api/v1/decisions/bundle-participations",
            "/api/v1/decisions/traces/requests/{requestId}", "/api/v1/decisions/traces/orders/{orderId}",
            "/api/v1/journey-runtime/release-bundle-directives",
            "/api/v1/journey-runtime/release-bundle-directives/{campaignId}/latest",
            "/api/v1/decisions/runtime/release-bundle-directives",
            "/api/v1/decisions/runtime/release-bundle-directives/{campaignId}/latest",
            "/api/v1/decisions/runtime/manifest", "/api/v1/decisions/runtime/release-bundles:prepare", "/api/v1/decisions/runtime/activation",
            "/api/v1/decisions/runtime/ack", "/api/v1/decisions/runtime/audience-membership",
            "/api/v1/funding/accounts", "/api/v1/funding/accounts/{resourceKey}:advance-fence",
            "/api/v1/benefits", "/api/v1/benefits/catalog/page", "/api/v1/benefit-skus", "/api/v1/benefits/{benefitId}",
            "/api/v1/award-intents",
                "/api/v1/award-intents/{intentId}/fulfillment",
                "/api/v1/award-intents/{intentId}/fulfillment:refresh", "/internal/v1/award-intents",
            "/internal/v1/benefits:assert-releasable",
            "/api/v1/promotion-applications", "/api/v1/promotion-applications/{applicationId}:confirm",
            "/api/v1/promotion-applications/{applicationId}:cancel",
            "/api/v1/promotion-applications/{applicationId}:refund",
            "/api/v1/promotion-applications/{applicationId}:reverse",
            "/api/v1/promotion-applications:expire", "/api/v1/funding/reconciliation",
            "/api/v1/events/sources", "/api/v1/events", "/api/v1/quarantine",
            "/api/v1/quarantine/{quarantineId}:replay", "/api/v1/journey-runtime/manifest", "/api/v1/journey-runtime/release-bundles:prepare",
            "/api/v1/journey-runtime/activation", "/api/v1/journeys/{journeyId}/versions/{version}",
            "/api/v1/enrollments", "/api/v1/enrollments/{enrollmentId}/signals",
            "/api/v1/enrollments/{enrollmentId}", "/api/v1/journeys/{journeyId}:migrate",
            "/api/v1/consents", "/api/v1/consents/suppressions", "/api/v1/contacts/frequency-policies",
            "/api/v1/templates", "/api/v1/templates/catalog/page", "/api/v1/contacts", "/api/v1/contacts/{contactKey}",
            "/api/v1/providers/callbacks",
            "/api/v1/experiments", "/api/v1/experiments/{experimentId}/versions/{version}:assign",
            "/api/v1/experiments/{experimentId}/versions/{version}:check-srm",
            "/api/v1/measurements/facts", "/api/v1/measurements/watermarks",
            "/api/v1/measurements/dashboard", "/api/v1/measurements/attribution/{conversionEventId}",
            "/api/v1/measurements/series", "/api/v1/measurements/attribution:recompute",
            "/api/v1/traces", "/api/v1/traces/requests/{requestId}", "/api/v1/traces/orders/{orderId}");

    private static final Set<String> TOPICS = Set.of(
            "mk.profile.change.v1", "mk.audience.membership.v1", "mk.journey.signal.v1",
            "mk.journey.output.v1", "mk.engagement.command.v1", "mk.benefit.command.v1",
            "mk.engagement.event.v1", "mk.benefit.event.v1", "mk.marketing.fact.v1",
            "mk.measurement.projection.v1", "mk.platform.events.v1", "mk.release.activation.v1",
            "mk.release.kill-switch.v1", "marketing.award-expected.v1");

    @Test
    void bundleTenantSchemaMatchesTheActualValueObjectWireShape() throws IOException {
        var document = yaml(RESOURCES.resolve("openapi/marketing-api.yaml"));
        var schemas = map(map(document.get("components")).get("schemas"));
        var property = map(map(map(schemas.get("ReleaseBundleManifest")).get("properties")).get("tenantId"));
        var schema = property.containsKey("$ref") ? map(schemas.get(String.valueOf(property.get("$ref")).substring("#/components/schemas/".length()))) : property;
        var wire = new tools.jackson.databind.ObjectMapper().readTree(new tools.jackson.databind.ObjectMapper()
                .writeValueAsString(new com.acme.marketing.platform.identity.TenantId("tenant-wire")));
        assertTrue(wire.isObject());
        assertEquals("object", schema.get("type"));
        assertEquals(Set.of("value"), map(schema.get("properties")).keySet());
        assertEquals(List.of("value"), schema.get("required"));
        assertEquals("tenant-wire", wire.path("value").asString());
        for (String resource : List.of("schema/artifact/release-manifest.schema.json", "schema/events/release-activation.schema.json")) {
            var tenant = new tools.jackson.databind.ObjectMapper().readTree(Files.readString(RESOURCES.resolve(resource)))
                    .path("properties").path("tenantId");
            assertEquals("object", tenant.path("type").asString(), resource);
            assertEquals("string", tenant.path("properties").path("value").path("type").asString(), resource);
            assertEquals("value", tenant.path("required").get(0).asString(), resource);
        }
    }

    @Test
    void participationSchemaCoversEveryRecordFieldAndUsesTheExistingTenantWireType() throws IOException {
        var document = yaml(RESOURCES.resolve("openapi/marketing-api.yaml"));
        var schemas = map(map(document.get("components")).get("schemas"));
        var schema = map(schemas.get("ReleaseBundleParticipation"));
        var fields = map(schema.get("properties"));
        var names = java.util.Arrays.stream(com.acme.marketing.contracts.release.ReleaseBundleParticipation.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName).collect(java.util.stream.Collectors.toSet());
        assertEquals(names, fields.keySet());
        assertEquals(names, new HashSet<>((List<?>) schema.get("required")));
        assertEquals(false, schema.get("additionalProperties"));
        assertEquals("#/components/schemas/TenantId", map(fields.get("tenantId")).get("$ref"));
        assertEquals(128, map(fields.get("participationId")).get("maxLength"));
        assertEquals(1, map(fields.get("activationSequence")).get("minimum"));
        assertEquals(1, map(fields.get("bundleVersion")).get("minimum"));
        var json = new tools.jackson.databind.ObjectMapper().readTree(Files.readString(
                RESOURCES.resolve("schema/events/release-bundle-participation.schema.json")));
        assertEquals(names, json.path("properties").propertyNames());
        Set<String> required = new HashSet<>(); json.path("required").forEach(value -> required.add(value.asString()));
        assertEquals(names, required);
        assertEquals("object", json.path("properties").path("tenantId").path("type").asString());
    }

    @Test
    void bundleDecisionContextIsOptionalAndReferencesTheSameParticipationContract() throws IOException {
        var schemas = map(map(yaml(RESOURCES.resolve("openapi/marketing-api.yaml")).get("components")).get("schemas"));
        var request = map(schemas.get("DecisionRequest"));
        assertFalse(((List<?>) request.get("required")).contains("bundleContext"));
        assertEquals("#/components/schemas/BundleDecisionContext", map(map(request.get("properties")).get("bundleContext")).get("$ref"));
        var bound = (List<?>) map(schemas.get("BundleDecisionRequest")).get("allOf");
        assertEquals(List.of("bundleContext"), map(bound.get(1)).get("required"));
        var unbound = (List<?>) map(schemas.get("UnboundDecisionRequest")).get("allOf");
        assertEquals(List.of("bundleContext"), map(map(unbound.get(1)).get("not")).get("required"));
        var context = map(schemas.get("BundleDecisionContext"));
        assertEquals(Set.of("campaignId", "participationId", "participation"), new HashSet<>((List<?>) context.get("required")));
        assertEquals("#/components/schemas/ReleaseBundleParticipation", map(map(context.get("properties")).get("participation")).get("$ref"));
        assertEquals(false, context.get("additionalProperties"));
    }

    @Test
    void admissionContractCannotSelectTenantOrReleaseAndRequiresMachinePermission() throws IOException {
        var document = yaml(RESOURCES.resolve("openapi/marketing-api.yaml"));
        var operation = map(map(map(document.get("paths")).get("/api/v1/decisions/bundle-participations")).get("post"));
        assertEquals("bundle:admit", operation.get("x-required-permission"));
        var schema = map(map(map(document.get("components")).get("schemas")).get("ReleaseBundleAdmissionRequest"));
        var names = java.util.Arrays.stream(com.acme.marketing.contracts.release.ReleaseBundleAdmissionRequest.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName).collect(java.util.stream.Collectors.toSet());
        assertEquals(names, map(schema.get("properties")).keySet());
        assertEquals(names, new HashSet<>((List<?>) schema.get("required")));
        assertEquals(false, schema.get("additionalProperties"));
    }

    @Test
    void openApiMatchesTheImplementedR1Surface() throws IOException {
        Path source = RESOURCES.resolve("openapi/marketing-api.yaml");
        Map<String, Object> document = yaml(source);
        assertEquals("3.1.0", document.get("openapi"));
        Map<String, Object> paths = map(document.get("paths"));
        assertEquals(API_PATHS, paths.keySet());
        Set<String> operationIds = new HashSet<>();
        for (Object pathItem : paths.values()) {
            for (Map.Entry<String, Object> operation : map(pathItem).entrySet()) {
                if (!Set.of("get", "post", "put", "patch", "delete").contains(operation.getKey())) continue;
                String id = String.valueOf(map(operation.getValue()).get("operationId"));
                assertFalse(id.isBlank() || "null".equals(id), "operationId is required");
                assertTrue(operationIds.add(id), () -> "duplicate operationId: " + id);
            }
        }
        assertFalse(Files.readString(source).contains("X-Payload-SHA256"));
        verifyExternalReferences(document, source);
    }

    @Test
    void approvalPageContractKeepsLegacyArrayAndBoundedTraversal() throws IOException {
        var document = yaml(RESOURCES.resolve("openapi/marketing-api.yaml"));
        var paths = map(document.get("paths"));
        assertTrue(paths.containsKey("/api/v1/approvals"));
        var operation = map(map(paths.get("/api/v1/approvals/page")).get("get"));
        var parameters = (List<?>) operation.get("parameters");
        var limit = parameters.stream().map(ContractSpecificationsTest::map)
                .filter(parameter -> "limit".equals(parameter.get("name"))).findFirst().orElseThrow();
        assertEquals(100, map(limit.get("schema")).get("maximum"));
        assertEquals(25, map(limit.get("schema")).get("default"));
        var schemas = map(map(document.get("components")).get("schemas"));
        var page = map(schemas.get("ApprovalPage"));
        assertEquals(Set.of("items", "nextCursor", "hasMore", "status"), new HashSet<>((List<?>) page.get("required")));
        assertEquals(100, map(map(page.get("properties")).get("items")).get("maxItems"));
    }

    @Test
    void releasePageContractKeepsLegacyArrayAndBoundedTraversal() throws IOException {
        var document = yaml(RESOURCES.resolve("openapi/marketing-api.yaml"));
        var paths = map(document.get("paths"));
        assertTrue(paths.containsKey("/api/v1/releases"));
        var detail = map(map(paths.get("/api/v1/releases/{manifestId}")).get("get"));
        assertEquals("release:read", detail.get("x-required-permission"));
        assertTrue(map(detail.get("responses")).keySet().containsAll(Set.of("200", "403", "404")));
        var operation = map(map(paths.get("/api/v1/releases/page")).get("get"));
        var parameters = (List<?>) operation.get("parameters");
        var limit = parameters.stream().map(ContractSpecificationsTest::map)
                .filter(parameter -> "limit".equals(parameter.get("name"))).findFirst().orElseThrow();
        assertEquals(100, map(limit.get("schema")).get("maximum"));
        assertEquals(25, map(limit.get("schema")).get("default"));
        var schemas = map(map(document.get("components")).get("schemas"));
        var page = map(schemas.get("ReleasePage"));
        assertEquals(Set.of("items", "nextCursor", "hasMore", "state"), new HashSet<>((List<?>) page.get("required")));
        assertEquals(100, map(map(page.get("properties")).get("items")).get("maxItems"));
    }

    @Test
    void clientDiagnosticsContractForbidsFreeTextAndIdentityFields() throws IOException {
        var document = yaml(RESOURCES.resolve("openapi/marketing-api.yaml"));
        var operation = map(map(map(document.get("paths")).get("/api/v1/client-errors")).get("post"));
        assertTrue(map(operation.get("responses")).keySet().containsAll(Set.of("204", "400", "401", "429", "503")));
        var schema = map(map(map(document.get("components")).get("schemas")).get("ClientErrorEvent"));
        assertEquals(false, schema.get("additionalProperties"));
        assertEquals(Set.of("schemaVersion", "source", "route", "httpStatus", "backendTraceId"), map(schema.get("properties")).keySet());
        assertEquals(Set.of("schemaVersion", "source", "route", "httpStatus"), new HashSet<>((List<?>) schema.get("required")));
        assertEquals(5, ((List<?>) map(map(schema.get("properties")).get("source")).get("enum")).size());
    }

    @Test
    void referralReadContractsResolveAndNeverExposeSubjectProofs() throws IOException {
        Path source=RESOURCES.resolve("openapi/referral-operations.yaml");Map<String,Object> api=yaml(source);
        assertEquals("3.1.0",api.get("openapi"));var paths=map(api.get("paths"));assertEquals(5,paths.size());
        for(var path:paths.values()){var item=map(path);String method=item.containsKey("get")?"get":"post";assertEquals(method.equals("get")?"referral:read":"referral:reevaluate",map(item.get(method)).get("x-required-permission"));}
        var schemas=map(map(api.get("components")).get("schemas"));
        for(String resource:List.of("Participant","Relation","Reward")){
            var fields=map(map(schemas.get(resource)).get("properties"));
            for(String forbidden:List.of("canonicalSubject","beneficiaryKey","subjectCipher","candidateToken","riskAssertion"))assertFalse(fields.containsKey(forbidden));
        }
        var summary=map(map(schemas.get("Summary")).get("properties"));assertEquals("DATABASE_PROJECTION",map(summary.get("consistency")).get("const"));assertTrue(((List<?>)map(summary.get("eventWatermark")).get("type")).contains("null"));
        verifyExternalReferences(api,source);
    }

    @Test
    void awardIntentContractExposesRiskBlockedAndUnavailableSemantics() throws IOException {
        Map<String, Object> document = yaml(RESOURCES.resolve("openapi/marketing-api.yaml"));
        Map<String, Object> schemas = map(map(document.get("components")).get("schemas"));
        Map<String, Object> awardView = map(schemas.get("AwardIntentView"));
        Map<String, Object> properties = map(awardView.get("properties"));

        assertEquals(List.of("PENDING", "SENT", "DEAD", "RISK_BLOCKED"),
                map(properties.get("status")).get("enum"));
        assertEquals(List.of("CHALLENGE", "REVIEW", "REJECT", "UNAVAILABLE"),
                map(properties.get("riskAction")).get("enum"));
        assertEquals(List.of("string", "null"), map(properties.get("deliveryResult")).get("type"));
        assertTrue(((List<?>) awardView.get("required")).contains("deliveryResult"),
                "deliveryResult remains present and is null for RISK_BLOCKED rows");

        Map<String, Object> post = map(map(map(document.get("paths"))
                .get("/internal/v1/award-intents")).get("post"));
        assertTrue(map(post.get("responses")).containsKey("503"));
    }

    @Test
    void releaseOperationsDeclareThePermissionsUsedByTheConsole() throws IOException {
        Map<String, Object> document = yaml(RESOURCES.resolve("openapi/marketing-api.yaml"));
        Map<String, Object> paths = map(document.get("paths"));
        assertEquals("release:read", permission(paths, "/api/v1/releases", "get"));
        assertEquals("release:rollback", permission(paths, "/api/v1/releases/{manifestId}:rollback", "post"));
        assertEquals("release:kill-switch", permission(paths, "/api/v1/releases/kill-switches/{namespace}", "put"));
    }

    @Test
    void asyncApiUsesOnlyProvisionedPrimaryTopicsAndResolvableSchemas() throws IOException {
        Path source = RESOURCES.resolve("asyncapi/marketing-events.yaml");
        Map<String, Object> document = yaml(source);
        assertEquals("3.0.0", document.get("asyncapi"));
        Set<String> addresses = new LinkedHashSet<>();
        map(document.get("channels")).values().forEach(channel ->
                addresses.add(String.valueOf(map(channel).get("address"))));
        assertEquals(TOPICS, addresses);
        verifyExternalReferences(document, source);
    }

    private static Map<String, Object> yaml(Path source) throws IOException {
        LoaderOptions options = new LoaderOptions();
        options.setMaxAliasesForCollections(200);
        options.setCodePointLimit(2_000_000);
        try (InputStream input = Files.newInputStream(source)) {
            return map(new Yaml(new SafeConstructor(options)).load(input));
        }
    }

    private static String permission(Map<String, Object> paths, String path, String method) {
        return String.valueOf(map(map(paths.get(path)).get(method)).get("x-required-permission"));
    }

    private static void verifyExternalReferences(Object node, Path source) {
        if (node instanceof Map<?, ?> values) {
            values.forEach((key, value) -> {
                if ("$ref".equals(key) && value instanceof String reference && !reference.startsWith("#")) {
                    Path target = source.getParent().resolve(reference.split("#", 2)[0]).normalize();
                    assertTrue(target.startsWith(RESOURCES), () -> "reference escapes resources: " + reference);
                    assertTrue(Files.isRegularFile(target), () -> "missing reference: " + reference);
                }
                verifyExternalReferences(value, source);
            });
        } else if (node instanceof List<?> values) {
            values.forEach(value -> verifyExternalReferences(value, source));
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        assertTrue(value instanceof Map<?, ?>, () -> "expected mapping but got " + value);
        return (Map<String, Object>) value;
    }
}
