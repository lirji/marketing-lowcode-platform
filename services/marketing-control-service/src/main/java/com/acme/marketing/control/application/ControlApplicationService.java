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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ControlApplicationService {
    private final ControlRepository repository;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final DefaultNodeRegistry registry;
    private final GraphSimulationService simulationService;
    private final GovernanceValidator governanceValidator;
    private final CanonicalGraphHasher hasher = new CanonicalGraphHasher();

    public ControlApplicationService(ControlRepository repository, ObjectMapper mapper, Clock clock,
            DefaultNodeRegistry registry, GraphSimulationService simulationService,
            GovernanceValidator governanceValidator) {
        this.repository = repository;
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
        repository.saveCampaign(new ControlRepository.CampaignWrite(campaign.tenantId(), campaign.id(),
                campaign.name(), campaign.objective(), campaign.status().name(), organizationId, shopId,
                format(now), format(now)));
        audit(scope.tenantId().value(), scope.actorId(), "CAMPAIGN_CREATED", campaign.id(), now);
        return CampaignView.from(campaign);
    }

    public List<CampaignView> campaigns() {
        var scope = TenantContextHolder.requireCurrent();
        scope.requirePermission("campaign:read");
        if (scope.organizations().isEmpty()) return List.of();
        return repository.findCampaigns(scope.tenantId().value(), scope.organizations(), scope.shops()).stream()
                .map(row -> new CampaignView(row.campaignId(), row.name(), row.objective(),
                        Campaign.Status.valueOf(row.status()), Instant.parse(row.createdAt()),
                        Instant.parse(row.updatedAt())))
                .toList();
    }

    @Transactional
    public DefinitionView saveDefinition(String campaignId, GraphDefinition graph) {
        var scope = TenantContextHolder.requireCurrent();
        scope.requirePermission("definition:write");
        requireCampaignScope(scope, campaignId, true);
        List<String> owners = repository.findDefinitionOwners(scope.tenantId().value(), graph.definitionId());
        if (!owners.isEmpty() && !owners.getFirst().equals(campaignId)) {
            throw new ConflictException("DEFINITION_CAMPAIGN_CONFLICT",
                    "definition id is already owned by another campaign");
        }
        var duplicate = repository.findDefinitionBySemanticHash(scope.tenantId().value(), graph.definitionId(),
                hasher.semanticHash(graph));
        if (duplicate.isPresent()) return definitionView(duplicate.orElseThrow());
        Long current = repository.findLatestDefinitionVersion(scope.tenantId().value(), graph.definitionId())
                .orElse(null);
        long version = current == null ? 1 : current + 1;
        Instant now = clock.instant();
        String hash = hasher.semanticHash(graph);
        repository.saveDefinition(new ControlRepository.DefinitionWrite(scope.tenantId().value(),
                graph.definitionId(), campaignId, version, graph.dialect().name(), json(graph), hash,
                DefinitionVersion.Status.DRAFT.name(), scope.actorId(), format(now), format(now)));
        audit(scope.tenantId().value(), scope.actorId(), "DEFINITION_SAVED", graph.definitionId() + ':' + version, now);
        return new DefinitionView(graph.definitionId(), campaignId, version, graph.dialect(), hash,
                DefinitionVersion.Status.DRAFT, scope.actorId(), now, now, graph);
    }

    public DefinitionView latestDefinition(String campaignId, Dialect dialect) {
        var scope = TenantContextHolder.requireCurrent();
        scope.requirePermission("definition:read");
        requireCampaignScope(scope, campaignId, false);
        return repository.findLatestDefinition(scope.tenantId().value(), campaignId, dialect.name())
                .map(this::storedDefinition)
                .orElseThrow(() -> new NotFoundException("DEFINITION_NOT_FOUND",
                        "campaign dialect has no definition"))
                .view();
    }

    public DefinitionView definition(String definitionId, long version) {
        var scope = TenantContextHolder.requireCurrent();
        scope.requirePermission("definition:read");
        return definition(scope, definitionId, version).view();
    }

    /** 裂变校验只确认共享规则语义；真实目录与发布闭包未完成时返回明确告警，不授予审批资格。 */
    @Transactional
    public ValidationResult validate(String definitionId, long version) {
        var scope = TenantContextHolder.requireCurrent();
        scope.requirePermission("definition:write");
        StoredDefinition stored = definition(scope, definitionId, version);
        if (stored.graph().dialect() == Dialect.REFERRAL_POLICY) {
            List<ValidationIssue> issues = new ArrayList<>();
            try {
                new com.acme.marketing.referral.ReferralPlanCompiler().compile(stored.graph());
                // 使用同一新方言摘要验证字符串编码合法性，不能校验通过后才在签名阶段报错。
                hasher.semanticHash(stored.graph());
            } catch (IllegalArgumentException invalid) {
                issues.add(new ValidationIssue(ValidationIssue.Severity.ERROR, "REFERRAL_POLICY_INVALID", "/nodes", null,
                        invalid.getMessage()));
            }
            boolean valid = issues.isEmpty();
            if (valid) issues.add(new ValidationIssue(ValidationIssue.Severity.WARNING, "REFERRAL_CATALOG_UNVERIFIED", "/nodes", null,
                    "仅通过规则语义校验；真实SKU/权益版本目录尚未核验，审批与发布仍关闭"));
            if (valid && stored.view().status() == DefinitionVersion.Status.DRAFT)
                updateDefinitionStatus(scope.tenantId().value(), definitionId, version,
                        DefinitionVersion.Status.VALIDATED, clock.instant());
            return new ValidationResult(valid, stored.view().semanticHash(), issues);
        }
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

    /** 原始facts保留JSON值类型直到方言检查；裂变只返回带模拟标识的纯计算结果。 */
    public GraphSimulationService.SimulationResult simulate(String definitionId, long version, Map<String, ?> facts) {
        var scope = TenantContextHolder.requireCurrent();
        scope.requirePermission("definition:simulate");
        StoredDefinition stored = definition(scope, definitionId, version);
        List<ValidationIssue> issues = new GraphValidator(registry.all(), GraphValidator.Limits.productionDefaults())
                .validate(stored.graph());
        if (issues.stream().anyMatch(issue -> issue.severity() == ValidationIssue.Severity.ERROR)) {
            throw new ConflictException("DEFINITION_INVALID", "definition must pass validation before simulation");
        }
        if (stored.graph().dialect() == Dialect.REFERRAL_POLICY && (facts == null
                || facts.values().stream().anyMatch(value -> !(value instanceof String))))
            throw new IllegalArgumentException("REFERRAL_SIMULATION_STRING_VALUES_REQUIRED");
        Map<String, String> typedFacts = mapper.convertValue(facts, new tools.jackson.core.type.TypeReference<Map<String, String>>() { });
        return simulationService.preview(stored.graph(), typedFacts);
    }

    @Transactional
    public ApprovalView submit(String definitionId, long version) {
        var scope = TenantContextHolder.requireCurrent();
        scope.requirePermission("definition:submit");
        StoredDefinition stored = definition(scope, definitionId, version);
        requireControlDialectSupported(stored.graph());
        if (stored.view().status() != DefinitionVersion.Status.VALIDATED) {
            throw new ConflictException("DEFINITION_NOT_VALIDATED", "validate definition before submission");
        }
        Set<ApprovalCase.Role> roles = requiredRoles(stored.graph());
        String caseId = UUID.randomUUID().toString();
        Instant now = clock.instant();
        repository.saveApprovalCase(new ControlRepository.ApprovalCaseWrite(scope.tenantId().value(), caseId,
                definitionId, version, scope.actorId(),
                roles.stream().map(Enum::name).sorted().reduce((left, right) -> left + ',' + right).orElse(""),
                ApprovalCase.Status.OPEN.name(), format(now), format(now)));
        String termsContent = termsContent(stored.graph());
        String termsHash = "sha256:" + com.acme.marketing.platform.crypto.Digests.sha256Hex(termsContent);
        repository.saveTerms(new ControlRepository.TermsWrite(scope.tenantId().value(),
                UUID.randomUUID().toString(), definitionId, version, termsContent, termsHash, format(now)));
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
        requireControlDialectSupported(definition(scope, approval.definitionId(), approval.definitionVersion()).graph());
        Instant now = clock.instant();
        ApprovalDecision resolved = decision == null ? ApprovalDecision.APPROVE : decision;
        if (resolved == ApprovalDecision.REJECT) {
            approval.reject(role, scope.actorId(), now);
        } else {
            approval.approve(role, scope.actorId(), now);
            repository.saveApprovalDecision(new ControlRepository.ApprovalDecisionWrite(scope.tenantId().value(),
                    caseId, role.name(), scope.actorId(), format(now)));
        }
        repository.updateApprovalCase(scope.tenantId().value(), caseId, approval.status().name(), format(now));
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
        StoredDefinition stored = repository.findDefinition(scope.tenantId().value(), definitionId, version)
                .map(this::storedDefinition)
                .orElseThrow(() -> new NotFoundException("DEFINITION_NOT_FOUND", "definition version not found"));
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
        List<ApprovalCase> cases = repository.findApprovalCases(scope.tenantId().value(), 100).stream()
                .map(row -> approval(scope.tenantId().value(), row))
                .toList();
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
        ControlRepository.TermsRow row = repository.findLatestTerms(scope.tenantId().value(), definitionId, version)
                .orElseThrow(() -> new NotFoundException("TERMS_NOT_FOUND", "terms snapshot not found"));
        return new TermsView(row.termsId(), definitionId, version, row.contentJson(), row.contentHash(),
                Instant.parse(row.createdAt()));
    }

    private ApprovalCase approval(String tenantId, String caseId, boolean lock) {
        ControlRepository.ApprovalCaseRow row = repository.findApprovalCase(tenantId, caseId, lock)
                .orElseThrow(() -> new NotFoundException("APPROVAL_NOT_FOUND", "approval case not found"));
        return approval(tenantId, row);
    }

    private ApprovalCase approval(String tenantId, ControlRepository.ApprovalCaseRow row) {
        return new ApprovalCase(row.caseId(), row.definitionId(), row.definitionVersion(), row.submittedBy(),
                roles(row.requiredRoles()), decisions(tenantId, row.caseId()),
                ApprovalCase.Status.valueOf(row.status()), Instant.parse(row.updatedAt()));
    }

    private Map<ApprovalCase.Role, String> decisions(String tenantId, String caseId) {
        EnumMap<ApprovalCase.Role, String> result = new EnumMap<>(ApprovalCase.Role.class);
        repository.findApprovalDecisions(tenantId, caseId).forEach(row ->
                result.put(ApprovalCase.Role.valueOf(row.roleName()), row.actorId()));
        return result;
    }

    private void requireCampaignScope(TenantScope scope, String campaignId, boolean lock) {
        ControlRepository.CampaignOwnershipRow ownership = repository.findCampaignOwnership(
                        scope.tenantId().value(), campaignId, lock)
                .orElseThrow(() -> new NotFoundException("CAMPAIGN_NOT_FOUND", "campaign not found"));
        scope.requireOrganization(ownership.organizationId());
        if (ownership.shopId() != null && !ownership.shopId().isBlank()) scope.requireShop(ownership.shopId());
    }

    private void updateDefinitionStatus(String tenantId, String definitionId, long version,
            DefinitionVersion.Status status, Instant now) {
        int count = repository.updateDefinitionStatus(tenantId, definitionId, version, status.name(), format(now));
        if (count != 1) {
            throw new NotFoundException("DEFINITION_NOT_FOUND", "definition version not found");
        }
    }

    private DefinitionView definitionView(ControlRepository.DefinitionRow row) {
        return new DefinitionView(row.definitionId(), row.campaignId(), row.versionNo(),
                Dialect.valueOf(row.dialect()), row.semanticHash(), DefinitionVersion.Status.valueOf(row.status()),
                row.createdBy(), Instant.parse(row.createdAt()), Instant.parse(row.updatedAt()),
                readGraph(row.graphJson()));
    }

    private StoredDefinition storedDefinition(ControlRepository.DefinitionRow row) {
        GraphDefinition graph = readGraph(row.graphJson());
        return new StoredDefinition(new DefinitionView(row.definitionId(), row.campaignId(), row.versionNo(),
                Dialect.valueOf(row.dialect()), row.semanticHash(), DefinitionVersion.Status.valueOf(row.status()),
                row.createdBy(), Instant.parse(row.createdAt()), Instant.parse(row.updatedAt()), graph), graph);
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
        // 无操作 upsert 会立即取得排他行锁，避免先捕获重复键再锁头记录时发生锁升级死锁。
        repository.ensureAuditHead(tenantId, format(now));
        ControlRepository.AuditHeadRow stored = repository.findAuditHeadForUpdate(tenantId).orElse(null);
        AuditHead head = stored == null ? null : new AuditHead(stored.chainIndex(), stored.entryHash());
        if (head == null) throw new IllegalStateException("tenant audit head was not created");
        long chainIndex = Math.addExact(head.chainIndex(), 1);
        String previous = head.entryHash();
        String hash = com.acme.marketing.platform.crypto.Digests.sha256Hex(
                previous + '|' + chainIndex + '|' + tenantId + '|' + actorId + '|' + action + '|' + resource
                        + '|' + format(now));
        repository.saveAudit(new ControlRepository.AuditWrite(tenantId, UUID.randomUUID().toString(), chainIndex,
                actorId, action, resource, format(now), previous, hash));
        int updated = repository.compareAndSetAuditHead(new ControlRepository.AuditHeadWrite(tenantId, chainIndex,
                hash, format(now), head.chainIndex(), head.entryHash()));
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
    // 注册节点只用于发现与编译；控制面虽可同源校验/预览，尚未接入真实SKU闭包及冻结条款前不得产生批准资格。
    private static void requireControlDialectSupported(GraphDefinition graph) {
        if (graph.dialect() == Dialect.REFERRAL_POLICY)
            throw new ConflictException("REFERRAL_GOVERNANCE_NOT_AVAILABLE",
                    "referral approval and release integration is not available");
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
}
