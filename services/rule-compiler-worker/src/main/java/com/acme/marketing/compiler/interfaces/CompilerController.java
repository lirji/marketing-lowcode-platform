package com.acme.marketing.compiler.interfaces;

import com.acme.marketing.compiler.application.RuleCompilerService;
import com.acme.marketing.contracts.artifact.ArtifactBundle;
import com.acme.marketing.platform.web.TenantContextHolder;
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
    private final com.acme.marketing.compiler.application.ReferralCompileRequestReader requestReader;

    /** 原始 JSON 先做裂变字符串合同检查，避免绑定成 Map 后丢失原类型。 */
    public CompilerController(RuleCompilerService service, tools.jackson.databind.ObjectMapper mapper) {
        this.service = service;
        this.requestReader = new com.acme.marketing.compiler.application.ReferralCompileRequestReader(mapper);
    }

    /** 权限通过后验证原始配置类型，再交给共享编译/签名流程。 */
    @PostMapping("/compile")
    public RuleCompilerService.CompileReport compile(
            @RequestBody tools.jackson.databind.JsonNode request) {
        var scope = TenantContextHolder.requireCurrent();
        scope.requirePermission("definition:compile");
        return service.compile(scope.tenantId().value(), requestReader.read(request));
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
