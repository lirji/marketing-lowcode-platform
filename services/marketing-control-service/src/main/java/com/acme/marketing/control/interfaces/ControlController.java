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
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
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
        com.acme.marketing.lowcode.validation.ReferralGraphInputGuard.validate(mapper.convertValue(input.graph(), Map.class));
        SaveDefinitionRequest request = new SaveDefinitionRequest(input.campaignId(), mapper.treeToValue(input.graph(), GraphDefinition.class));
        return commands.execute(TenantContextHolder.requireCurrent().tenantId(), "definition.save", idempotencyKey,
                request, ControlApplicationService.DefinitionView.class,
                () -> service.saveDefinition(request.campaignId(), request.graph()));
    }

    @GetMapping("/definitions/latest")
    public ControlApplicationService.DefinitionView latestDefinition(
            @RequestParam String campaignId, @RequestParam com.acme.marketing.lowcode.model.Dialect dialect) {
        return service.latestDefinition(campaignId, dialect);
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
