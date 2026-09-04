package com.acme.marketing.control.interfaces;

import com.acme.marketing.contracts.release.RuntimeAck;
import com.acme.marketing.control.application.ControlCommandExecutor;
import com.acme.marketing.control.application.ReleaseApplicationService;
import com.acme.marketing.platform.web.TenantContextHolder;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/releases")
public class ReleaseController {
    private final ReleaseApplicationService service;
    private final ControlCommandExecutor commands;

    public ReleaseController(ReleaseApplicationService service, ControlCommandExecutor commands) {
        this.service = service;
        this.commands = commands;
    }

    @GetMapping
    public java.util.List<ReleaseApplicationService.ReleaseView> manifests() {
        return service.manifests();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ReleaseApplicationService.ReleaseView stage(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody ReleaseApplicationService.StageReleaseRequest request) {
        return commands.execute(TenantContextHolder.requireCurrent().tenantId(), "release.stage", idempotencyKey,
                request, ReleaseApplicationService.ReleaseView.class, () -> service.stage(request));
    }

    @PostMapping("/{manifestId}:ack")
    public ReleaseApplicationService.ReleaseView acknowledge(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @PathVariable String manifestId, @Valid @RequestBody RuntimeAck ack) {
        return commands.execute(TenantContextHolder.requireCurrent().tenantId(), "release.ack", idempotencyKey,
                new CommandPayload(manifestId, ack), ReleaseApplicationService.ReleaseView.class,
                () -> service.acknowledge(manifestId, ack));
    }

    @PostMapping("/{manifestId}:activate")
    public ReleaseApplicationService.ReleaseView activate(
            @RequestHeader("Idempotency-Key") String idempotencyKey, @PathVariable String manifestId) {
        return commands.execute(TenantContextHolder.requireCurrent().tenantId(), "release.activate",
                idempotencyKey, manifestId, ReleaseApplicationService.ReleaseView.class,
                () -> service.activate(manifestId));
    }

    @PostMapping("/{manifestId}:rollback")
    public ReleaseApplicationService.ReleaseView rollback(@PathVariable String manifestId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody RollbackRequest request) {
        return commands.execute(TenantContextHolder.requireCurrent().tenantId(), "release.rollback",
                idempotencyKey, new CommandPayload(manifestId, request),
                ReleaseApplicationService.ReleaseView.class,
                () -> service.rollback(manifestId, request.targetGeneration()));
    }

    @GetMapping("/desired")
    public ReleaseApplicationService.ReleaseView desired(@RequestParam String environment,
            @RequestParam String cell, @RequestParam String runtime, @RequestParam String namespace) {
        return service.desired(environment, cell, runtime, namespace);
    }

    @PutMapping("/kill-switches/{namespace}")
    public ReleaseApplicationService.KillSwitchView killSwitch(@PathVariable String namespace,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody KillSwitchRequest request) {
        return commands.execute(TenantContextHolder.requireCurrent().tenantId(), "release.kill-switch",
                idempotencyKey, new CommandPayload(namespace, request),
                ReleaseApplicationService.KillSwitchView.class,
                () -> service.setKillSwitch(namespace, request.enabled(), request.reason()));
    }

    public record RollbackRequest(@jakarta.validation.constraints.Min(1) long targetGeneration) { }
    public record KillSwitchRequest(boolean enabled, @NotBlank String reason) { }
    private record CommandPayload(String resource, Object request) { }
}
