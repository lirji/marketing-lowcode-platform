package com.acme.marketing.compiler.interfaces;

import com.acme.marketing.compiler.application.RuleCompilerService;
import com.acme.marketing.contracts.artifact.ArtifactBundle;
import com.acme.marketing.platform.web.TenantContextHolder;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.Base64;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class CompilerController {
    private final RuleCompilerService service;

    public CompilerController(RuleCompilerService service) {
        this.service = service;
    }

    @PostMapping("/compile")
    public RuleCompilerService.CompileReport compile(
            @Valid @RequestBody RuleCompilerService.CompileRequest request) {
        var scope = TenantContextHolder.requireCurrent();
        scope.requirePermission("definition:compile");
        return service.compile(scope.tenantId().value(), request);
    }

    @GetMapping("/artifacts/{artifactId}")
    public Map<String, Object> artifact(@PathVariable String artifactId) {
        var scope = TenantContextHolder.requireCurrent();
        scope.requirePermission("artifact:read");
        ArtifactBundle artifact = service.artifact(scope.tenantId().value(), artifactId);
        return Map.ofEntries(
                Map.entry("artifactId", artifact.artifactId()),
                Map.entry("definitionId", artifact.definitionId()),
                Map.entry("definitionVersion", artifact.definitionVersion()),
                Map.entry("type", artifact.type()),
                Map.entry("abi", artifact.abi()),
                Map.entry("checksum", artifact.checksum()),
                Map.entry("signatureKeyId", artifact.signatureKeyId()),
                Map.entry("sourceDigest", artifact.sourceDigest()),
                Map.entry("signature", artifact.signature()),
                Map.entry("payload", Base64.getEncoder().encodeToString(artifact.payload())),
                Map.entry("compiledAt", artifact.compiledAt()));
    }
}
