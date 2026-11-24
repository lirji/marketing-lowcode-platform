package com.acme.marketing.control.interfaces;

import com.acme.marketing.control.application.ControlApplicationService;
import com.acme.marketing.control.application.ControlCommandExecutor;
import com.acme.marketing.control.application.DefaultNodeRegistry;
import com.acme.marketing.control.application.GraphSimulationService;
import com.acme.marketing.control.domain.ApprovalCase;
import com.acme.marketing.lowcode.model.GraphDefinition;
import com.acme.marketing.lowcode.model.NodeDefinition;
import com.acme.marketing.platform.web.TenantContextHolder;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class ControlController {
    private static final String PRODUCT_SCOPE_ID = "[A-Za-z0-9_.:@/-]{1,128}";
    private static final int CAMPAIGN_ID_MAX_LENGTH = 64;
    private static final int INSTANT_MAX_LENGTH = 40;
    private static final int MAX_OFFER_PAGE_SIZE = 50;
    private static final int MAX_COMPARISON_SCENARIOS = 10;
    private final ControlApplicationService service;
    private final DefaultNodeRegistry registry;
    private final ControlCommandExecutor commands;
    private final tools.jackson.databind.ObjectMapper mapper;

    public ControlController(ControlApplicationService service, DefaultNodeRegistry registry,
            ControlCommandExecutor commands, tools.jackson.databind.ObjectMapper mapper) {
        this.service = service;
        this.registry = registry;
        this.commands = commands;
        this.mapper = mapper;
    }

    @PostMapping("/campaigns")
    @ResponseStatus(HttpStatus.CREATED)
    public ControlApplicationService.CampaignView createCampaign(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreateCampaignRequest request) {
        return commands.execute(TenantContextHolder.requireCurrent().tenantId(), "campaign.create", idempotencyKey,
                request.idempotencyPayload(), ControlApplicationService.CampaignView.class,
                () -> service.createCampaign(request.name(), request.objective(), request.organizationId(),
                        request.shopId(), request.campaignType()));
    }

    @GetMapping("/campaigns")
    public List<ControlApplicationService.CampaignView> campaigns(
            @RequestParam(required=false) com.acme.marketing.control.domain.Campaign.Type campaignType) {
        return service.campaigns(campaignType);
    }

    @PostMapping("/definitions")
    @ResponseStatus(HttpStatus.CREATED)
    public ControlApplicationService.DefinitionView saveDefinition(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody SaveDefinitionInput input) {
        var request = definitionRequest(input);
        return commands.execute(TenantContextHolder.requireCurrent().tenantId(), "definition.save", idempotencyKey, request,
                ControlApplicationService.DefinitionView.class,
                () -> service.saveDefinition(request.campaignId(), request.graph()));
    }

    /** 使用独立路径，让旧服务拒绝模板初始化，而不是把它当作无条件保存。 */
    @PostMapping("/definitions/initial-offer")
    @ResponseStatus(HttpStatus.CREATED)
    public ControlApplicationService.DefinitionView initializeOffer(
            @RequestHeader("Idempotency-Key") String idempotencyKey, @Valid @RequestBody SaveDefinitionInput input) {
        service.authorizeOfferInitialization(input.campaignId());
        var request = definitionRequest(input);
        return commands.execute(TenantContextHolder.requireCurrent().tenantId(), "definition.initialize-offer", idempotencyKey,
                request, ControlApplicationService.DefinitionView.class,
                () -> service.initializeOffer(request.campaignId(), request.graph()));
    }

    private SaveDefinitionRequest definitionRequest(SaveDefinitionInput input) {
        com.acme.marketing.lowcode.validation.ReferralGraphInputGuard.validate(mapper.convertValue(input.graph(), Map.class));
        return new SaveDefinitionRequest(input.campaignId(), mapper.treeToValue(input.graph(), GraphDefinition.class));
    }

    @GetMapping("/definitions/latest")
    public ControlApplicationService.DefinitionView latestDefinition(
            @RequestParam String campaignId, @RequestParam com.acme.marketing.lowcode.model.Dialect dialect) {
        return service.latestDefinition(campaignId, dialect);
    }

    /** 版本选择仅需要元数据，分页独立于最新版本读取。 */
    @GetMapping("/definitions/{definitionId}/versions")
    public ControlApplicationService.OfferVersionPage offerVersions(@PathVariable String definitionId,
            @RequestParam @NotBlank @Size(max = CAMPAIGN_ID_MAX_LENGTH) String campaignId,
            @RequestParam(required = false) @Positive Long beforeVersion,
            @RequestParam(defaultValue = "20") @Min(1) @Max(MAX_OFFER_PAGE_SIZE) int limit) {
        return service.offerVersions(campaignId, definitionId, beforeVersion, limit);
    }

    /** 批次为纯计算，用户重试使用同一版本和场景，无需占用命令幂等记录。 */
    @PostMapping("/definitions/{definitionId}/versions:compare-offer")
    public GraphSimulationService.Comparison compareOffers(@PathVariable String definitionId,
            @Valid @RequestBody OfferComparisonInput input) {
        return service.compareOffers(input.campaignId(), definitionId, input.baselineVersion(), input.candidateVersion(),
                input.scenarios().stream().map(OfferComparisonScenario::scenario).toList());
    }

    /** 金额采用人民币分的十进制整数字符串，避免小数截断或浮点舍入。 */
    public record OfferComparisonInput(@NotBlank @Size(max = CAMPAIGN_ID_MAX_LENGTH) String campaignId,
            @NotNull @Positive Long baselineVersion, @NotNull @Positive Long candidateVersion,
            @Valid @NotNull @Size(min = 1, max = MAX_COMPARISON_SCENARIOS) List<@NotNull OfferComparisonScenario> scenarios) { }

    public record OfferComparisonScenario(@NotNull @Pattern(regexp = PRODUCT_SCOPE_ID) String skuId,
            @NotNull @Pattern(regexp = PRODUCT_SCOPE_ID) String shopId,
            @NotNull @Pattern(regexp = "[0-9]{1,14}") String amountMinor,
            @NotBlank @Size(max = INSTANT_MAX_LENGTH) String occurredAt) {
        /** 显式转换为一个时间点，沿用商品试跑的 UTC 契约。 */
        GraphSimulationService.ProductScenario scenario() {
            try {
                return new GraphSimulationService.ProductScenario(skuId, shopId, Long.parseLong(amountMinor),
                        Instant.parse(occurredAt));
            } catch (DateTimeParseException invalid) {
                throw new IllegalArgumentException("offer preview occurredAt must be an ISO-8601 instant", invalid);
            }
        }
    }

    @GetMapping("/definitions/{definitionId}/versions/{version}")
    public ControlApplicationService.DefinitionView definition(
            @PathVariable String definitionId, @PathVariable long version) {
        return service.definition(definitionId, version);
    }

    @PostMapping("/definitions/{definitionId}/versions/{version}:validate")
    public ControlApplicationService.ValidationResult validate(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @PathVariable String definitionId, @PathVariable long version) {
        CommandPayload payload = new CommandPayload(definitionId + ':' + version, null);
        return commands.execute(TenantContextHolder.requireCurrent().tenantId(), "definition.validate",
                idempotencyKey, payload, ControlApplicationService.ValidationResult.class,
                () -> service.validate(definitionId, version));
    }

    @PostMapping("/definitions/{definitionId}/versions/{version}:simulate")
    public GraphSimulationService.SimulationResult simulate(@PathVariable String definitionId, @PathVariable long version,
            @RequestBody Map<String, Object> facts) {
        return service.simulate(definitionId, version, facts);
    }

    @PostMapping("/definitions/{definitionId}/versions/{version}:submit")
    public ControlApplicationService.ApprovalView submit(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @PathVariable String definitionId, @PathVariable long version) {
        CommandPayload payload = new CommandPayload(definitionId + ':' + version, null);
        return commands.execute(TenantContextHolder.requireCurrent().tenantId(), "definition.submit",
                idempotencyKey, payload, ControlApplicationService.ApprovalView.class,
                () -> service.submit(definitionId, version));
    }

    @GetMapping("/definitions/{definitionId}/versions/{version}/terms")
    public ControlApplicationService.TermsView terms(
            @PathVariable String definitionId, @PathVariable long version) {
        return service.terms(definitionId, version);
    }

    @GetMapping("/approvals")
    public List<ControlApplicationService.ApprovalView> approvals() {
        return service.approvals();
    }

    /** 新分页契约独立于旧数组接口，旧客户端可在兼容窗口内继续读取最近记录。 */
    @GetMapping("/approvals/page")
    public ControlApplicationService.ApprovalPage approvalPage(@RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String status, @RequestParam(required = false) String cursor) {
        return service.approvalPage(limit, status, cursor);
    }

    @PostMapping("/approvals/{caseId}/decisions")
    public ControlApplicationService.ApprovalView decide(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @PathVariable String caseId, @Valid @RequestBody ApprovalRequest request) {
        return commands.execute(TenantContextHolder.requireCurrent().tenantId(), "approval.decide", idempotencyKey,
                new CommandPayload(caseId, request), ControlApplicationService.ApprovalView.class,
                () -> service.decide(caseId, request.role(), request.decision(), request.comment()));
    }

    @GetMapping("/registries/nodes")
    public List<NodeDefinition> nodeRegistry() {
        TenantContextHolder.requireCurrent().requirePermission("definition:read");
        return registry.all();
    }

    public record CreateCampaignRequest(@NotBlank String name, @NotBlank String objective,
            @NotBlank String organizationId, String shopId,
            com.acme.marketing.control.domain.Campaign.Type campaignType) {
        /** 缺省与显式STANDARD语义一致，继续使用旧四字段幂等载荷。 */
        public CreateCampaignRequest {
            if (campaignType == null) campaignType = com.acme.marketing.control.domain.Campaign.Type.STANDARD;
        }
        Object idempotencyPayload() {
            return campaignType == com.acme.marketing.control.domain.Campaign.Type.STANDARD
                    ? new LegacyCampaignPayload(name, objective, organizationId, shopId) : this;
        }
    }
    /** 保持历史JSON字段顺序和null写法，避免升级使未过期幂等请求冲突。 */
    private record LegacyCampaignPayload(String name, String objective, String organizationId, String shopId) { }
    /** 保留原始图值类型直到共享检查完成；不能在Map<String,String>绑定后检查。 */
    public record SaveDefinitionInput(@NotBlank String campaignId, @NotNull tools.jackson.databind.JsonNode graph) { }
    public record SaveDefinitionRequest(@NotBlank String campaignId, @NotNull GraphDefinition graph) { }
    public record ApprovalRequest(@NotNull ApprovalCase.Role role,
            ControlApplicationService.ApprovalDecision decision, @Size(max = 128) String comment) {
        public ApprovalRequest {
            decision = decision == null ? ControlApplicationService.ApprovalDecision.APPROVE : decision;
            comment = comment == null ? "" : comment;
        }
    }
    private record CommandPayload(String resource, Object request) { }
}
