package com.acme.marketing.decision.application;

import com.acme.marketing.contracts.release.PinnedReleaseBundleActivationVerifier;
import com.acme.marketing.contracts.release.PinnedReleaseBundleParticipationVerifier;
import com.acme.marketing.contracts.release.ReleaseBundleActivationDirective;
import com.acme.marketing.contracts.release.ReleaseBundleParticipationSigner;
import com.acme.marketing.contracts.release.ReleaseBundleProtocolError;
import com.acme.marketing.decision.runtime.RuntimeManifestRegistry;
import com.acme.marketing.platform.crypto.TrustedPublicKeys;
import com.acme.marketing.platform.identity.TenantScope;
import java.security.PublicKey;
import java.time.Instant;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;

/** 按已签名参与读取本站精确成员；没有任何latest回退或新准入签发副作用。 */
@Component
public final class DecisionBundlePlanResolver {
    private static final String RUNTIME_NAME = "decision";
    private final BundleDirectiveRepository repository;
    private final RuntimeManifestRegistry registry;
    private final ObjectMapper mapper;
    private final Map<String, PublicKey> participationKeys;
    private final PinnedReleaseBundleParticipationVerifier verifier;
    private final RuntimeManifestRegistry.RuntimeSlot slot;

    /** 参与角色缺省无信任；不影响旧路径启动，也不因此接受其他角色签名。 */
    public DecisionBundlePlanResolver(BundleDirectiveRepository repository, RuntimeManifestRegistry registry,
            ObjectMapper mapper, PinnedReleaseBundleActivationVerifier activationVerifier,
            @Value("${marketing.participation.trusted-key-id:}") String keyId,
            @Value("${marketing.participation.public-key-base64:}") String encodedKey,
            @Value("${marketing.participation.trusted-public-keys:}") String additionalKeys,
            @Value("${marketing.runtime.environment:local}") String environment,
            @Value("${marketing.runtime.cell:cell-a}") String cell,
            @Value("${marketing.runtime.namespace:main}") String namespace) {
        this.repository = repository; this.registry = registry; this.mapper = mapper;
        this.participationKeys = TrustedPublicKeys.parse(keyId, encodedKey, additionalKeys);
        this.verifier = new PinnedReleaseBundleParticipationVerifier(participationKeys, activationVerifier);
        this.slot = new RuntimeManifestRegistry.RuntimeSlot(environment, cell, namespace);
    }

    /** 先验证参与来源再查本库；主体/组织/店铺不能从待验凭证中推导为可信输入。 */
    public RuntimeManifestRegistry.GenerationSnapshot resolve(TenantScope scope,
            DecisionApplicationService.DecisionRequest request, Instant now) {
        var context = request.bundleContext();
        if (context == null) throw ReleaseBundleProtocolError.PARTICIPATION_INVALID.conflict();
        var proof = context.participation();
        if (!scope.tenantId().equals(proof.tenantId()) || !slot.environment().equals(proof.environment())
                || !slot.cell().equals(proof.cell())
                || !ReleaseBundleParticipationSigner.verify(participationKeys.get(proof.signatureKeyId()), proof))
            throw ReleaseBundleProtocolError.PARTICIPATION_INVALID.conflict();
        var key = new BundleDirectiveRepository.Key(scope.tenantId().value(), slot.environment(), slot.cell(),
                slot.namespace(), context.campaignId());
        var stored = repository.history(key, proof.activationSequence())
                .orElseThrow(ReleaseBundleProtocolError.ACTIVATION_NOT_RECORDED::conflict);
        ReleaseBundleActivationDirective directive = read(stored);
        verifier.requireValid(new PinnedReleaseBundleParticipationVerifier.ExpectedContext(scope.tenantId(),
                context.campaignId(), slot.environment(), slot.cell(), context.participationId(), request.subjectToken()), proof, directive, now);
        if (!request.organizationId().equals(directive.organizationId())
                || (!directive.shopId().isEmpty() && request.cart().lines().stream().anyMatch(line -> !directive.shopId().equals(line.shopId()))))
            throw ReleaseBundleProtocolError.PARTICIPATION_INVALID.conflict();
        scope.requireOrganization(directive.organizationId());
        if (!directive.shopId().isEmpty()) scope.requireShop(directive.shopId());
        var member = directive.bundle().members().stream().filter(value -> RUNTIME_NAME.equals(value.runtime()) && slot.matches(value))
                .findFirst().orElseThrow(ReleaseBundleProtocolError.MEMBER_MISMATCH::conflict);
        return registry.verifyInstalledMemberAt(scope.tenantId().value(), member, proof.admittedAt());
    }

    private ReleaseBundleActivationDirective read(BundleDirectiveRepository.StoredDirective stored) {
        try {
            if (stored.directiveJson().length() > DecisionBundleDirectiveService.MAX_REQUEST_BYTES)
                throw ReleaseBundleProtocolError.ACTIVATION_STORED_INVALID.conflict();
            ReleaseBundleActivationDirective value = mapper.readerFor(ReleaseBundleActivationDirective.class)
                    .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readValue(stored.directiveJson());
            if (value == null || value.activationSequence() != stored.activationSequence() || !value.directiveId().equals(stored.directiveId()))
                throw ReleaseBundleProtocolError.ACTIVATION_STORED_INVALID.conflict();
            return value;
        } catch (JacksonException | IllegalArgumentException corrupt) { throw ReleaseBundleProtocolError.ACTIVATION_STORED_INVALID.conflict(); }
    }
}
