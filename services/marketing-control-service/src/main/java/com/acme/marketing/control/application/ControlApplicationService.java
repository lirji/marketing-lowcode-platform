package com.acme.marketing.control.application;

import static com.acme.marketing.platform.time.SqlTime.format;

import com.acme.marketing.control.domain.ApprovalCase;
import com.acme.marketing.control.domain.Campaign;
import com.acme.marketing.control.domain.DefinitionVersion;
import com.acme.marketing.lowcode.compiler.CanonicalGraphHasher;
import com.acme.marketing.lowcode.model.Dialect;
import com.acme.marketing.lowcode.model.GraphDefinition;
import com.acme.marketing.lowcode.validation.GraphValidator;
import com.acme.marketing.lowcode.validation.ValidationIssue;
import com.acme.marketing.platform.error.ConflictException;
import com.acme.marketing.platform.error.NotFoundException;
import com.acme.marketing.platform.identity.TenantScope;
import com.acme.marketing.platform.web.TenantContextHolder;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ControlApplicationService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final DefaultNodeRegistry registry;
    private final GraphSimulationService simulationService;
    private final GovernanceValidator governanceValidator;
    private final CanonicalGraphHasher hasher = new CanonicalGraphHasher();

    public ControlApplicationService(JdbcTemplate jdbc, ObjectMapper mapper, Clock clock,
            DefaultNodeRegistry registry, GraphSimulationService simulationService,
            GovernanceValidator governanceValidator) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.clock = clock;
        this.registry = registry;
        this.simulationService = simulationService;
        this.governanceValidator = governanceValidator;
    }

    @Transactional
    public CampaignView createCampaign(String name, String objective, String organizationId, String shopId) {
        var scope = TenantContextHolder.requireCurrent();
        scope.requirePermission("campaign:write");
        if (organizationId == null || organizationId.isBlank()) {
            throw new IllegalArgumentException("campaign organizationId is required");
        }
        scope.requireOrganization(organizationId);
        if (shopId != null && !shopId.isBlank()) scope.requireShop(shopId);
        Instant now = clock.instant();
        Campaign campaign = new Campaign(scope.tenantId().value(), UUID.randomUUID().toString(), name, objective,
                Campaign.Status.DRAFT, now, now);
        jdbc.update("insert into mk_campaign(tenant_id,campaign_id,name,objective,status,organization_id,shop_id,created_at,updated_at) values(?,?,?,?,?,?,?,?,?)",
                campaign.tenantId(), campaign.id(), campaign.name(), campaign.objective(), campaign.status().name(),
                organizationId, shopId, format(now), format(now));
        audit(scope.tenantId().value(), scope.actorId(), "CAMPAIGN_CREATED", campaign.id(), now);
        return CampaignView.from(campaign);
    }

    public List<CampaignView> campaigns() {
        var scope = TenantContextHolder.requireCurrent();
        scope.requirePermission("campaign:read");
        if (scope.organizations().isEmpty()) return List.of();
        StringBuilder sql = new StringBuilder("select tenant_id,campaign_id,name,objective,status,created_at,updated_at from mk_campaign where tenant_id=?");
        List<Object> arguments = new ArrayList<>();
        arguments.add(scope.tenantId().value());
        appendScope(sql, arguments, "organization_id", scope.organizations(), false);
        appendScope(sql, arguments, "shop_id", scope.shops(), true);
        sql.append(" order by created_at desc");
        return jdbc.query(sql.toString(),
                (rs, rowNum) -> new CampaignView(rs.getString("campaign_id"), rs.getString("name"),
                        rs.getString("objective"), Campaign.Status.valueOf(rs.getString("status")),
                        Instant.parse(rs.getString("created_at")), Instant.parse(rs.getString("updated_at"))),
                arguments.toArray());
    }

    private static void appendScope(StringBuilder sql, List<Object> arguments, String column,
            Set<String> scope, boolean allowUnscoped) {
        if (scope.contains("*")) return;
        if (scope.isEmpty()) {
            sql.append(allowUnscoped ? " and (" : " and ").append(column);
            if (allowUnscoped) sql.append(" is null or ").append(column).append("='')");
            else sql.append(" is null and 1=0");
            return;
        }
        sql.append(" and (");
        if (allowUnscoped) sql.append(column).append(" is null or ").append(column).append("='' or ");
        sql.append(column).append(" in (");
        List<String> values = scope.stream().sorted().toList();
        sql.append("?,".repeat(values.size()));
        sql.setLength(sql.length() - 1);
        sql.append("))");
        arguments.addAll(values);
    }

    @Transactional
    public DefinitionView saveDefinition(String campaignId, GraphDefinition graph) {
        var scope = TenantContextHolder.requireCurrent();
        scope.requirePermission("definition:write");
        requireCampaignScope(scope, campaignId, true);
        List<String> owners = jdbc.query("select distinct campaign_id from mk_definition_version where tenant_id=? and definition_id=?",
                (rs, rowNum) -> rs.getString(1), scope.tenantId().value(), graph.definitionId());
        if (!owners.isEmpty() && !owners.getFirst().equals(campaignId)) {
            throw new ConflictException("DEFINITION_CAMPAIGN_CONFLICT",
                    "definition id is already owned by another campaign");
        }
        List<DefinitionView> duplicate = jdbc.query(
                "select campaign_id,definition_id,version_no,dialect,graph_json,semantic_hash,status,created_by,created_at,updated_at from mk_definition_version where tenant_id=? and definition_id=? and semantic_hash=?",
                (rs, rowNum) -> definitionView(rs), scope.tenantId().value(), graph.definitionId(),
                hasher.semanticHash(graph));
        if (!duplicate.isEmpty()) {
            return duplicate.getFirst();
        }
        Long current = jdbc.query("select max(version_no) version_no from mk_definition_version where tenant_id=? and definition_id=?",
                rs -> rs.next() ? rs.getObject("version_no", Long.class) : null,
                scope.tenantId().value(), graph.definitionId());
        long version = current == null ? 1 : current + 1;
        Instant now = clock.instant();
        String hash = hasher.semanticHash(graph);
        jdbc.update("insert into mk_definition_version(tenant_id,definition_id,campaign_id,version_no,dialect,graph_json,semantic_hash,status,created_by,created_at,updated_at) values(?,?,?,?,?,?,?,?,?,?,?)",
                scope.tenantId().value(), graph.definitionId(), campaignId, version, graph.dialect().name(),
                json(graph), hash, DefinitionVersion.Status.DRAFT.name(), scope.actorId(), format(now), format(now));
        audit(scope.tenantId().value(), scope.actorId(), "DEFINITION_SAVED", graph.definitionId() + ':' + version, now);
        return new DefinitionView(graph.definitionId(), campaignId, version, graph.dialect(), hash,
                DefinitionVersion.Status.DRAFT, scope.actorId(), now, now, graph);
    }

    public DefinitionView latestDefinition(String campaignId, Dialect dialect) {
        var scope = TenantContextHolder.requireCurrent();
        scope.requirePermission("definition:read");
        requireCampaignScope(scope, campaignId, false);
        List<StoredDefinition> definitions = jdbc.query(
                "select campaign_id,definition_id,version_no,dialect,graph_json,semantic_hash,status,created_by,created_at,updated_at from mk_definition_version where tenant_id=? and campaign_id=? and dialect=? order by version_no desc,updated_at desc,definition_id limit 1",
                (rs, rowNum) -> storedDefinition(rs), scope.tenantId().value(), campaignId, dialect.name());
        if (definitions.isEmpty()) {
            throw new NotFoundException("DEFINITION_NOT_FOUND", "campaign dialect has no definition");
        }
        return definitions.getFirst().view();
    }

    public DefinitionView definition(String definitionId, long version) {
        var scope = TenantContextHolder.requireCurrent();
        scope.requirePermission("definition:read");
        return definition(scope, definitionId, version).view();
    }

    @Transactional
    public ValidationResult validate(String definitionId, long version) {
        var scope = TenantContextHolder.requireCurrent();
        scope.requirePermission("definition:write");
        StoredDefinition stored = definition(scope, definitionId, version);
        List<ValidationIssue> issues = new ArrayList<>(new GraphValidator(
                registry.all(), GraphValidator.Limits.productionDefaults()).validate(stored.graph()));
        issues.addAll(governanceValidator.validate(stored.graph()));
        boolean valid = issues.stream().noneMatch(issue -> issue.severity() == ValidationIssue.Severity.ERROR);
        if (valid && stored.view().status() == DefinitionVersion.Status.DRAFT) {
            updateDefinitionStatus(scope.tenantId().value(), definitionId, version,
                    DefinitionVersion.Status.VALIDATED, clock.instant());
        }
        return new ValidationResult(valid, stored.view().semanticHash(), issues);
    }

    public GraphSimulationService.Simulation simulate(String definitionId, long version, Map<String, String> facts) {
        var scope = TenantContextHolder.requireCurrent();
        scope.requirePermission("definition:simulate");
        StoredDefinition stored = definition(scope, definitionId, version);
        List<ValidationIssue> issues = new GraphValidator(registry.all(), GraphValidator.Limits.productionDefaults())
                .validate(stored.graph());
        if (issues.stream().anyMatch(issue -> issue.severity() == ValidationIssue.Severity.ERROR)) {
            throw new ConflictException("DEFINITION_INVALID", "definition must pass validation before simulation");
        }
        return simulationService.simulate(stored.graph(), facts);
    }

    @Transactional
    public ApprovalView submit(String definitionId, long version) {
        var scope = TenantContextHolder.requireCurrent();
        scope.requirePermission("definition:submit");
        StoredDefinition stored = definition(scope, definitionId, version);
        if (stored.view().status() != DefinitionVersion.Status.VALIDATED) {
            throw new ConflictException("DEFINITION_NOT_VALIDATED", "validate definition before submission");
        }
        Set<ApprovalCase.Role> roles = requiredRoles(stored.graph());
        String caseId = UUID.randomUUID().toString();
        Instant now = clock.instant();
        jdbc.update("insert into mk_approval_case(tenant_id,case_id,definition_id,definition_version,submitted_by,required_roles,status,created_at,updated_at) values(?,?,?,?,?,?,?,?,?)",
                scope.tenantId().value(), caseId, definitionId, version, scope.actorId(),
                roles.stream().map(Enum::name).sorted().reduce((left, right) -> left + ',' + right).orElse(""),
                ApprovalCase.Status.OPEN.name(), format(now), format(now));
        String termsContent = termsContent(stored.graph());
        String termsHash = "sha256:" + com.acme.marketing.platform.crypto.Digests.sha256Hex(termsContent);
        jdbc.update("insert into mk_terms_snapshot(tenant_id,terms_id,definition_id,definition_version,content_json,content_hash,created_at) values(?,?,?,?,?,?,?)",
                scope.tenantId().value(), UUID.randomUUID().toString(), definitionId, version,
                termsContent, termsHash, format(now));
        updateDefinitionStatus(scope.tenantId().value(), definitionId, version,
                DefinitionVersion.Status.IN_REVIEW, now);
        audit(scope.tenantId().value(), scope.actorId(), "DEFINITION_SUBMITTED", definitionId + ':' + version, now);
        return new ApprovalView(caseId, definitionId, version, scope.actorId(), roles, Map.of(),
                ApprovalCase.Status.OPEN, now);
    }

    @Transactional
    public ApprovalView decide(String caseId, ApprovalCase.Role role, ApprovalDecision decision, String comment) {
        var scope = TenantContextHolder.requireCurrent();
        scope.requirePermission("approval:" + role.name().toLowerCase(java.util.Locale.ROOT));
        ApprovalCase approval = approval(scope.tenantId().value(), caseId, true);
        definition(scope, approval.definitionId(), approval.definitionVersion());
        Instant now = clock.instant();
        ApprovalDecision resolved = decision == null ? ApprovalDecision.APPROVE : decision;
        if (resolved == ApprovalDecision.REJECT) {
            approval.reject(role, scope.actorId(), now);
        } else {
            approval.approve(role, scope.actorId(), now);
            jdbc.update("insert into mk_approval_decision(tenant_id,case_id,role_name,actor_id,decided_at) values(?,?,?,?,?)",
                    scope.tenantId().value(), caseId, role.name(), scope.actorId(), format(now));
        }
        jdbc.update("update mk_approval_case set status=?,updated_at=? where tenant_id=? and case_id=?",
                approval.status().name(), format(now), scope.tenantId().value(), caseId);
        if (approval.status() == ApprovalCase.Status.APPROVED) {
            updateDefinitionStatus(scope.tenantId().value(), approval.definitionId(), approval.definitionVersion(),
                    DefinitionVersion.Status.APPROVED, now);
        } else if (approval.status() == ApprovalCase.Status.REJECTED) {
            updateDefinitionStatus(scope.tenantId().value(), approval.definitionId(), approval.definitionVersion(),
                    DefinitionVersion.Status.DRAFT, now);
        }
        String auditResource = caseId + ':' + role + ':' + resolved
                + (comment == null || comment.isBlank() ? "" : ':' + comment);
        audit(scope.tenantId().value(), scope.actorId(),
                resolved == ApprovalDecision.REJECT ? "APPROVAL_REJECTED" : "APPROVAL_RECORDED",
                auditResource, now);
        return ApprovalView.from(approval);
    }

    private StoredDefinition definition(TenantScope scope, String definitionId, long version) {
        List<StoredDefinition> definitions = jdbc.query(
                "select campaign_id,definition_id,version_no,dialect,graph_json,semantic_hash,status,created_by,created_at,updated_at from mk_definition_version where tenant_id=? and definition_id=? and version_no=?",
                (rs, rowNum) -> {
                    GraphDefinition graph = readGraph(rs.getString("graph_json"));
                    DefinitionView view = new DefinitionView(rs.getString("definition_id"), rs.getString("campaign_id"),
                            rs.getLong("version_no"), Dialect.valueOf(rs.getString("dialect")),
                            rs.getString("semantic_hash"), DefinitionVersion.Status.valueOf(rs.getString("status")),
                            rs.getString("created_by"), Instant.parse(rs.getString("created_at")),
                            Instant.parse(rs.getString("updated_at")), graph);
                    return new StoredDefinition(view, graph);
                }, scope.tenantId().value(), definitionId, version);
        if (definitions.isEmpty()) {
            throw new NotFoundException("DEFINITION_NOT_FOUND", "definition version not found");
        }
        StoredDefinition stored = definitions.getFirst();
        requireCampaignScope(scope, stored.view().campaignId(), false);
        return stored;
    }

    public List<ApprovalView> approvals() {
        var scope = TenantContextHolder.requireCurrent();
        if (!scope.permits("definition:read")
                && !scope.permits("approval:business")
                && !scope.permits("approval:finance")
                && !scope.permits("approval:compliance")
                && !scope.permits("approval:merchant")) {
            scope.requirePermission("definition:read");
        }
        List<ApprovalCase> cases = jdbc.query(
                "select case_id,definition_id,definition_version,submitted_by,required_roles,status,updated_at from mk_approval_case where tenant_id=? order by updated_at desc limit 100",
                (rs, rowNum) -> {
                    String caseId = rs.getString("case_id");
                    return new ApprovalCase(caseId, rs.getString("definition_id"),
                            rs.getLong("definition_version"), rs.getString("submitted_by"),
                            roles(rs.getString("required_roles")), decisions(scope.tenantId().value(), caseId),
                            ApprovalCase.Status.valueOf(rs.getString("status")),
                            Instant.parse(rs.getString("updated_at")));
                }, scope.tenantId().value());
        List<ApprovalView> views = new ArrayList<>();
        for (ApprovalCase item : cases) {
            try {
                definition(scope, item.definitionId(), item.definitionVersion());
                views.add(ApprovalView.from(item));
            } catch (NotFoundException | IllegalArgumentException ignored) {
                // Skip cases outside the caller's campaign/org/shop scope.
            }
        }
        return views;
    }

    public TermsView terms(String definitionId, long version) {
        var scope = TenantContextHolder.requireCurrent();
        scope.requirePermission("definition:read");
        definition(scope, definitionId, version);
        List<TermsView> terms = jdbc.query(
                "select terms_id,content_json,content_hash,created_at from mk_terms_snapshot where tenant_id=? and definition_id=? and definition_version=? order by created_at desc",
                (rs, rowNum) -> new TermsView(rs.getString("terms_id"), definitionId, version,
                        rs.getString("content_json"), rs.getString("content_hash"),
                        Instant.parse(rs.getString("created_at"))), scope.tenantId().value(), definitionId, version);
        if (terms.isEmpty()) {
            throw new NotFoundException("TERMS_NOT_FOUND", "terms snapshot not found");
        }
        return terms.getFirst();
    }

    private ApprovalCase approval(String tenantId, String caseId, boolean lock) {
        String suffix = lock ? " for update" : "";
        List<ApprovalCase> cases = jdbc.query(
                "select case_id,definition_id,definition_version,submitted_by,required_roles,status,updated_at from mk_approval_case where tenant_id=? and case_id=?" + suffix,
                (rs, rowNum) -> new ApprovalCase(rs.getString("case_id"), rs.getString("definition_id"),
                        rs.getLong("definition_version"), rs.getString("submitted_by"),
                        roles(rs.getString("required_roles")), decisions(tenantId, caseId),
                        ApprovalCase.Status.valueOf(rs.getString("status")), Instant.parse(rs.getString("updated_at"))),
                tenantId, caseId);
        if (cases.isEmpty()) {
            throw new NotFoundException("APPROVAL_NOT_FOUND", "approval case not found");
        }
        return cases.getFirst();
    }

    private Map<ApprovalCase.Role, String> decisions(String tenantId, String caseId) {
        EnumMap<ApprovalCase.Role, String> result = new EnumMap<>(ApprovalCase.Role.class);
        jdbc.query("select role_name,actor_id from mk_approval_decision where tenant_id=? and case_id=?",
                rs -> {
                    result.put(ApprovalCase.Role.valueOf(rs.getString("role_name")), rs.getString("actor_id"));
                },
                tenantId, caseId);
        return result;
    }

    private void requireCampaignScope(TenantScope scope, String campaignId, boolean lock) {
        List<CampaignOwnership> result = jdbc.query("select organization_id,shop_id from mk_campaign where tenant_id=? and campaign_id=?" + (lock ? " for update" : ""),
                (rs, rowNum) -> new CampaignOwnership(rs.getString(1), rs.getString(2)),
                scope.tenantId().value(), campaignId);
        if (result.isEmpty()) {
            throw new NotFoundException("CAMPAIGN_NOT_FOUND", "campaign not found");
        }
        CampaignOwnership ownership = result.getFirst();
        scope.requireOrganization(ownership.organizationId());
        if (ownership.shopId() != null && !ownership.shopId().isBlank()) scope.requireShop(ownership.shopId());
    }

    private void updateDefinitionStatus(String tenantId, String definitionId, long version,
            DefinitionVersion.Status status, Instant now) {
        int count = jdbc.update("update mk_definition_version set status=?,updated_at=? where tenant_id=? and definition_id=? and version_no=?",
                status.name(), format(now), tenantId, definitionId, version);
        if (count != 1) {
            throw new NotFoundException("DEFINITION_NOT_FOUND", "definition version not found");
        }
    }

    private DefinitionView definitionView(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new DefinitionView(rs.getString("definition_id"), rs.getString("campaign_id"), rs.getLong("version_no"),
                Dialect.valueOf(rs.getString("dialect")), rs.getString("semantic_hash"),
                DefinitionVersion.Status.valueOf(rs.getString("status")), rs.getString("created_by"),
                Instant.parse(rs.getString("created_at")), Instant.parse(rs.getString("updated_at")),
                readGraph(rs.getString("graph_json")));
    }

    private StoredDefinition storedDefinition(java.sql.ResultSet rs) throws java.sql.SQLException {
        GraphDefinition graph = readGraph(rs.getString("graph_json"));
        return new StoredDefinition(new DefinitionView(rs.getString("definition_id"), rs.getString("campaign_id"),
                rs.getLong("version_no"), Dialect.valueOf(rs.getString("dialect")), rs.getString("semantic_hash"),
                DefinitionVersion.Status.valueOf(rs.getString("status")), rs.getString("created_by"),
                Instant.parse(rs.getString("created_at")), Instant.parse(rs.getString("updated_at")), graph), graph);
    }

    private static Set<ApprovalCase.Role> requiredRoles(GraphDefinition graph) {
        EnumSet<ApprovalCase.Role> roles = EnumSet.of(ApprovalCase.Role.BUSINESS, ApprovalCase.Role.COMPLIANCE);
        if (graph.dialect() == Dialect.OFFER_DECISION_DAG || graph.dialect() == Dialect.BENEFIT_POLICY) {
            roles.add(ApprovalCase.Role.FINANCE);
        }
        if (Boolean.parseBoolean(graph.annotations().getOrDefault("merchantFunding", "false"))) {
            roles.add(ApprovalCase.Role.MERCHANT);
        }
        return Set.copyOf(roles);
    }

    private static Set<ApprovalCase.Role> roles(String encoded) {
        if (encoded == null || encoded.isBlank()) return Set.of();
        EnumSet<ApprovalCase.Role> roles = EnumSet.noneOf(ApprovalCase.Role.class);
        for (String role : encoded.split(",")) roles.add(ApprovalCase.Role.valueOf(role));
        return Set.copyOf(roles);
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JacksonException failure) {
            throw new IllegalArgumentException("cannot serialize graph", failure);
        }
    }

    private String termsContent(GraphDefinition graph) {
        Map<String, Object> terms = new java.util.LinkedHashMap<>();
        terms.put("definitionId", graph.definitionId());
        terms.put("dialect", graph.dialect().name());
        terms.put("benefits", graph.nodes().stream()
                .filter(node -> node.stableTypeId().startsWith("offer."))
                .filter(node -> node.stableTypeId().equals("offer.fixed")
                        || node.stableTypeId().equals("offer.percentage"))
                .map(node -> Map.of("type", node.stableTypeId(), "config", node.config()))
                .toList());
        terms.put("disclosures", graph.annotations());
        return json(terms);
    }

    private GraphDefinition readGraph(String json) {
        try {
            return mapper.readValue(json, GraphDefinition.class);
        } catch (JacksonException failure) {
            throw new IllegalStateException("stored graph is invalid", failure);
        }
    }

    private void audit(String tenantId, String actorId, String action, String resource, Instant now) {
        // A no-op upsert takes an exclusive row lock immediately. Catching a duplicate insert would leave
        // concurrent transactions holding shared duplicate-key locks and deadlock when they upgrade below.
        jdbc.update("insert into mk_audit_head(tenant_id,last_chain_index,last_entry_hash,updated_at) values(?,?,?,?) on duplicate key update tenant_id=tenant_id",
                tenantId, 0, "GENESIS", format(now));
        AuditHead head = jdbc.query("select last_chain_index,last_entry_hash from mk_audit_head where tenant_id=? for update",
                rs -> rs.next() ? new AuditHead(rs.getLong(1), rs.getString(2)) : null, tenantId);
        if (head == null) throw new IllegalStateException("tenant audit head was not created");
        long chainIndex = Math.addExact(head.chainIndex(), 1);
        String previous = head.entryHash();
        String hash = com.acme.marketing.platform.crypto.Digests.sha256Hex(
                previous + '|' + chainIndex + '|' + tenantId + '|' + actorId + '|' + action + '|' + resource
                        + '|' + format(now));
        jdbc.update("insert into mk_audit(tenant_id,audit_id,chain_index,actor_id,action_name,resource_ref,occurred_at,previous_hash,entry_hash) values(?,?,?,?,?,?,?,?,?)",
                tenantId, UUID.randomUUID().toString(), chainIndex, actorId, action, resource, format(now),
                previous, hash);
        int updated = jdbc.update("update mk_audit_head set last_chain_index=?,last_entry_hash=?,updated_at=? where tenant_id=? and last_chain_index=? and last_entry_hash=?",
                chainIndex, hash, format(now), tenantId, head.chainIndex(), head.entryHash());
        if (updated != 1) throw new IllegalStateException("tenant audit head compare-and-set failed");
    }

    private record AuditHead(long chainIndex, String entryHash) { }

    public record CampaignView(String id, String name, String objective, Campaign.Status status,
            Instant createdAt, Instant updatedAt) {
        static CampaignView from(Campaign campaign) {
            return new CampaignView(campaign.id(), campaign.name(), campaign.objective(), campaign.status(),
                    campaign.createdAt(), campaign.updatedAt());
        }
    }
    public record DefinitionView(String definitionId, String campaignId, long version, Dialect dialect,
            String semanticHash, DefinitionVersion.Status status, String createdBy, Instant createdAt, Instant updatedAt,
            GraphDefinition graph) { }
    public record StoredDefinition(DefinitionView view, GraphDefinition graph) { }
    public record ValidationResult(boolean valid, String semanticHash, List<ValidationIssue> issues) {
        public ValidationResult { issues = List.copyOf(issues); }
    }
    public record ApprovalView(String caseId, String definitionId, long definitionVersion, String submittedBy,
            Set<ApprovalCase.Role> requiredRoles, Map<ApprovalCase.Role, String> approvals,
            ApprovalCase.Status status, Instant updatedAt) {
        static ApprovalView from(ApprovalCase approval) {
            return new ApprovalView(approval.id(), approval.definitionId(), approval.definitionVersion(),
                    approval.submittedBy(), approval.requiredRoles(), approval.approvals(),
                    approval.status(), approval.updatedAt());
        }
    }
    public record TermsView(String termsId, String definitionId, long definitionVersion,
            String contentJson, String contentHash, Instant createdAt) { }
    public enum ApprovalDecision { APPROVE, REJECT }
    private record CampaignOwnership(String organizationId, String shopId) { }
}
