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

    /** 旧应用入口保持STANDARD缺省语义。 */
    @Transactional
    public CampaignView createCampaign(String name, String objective, String organizationId, String shopId) {
        return createCampaign(name, objective, organizationId, shopId, Campaign.Type.STANDARD);
    }

    /** 类型随活动与审计同事务保存，不自动解除裂变发布门禁。 */
    @Transactional
    public CampaignView createCampaign(String name, String objective, String organizationId, String shopId,
            Campaign.Type campaignType) {
        var scope = TenantContextHolder.requireCurrent();
        scope.requirePermission("campaign:write");
        if (organizationId == null || organizationId.isBlank()) {
            throw new IllegalArgumentException("campaign organizationId is required");
        }
        scope.requireOrganization(organizationId);
        if (shopId != null && !shopId.isBlank()) scope.requireShop(shopId);
        Instant now = clock.instant();
        Campaign campaign = new Campaign(scope.tenantId().value(), UUID.randomUUID().toString(), name, objective,
                Campaign.Status.DRAFT, now, now, campaignType);
        repository.saveCampaign(new ControlRepository.CampaignWrite(campaign.tenantId(), campaign.id(),
                campaign.name(), campaign.objective(), campaign.status().name(), organizationId, shopId,
                format(now), format(now), campaign.campaignType().name()));
        audit(scope.tenantId().value(), scope.actorId(), "CAMPAIGN_CREATED", campaign.id(), now);
        return CampaignView.from(campaign);
    }

    /** 不筛选时保持原列表行为。 */
    public List<CampaignView> campaigns() { return campaigns(null); }

    /** 类型筛选在SQL执行，并继续应用租户、组织和门店隔离。 */
    public List<CampaignView> campaigns(Campaign.Type campaignType) {
        var scope = TenantContextHolder.requireCurrent();
        scope.requirePermission("campaign:read");
        if (scope.organizations().isEmpty()) return List.of();
        return repository.findCampaigns(scope.tenantId().value(), scope.organizations(), scope.shops(),
                        campaignType == null ? null : campaignType.name()).stream()
                .map(row -> new CampaignView(row.campaignId(), row.name(), row.objective(),
                        Campaign.Status.valueOf(row.status()), Instant.parse(row.createdAt()),
                        Instant.parse(row.updatedAt()), Campaign.Type.valueOf(row.campaignType())))
                .toList();
    }

    /** 重放命令前也检查当前权限与活动范围，旧成功回执不授予已撤销的访问权。 */
    public void authorizeDmnWrite(String campaignId) {
        var scope = TenantContextHolder.requireCurrent();
        scope.requirePermission("definition:write");
        requireCampaignScope(scope, campaignId, false);
    }

    /** 活动行锁串行化版本分配；草稿、命令回执及既有审计链由同一事务提交。 */
    @Transactional(timeout = 10)
    public DmnDraftView saveDmnDraft(String campaignId, long expectedVersion, DmnDraftTable table, String commandId) {
        var scope = TenantContextHolder.requireCurrent();
        scope.requirePermission("definition:write");
        if (table == null) throw DmnDraftError.INPUT_INVALID.invalid();
        if (expectedVersion < 0 || expectedVersion == Long.MAX_VALUE) throw DmnDraftError.VERSION_INVALID.invalid();
        requireCampaignScope(scope, campaignId, true);
        var current = repository.findLatestDmnDraft(scope.tenantId().value(), campaignId, true);
        long latest = current.map(ControlRepository.DmnDraftRow::versionNo).orElse(0L);
        if (expectedVersion != latest) throw new ConflictException(DmnDraftError.VERSION_CONFLICT, DmnDraftError.VERSION_CONFLICT_MESSAGE);
        String content = json(table);
        String hash = com.acme.marketing.platform.crypto.Digests.sha256Hex(content);
        if (current.isPresent() && current.orElseThrow().contentHash().equals(hash)) return dmnDraftView(current.orElseThrow());
        Instant now = clock.instant();
        var row = new ControlRepository.DmnDraftRow(scope.tenantId().value(), campaignId, latest + 1,
                content, hash, commandId, scope.actorId(), format(now));
        repository.saveDmnDraft(row);
        audit(scope.tenantId().value(), scope.actorId(), "DMN_DRAFT_SAVED", campaignId + ':' + row.versionNo(), now);
        return dmnDraftView(row);
    }

    /** 读取活动最新草稿；不把不存在的草稿替换成已保存空表。 */
    public DmnDraftView latestDmnDraft(String campaignId) {
        var scope = TenantContextHolder.requireCurrent();
        scope.requirePermission("definition:read");
        requireCampaignScope(scope, campaignId, false);
        return dmnDraftView(repository.findLatestDmnDraft(scope.tenantId().value(), campaignId, false)
                .orElseThrow(() -> new NotFoundException(DmnDraftError.NOT_FOUND, DmnDraftError.NOT_FOUND_MESSAGE)));
    }

    /** 历史版本仅查询；沿用活动当前数据范围，不绕过权限读取旧版本。 */
    public DmnDraftView dmnDraftVersion(String campaignId, long version) {
        var scope = TenantContextHolder.requireCurrent();
        scope.requirePermission("definition:read");
        if (version < 1) throw DmnDraftError.VERSION_INVALID.invalid();
        requireCampaignScope(scope, campaignId, false);
        return dmnDraftView(repository.findDmnDraft(scope.tenantId().value(), campaignId, version)
                .orElseThrow(() -> new NotFoundException(DmnDraftError.NOT_FOUND, DmnDraftError.NOT_FOUND_MESSAGE)));
    }

    private DmnDraftView dmnDraftView(ControlRepository.DmnDraftRow row) {
        return new DmnDraftView(row.campaignId(), row.versionNo(), row.contentHash(), row.createdBy(),
                Instant.parse(row.createdAt()), mapper.readValue(row.tableJson(), DmnDraftTable.class));
    }

    /** 保存回执始终指向具体不可变版本，不携带任何审核或发布状态。 */
    public record DmnDraftView(String campaignId, long version, String contentHash, String createdBy,
            Instant createdAt, DmnDraftTable table) { }

    @Transactional
    public DefinitionView saveDefinition(String campaignId, GraphDefinition graph) {
        var scope = TenantContextHolder.requireCurrent();
        scope.requirePermission("definition:write");
        requireCampaignScope(scope, campaignId, true);
        return storeDefinition(scope, campaignId, graph);
    }

    /** 在幂等回执回放前也检查当前身份的权限和活动归属。 */
    public void authorizeOfferInitialization(String campaignId) {
        var scope = TenantContextHolder.requireCurrent();
        scope.requirePermission("definition:write");
        requireCampaignScope(scope, campaignId, false);
    }

    /** 模板只初始化空白 Offer；复用活动行锁，防止另一位运营的规则被模板抢占。 */
    @Transactional
    public DefinitionView initializeOffer(String campaignId, GraphDefinition graph) {
        var scope = TenantContextHolder.requireCurrent();
        scope.requirePermission("definition:write");
        var owner = ControlCampaignScope.require(repository, scope, campaignId, true);
        if (!Campaign.Type.STANDARD.name().equals(owner.campaignType()) || owner.shopId() == null || owner.shopId().isBlank()) {
            throw OfferPolicyError.INITIAL_OFFER_REQUIRES_SHOP.exception();
        }
        if (repository.findLatestDefinition(scope.tenantId().value(), campaignId, Dialect.OFFER_DECISION_DAG.name()).isPresent()) {
            throw OfferPolicyError.INITIAL_OFFER_ALREADY_EXISTS.exception();
        }
        var policies = new com.acme.marketing.decision.runtime.OfferPolicyGraphCompiler().compile(graph).policies();
        if (policies.size() != 1) throw OfferPolicyError.INITIAL_OFFER_REQUIRES_SHOP.exception();
        var policy = policies.getFirst();
        var formula = policy.candidate().formula();
        if (!policy.shopIds().equals(Set.of(owner.shopId())) || policy.skuIds().size() != 1
                || !"CNY".equals(policy.pricingCurrency())
                || formula.kind() != com.acme.marketing.decision.model.PromotionKind.FIXED_OFF
                || formula.valueMinor() <= 0 || formula.valueMinor() > formula.thresholdMinor()) {
            throw OfferPolicyError.INITIAL_OFFER_REQUIRES_SHOP.exception();
        }
        return storeDefinition(scope, campaignId, graph);
    }

    private DefinitionView storeDefinition(TenantScope scope, String campaignId, GraphDefinition graph) {
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
        if (stored.graph().dialect() == Dialect.OFFER_DECISION_DAG) {
            issues.addAll(com.acme.marketing.lowcode.validation.OfferGraphInputGuard.validate(stored.graph()));
        }
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

    /** 读取请求正文前检查当前定义范围，禁止无权请求消耗仿真计算预算。 */
    public void authorizeJourneySimulation(String definitionId, long version) {
        var scope = TenantContextHolder.requireCurrent();
        scope.requirePermission("definition:simulate");
        definition(scope, definitionId, version);
    }

    /** 对真实保存的版本执行同源状态机，纯计算不写定义状态或业务命令。 */
    public JourneySimulationService.Result simulateJourney(String definitionId, long version, JourneySimulationRequest request) {
        var scope = TenantContextHolder.requireCurrent();
        scope.requirePermission("definition:simulate");
        StoredDefinition stored = definition(scope, definitionId, version);
        if (stored.graph().dialect() != Dialect.JOURNEY_STATE_MACHINE) throw JourneySimulationErrorCode.DIALECT_REQUIRED.invalid();
        var issues = new GraphValidator(registry.all(), GraphValidator.Limits.productionDefaults()).validate(stored.graph());
        if (issues.stream().anyMatch(issue -> issue.severity() == ValidationIssue.Severity.ERROR))
            throw new ConflictException("DEFINITION_INVALID", "definition must pass validation before simulation");
        return new JourneySimulationService().simulate(stored.graph(), version, request);
    }

    @Transactional
    public ApprovalView submit(String definitionId, long version) {
        var scope = TenantContextHolder.requireCurrent();
        scope.requirePermission("definition:submit");
        StoredDefinition stored = definition(scope, definitionId, version);
        requireControlDialectSupported(stored.graph());
        if (stored.graph().dialect() == Dialect.OFFER_DECISION_DAG) {
            com.acme.marketing.lowcode.validation.OfferGraphInputGuard.requireSupported(stored.graph());
        }
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

    /** 兼容旧数组接口，保留最近更新时间排序，但先在SQL中过滤可见范围。 */
    public List<ApprovalView> approvals() {
        var scope = approvalReadScope();
        var query = new ControlRepository.ApprovalListQuery(scope.tenantId().value(), scope.organizations(),
                scope.shops(), null, null, null, ApprovalPaging.MAX_PAGE_SIZE);
        return repository.findApprovalCases(query).stream()
                .map(row -> ApprovalView.from(approval(scope.tenantId().value(), row))).toList();
    }

    /** 按不可变提交时间与ID翻页；状态可变，刷新后从第一页重新核对当前事实。 */
    public ApprovalPage approvalPage(Integer limit, String status, String cursor) {
        var scope = approvalReadScope();
        var input = ApprovalPaging.validate(limit, status, cursor);
        String createdAt = null;
        if (input.cursor() != null) {
            var pointQuery = new ControlRepository.ApprovalListQuery(scope.tenantId().value(), scope.organizations(),
                    scope.shops(), null, null, input.cursor(), 1);
            // 游标允许已改变审核状态，但必须仍在当前租户/组织/门店的可见范围。
            createdAt = repository.findApprovalCursor(pointQuery).orElseThrow(ApprovalPaging::invalidCursor).createdAt();
        }
        var query = new ControlRepository.ApprovalListQuery(scope.tenantId().value(), scope.organizations(),
                scope.shops(), input.status(), createdAt, input.cursor(), input.limit() + 1);
        var rows = repository.findApprovalPage(query);
        boolean hasMore = rows.size() > input.limit();
        var items = rows.stream().limit(input.limit())
                .map(row -> ApprovalView.from(approval(scope.tenantId().value(), row))).toList();
        return new ApprovalPage(items, hasMore ? items.getLast().caseId() : null, hasMore, input.status());
    }

    private TenantScope approvalReadScope() {
        var scope = TenantContextHolder.requireCurrent();
        if (!scope.permits("definition:read") && !scope.permits("approval:business")
                && !scope.permits("approval:finance") && !scope.permits("approval:compliance")
                && !scope.permits("approval:merchant")) scope.requirePermission("definition:read");
        return scope;
    }

    public record ApprovalPage(List<ApprovalView> items, String nextCursor, boolean hasMore, String status) { }

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
        ControlCampaignScope.require(repository, scope, campaignId, lock);
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
        ControlAuditChain.append(repository, tenantId, actorId, action, resource, now);
    }

    public record CampaignView(String id, String name, String objective, Campaign.Status status,
            Instant createdAt, Instant updatedAt, Campaign.Type campaignType) {
        /** 历史幂等回执缺少新字段时补STANDARD，保留原活动身份。 */
        public CampaignView { if (campaignType == null) campaignType = Campaign.Type.STANDARD; }
        static CampaignView from(Campaign campaign) {
            return new CampaignView(campaign.id(), campaign.name(), campaign.objective(), campaign.status(),
                    campaign.createdAt(), campaign.updatedAt(), campaign.campaignType());
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
