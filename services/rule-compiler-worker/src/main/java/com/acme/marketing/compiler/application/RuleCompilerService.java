package com.acme.marketing.compiler.application;

import com.acme.marketing.contracts.artifact.ArtifactBundle;
import com.acme.marketing.contracts.artifact.ArtifactAttestation;
import com.acme.marketing.decision.rule.DecisionTableAdapter;
import com.acme.marketing.decision.rule.RuleEngineAdapter;
import com.acme.marketing.lowcode.compiler.CanonicalGraphHasher;
import com.acme.marketing.lowcode.model.GraphDefinition;
import com.acme.marketing.referral.ReferralPlanCompiler;
import com.acme.marketing.journey.JourneyCompiler;
import com.acme.marketing.platform.crypto.Digests;
import com.acme.marketing.platform.crypto.SigningKeyRing;
import com.acme.marketing.platform.error.NotFoundException;
import com.acme.marketing.rule.dmn.KieDmnDecisionTableAdapter;
import com.acme.marketing.rule.drools.DroolsRuleEngineAdapter;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public final class RuleCompilerService {
    private static final int MAX_SOURCE_BYTES = 1_048_576;
    private final ArtifactStore store;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final SigningKeyRing keys;
    private final RuleEngineAdapter drools = new DroolsRuleEngineAdapter();
    private final DecisionTableAdapter dmn = new KieDmnDecisionTableAdapter();
    private final UntrustedRuleSourcePolicy sourcePolicy = new UntrustedRuleSourcePolicy();

    public RuleCompilerService(ArtifactStore store, ObjectMapper mapper, Clock clock, SigningKeyRing keys) {
        this.store = store;
        this.mapper = mapper;
        this.clock = clock;
        this.keys = keys;
    }

    /** 编译并按既有签名协议保存产物；裂变图必须通过专用纯规则降级，不允许借 GRAPH 格式绕过。 */
    public CompileReport compile(String tenantId, CompileRequest request) {
        if (request.definitionVersion() < 1 || request.definitionId() == null || request.definitionId().isBlank()) {
            throw new IllegalArgumentException("definition identity is required");
        }
        if (request.graph() != null && request.graph().dialect() == com.acme.marketing.lowcode.model.Dialect.REFERRAL_POLICY
                && request.format() != Format.REFERRAL_PLAN) {
            return CompileReport.invalid(List.of("REFERRAL_PLAN_FORMAT_REQUIRED"));
        }
        byte[] executable;
        List<String> messages;
        String abi;
        String sourceDigest;
        switch (request.format()) {
            case GRAPH -> {
                if (request.graph() == null || request.graph().nodes().size() > 500) {
                    return CompileReport.invalid(List.of("GRAPH_REQUIRED_OR_TOO_LARGE"));
                }
                executable = json(request.graph());
                messages = List.of("semanticHash=" + new CanonicalGraphHasher().semanticHash(request.graph()));
                abi = "marketing-graph/1";
                sourceDigest = new CanonicalGraphHasher().semanticHash(request.graph());
            }
            case DRL -> {
                size(request.source());
                try {
                    sourcePolicy.validateDrl(request.source());
                } catch (IllegalArgumentException rejected) {
                    return CompileReport.invalid(List.of(rejected.getMessage()));
                }
                RuleEngineAdapter.CompilationResult result = drools.compile(request.namespace(), request.source(),
                        Duration.ofSeconds(10));
                if (!result.valid()) return CompileReport.invalid(result.messages());
                executable = result.rules().executableModel();
                messages = result.messages();
                abi = result.rules().engineVersion();
                sourceDigest = "sha256:" + Digests.sha256Hex(request.source());
            }
            case DMN -> {
                size(request.source());
                try {
                    sourcePolicy.validateDmn(request.source());
                } catch (IllegalArgumentException rejected) {
                    return CompileReport.invalid(List.of(rejected.getMessage()));
                }
                DecisionTableAdapter.Compilation result = dmn.compile(request.namespace(), request.modelName(),
                        request.source());
                if (!result.valid()) return CompileReport.invalid(result.messages());
                executable = result.table().kjar();
                messages = result.messages();
                abi = "dmn-1.5/kie-10.2";
                sourceDigest = "sha256:" + Digests.sha256Hex(request.source());
            }
            case OFFER_POLICY -> {
                try {
                    executable = json(new OfferPolicyGraphCompiler().compile(request.graph()));
                } catch (RuntimeException invalid) {
                    return CompileReport.invalid(List.of("OFFER_POLICY_LOWERING_FAILED: " + invalid.getMessage()));
                }
                messages = List.of("semanticHash=" + new CanonicalGraphHasher().semanticHash(request.graph()));
                abi = "marketing-offer-policy/1";
                sourceDigest = new CanonicalGraphHasher().semanticHash(request.graph());
            }
            case REFERRAL_PLAN -> {
                if (request.graph() == null || !request.definitionId().equals(request.graph().definitionId()))
                    return CompileReport.invalid(List.of("REFERRAL_GRAPH_IDENTITY_REQUIRED"));
                if (json(request.graph()).length > MAX_SOURCE_BYTES)
                    return CompileReport.invalid(List.of("REFERRAL_GRAPH_TOO_LARGE"));
                try {
                    executable = json(new ReferralPlanCompiler().compile(request.graph()));
                    sourceDigest = new CanonicalGraphHasher().semanticHash(request.graph());
                } catch (RuntimeException invalid) {
                    return CompileReport.invalid(List.of("REFERRAL_PLAN_LOWERING_FAILED: " + invalid.getMessage()));
                }
                messages = List.of("semanticHash=" + sourceDigest);
                abi = "marketing-referral-plan/1";
            }
            case JOURNEY_PLAN -> {
                if (request.graph() == null) return CompileReport.invalid(List.of("JOURNEY_GRAPH_REQUIRED"));
                try {
                    executable = json(new JourneyCompiler().compile(request.graph(), request.definitionVersion()));
                } catch (RuntimeException invalid) {
                    return CompileReport.invalid(List.of("JOURNEY_PLAN_LOWERING_FAILED: " + invalid.getMessage()));
                }
                messages = List.of("semanticHash=" + new CanonicalGraphHasher().semanticHash(request.graph()));
                abi = "marketing-journey-plan/1";
                sourceDigest = new CanonicalGraphHasher().semanticHash(request.graph());
            }
            default -> throw new IllegalStateException("unsupported compiler format");
        }
        String checksum = "sha256:" + Digests.sha256Hex(executable);
        // The attestation is definition/version/key scoped, so its identifier must be scoped as well.
        // Keeping only the payload digest caused an identical executable recompiled for a new approved
        // version (or after key rotation) to collide with stale provenance in the artifact store.
        String identityDigest = Digests.sha256Hex(String.join("|", tenantId, request.definitionId(),
                Long.toString(request.definitionVersion()), request.format().name(), abi, checksum,
                sourceDigest, keys.activeKeyId()));
        String artifactId = request.format().name().toLowerCase(java.util.Locale.ROOT) + '-' + identityDigest;
        String signature = ArtifactAttestation.sign(keys.activePrivateKey(), tenantId, artifactId,
                request.definitionId(), request.definitionVersion(), request.format().name(), abi, checksum,
                sourceDigest);
        ArtifactBundle artifact = new ArtifactBundle(artifactId, tenantId, request.definitionId(),
                request.definitionVersion(), request.format().name(), abi, executable, checksum, sourceDigest,
                keys.activeKeyId(), signature, Map.of("compiler", "rule-compiler-worker/1"), clock.instant());
        ArtifactBundle stored = store.putIfAbsent(artifact);
        return new CompileReport(true, stored.artifactId(), stored.checksum(), stored.signatureKeyId(),
                stored.signature(), stored.abi(), messages);
    }

    public ArtifactBundle artifact(String tenantId, String artifactId) {
        return store.find(tenantId, artifactId)
                .orElseThrow(() -> new NotFoundException("ARTIFACT_NOT_FOUND", "artifact not found"));
    }

    private byte[] json(Object value) {
        try {
            return mapper.writeValueAsBytes(value);
        } catch (JacksonException failure) {
            throw new IllegalArgumentException("graph cannot be serialized", failure);
        }
    }

    private static void size(String source) {
        if (source == null || source.isBlank()
                || source.getBytes(StandardCharsets.UTF_8).length > MAX_SOURCE_BYTES) {
            throw new IllegalArgumentException("compiler source is empty or exceeds one MiB");
        }
    }

    /** 显式产物格式；REFERRAL_PLAN 只能来自严格验证的 REFERRAL_POLICY 图。 */
    public enum Format { GRAPH, DRL, DMN, OFFER_POLICY, JOURNEY_PLAN, REFERRAL_PLAN }
    public record CompileRequest(String definitionId, long definitionVersion, Format format,
            String namespace, String modelName, String source, GraphDefinition graph) { }
    public record CompileReport(boolean valid, String artifactId, String checksum, String signatureKeyId, String signature,
            String abi, List<String> messages) {
        public CompileReport { messages = List.copyOf(messages); }
        static CompileReport invalid(List<String> messages) {
            return new CompileReport(false, null, null, null, null, null, messages);
        }
    }
}
