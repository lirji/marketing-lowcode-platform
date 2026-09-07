package com.acme.marketing.decision.infrastructure;

import com.acme.marketing.contracts.artifact.PinnedArtifactVerifier;
import com.acme.marketing.decision.application.DecisionApplicationService;
import com.acme.marketing.decision.runtime.AudienceMembershipProjection;
import com.acme.marketing.decision.runtime.AudienceMembershipStore;
import com.acme.marketing.decision.runtime.DecisionKillSwitchRegistry;
import com.acme.marketing.decision.runtime.DecisionKillSwitchStore;
import com.acme.marketing.decision.runtime.ActivationDirectiveVerifier;
import com.acme.marketing.decision.runtime.ManifestVerifier;
import com.acme.marketing.decision.runtime.PinnedActivationDirectiveVerifier;
import com.acme.marketing.decision.runtime.PinnedManifestVerifier;
import com.acme.marketing.decision.runtime.RuntimeManifestRegistry;
import com.acme.marketing.decision.runtime.RuntimeManifestRegistry.RuntimeSlot;
import com.acme.marketing.decision.runtime.RuntimeStateStore;
import com.acme.marketing.platform.crypto.Ed25519;
import com.acme.marketing.platform.crypto.Ed25519KeyPairCodec;
import com.acme.marketing.platform.crypto.SigningKeyRing;
import com.acme.marketing.platform.crypto.TrustedPublicKeys;
import com.acme.marketing.platform.isolation.TenantBulkhead;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

@Configuration
public class DecisionConfiguration {
    @Bean
    public ManifestVerifier manifestVerifier(
            @Value("${marketing.release.trusted-key-id:}") String keyId,
            @Value("${marketing.release.public-key-base64:}") String encodedKey,
            @Value("${marketing.release.trusted-public-keys:}") String additionalKeys,
            @Value("${marketing.security.mode:DEV}") String securityMode) {
        var keys = trustedKeys(securityMode, "release verification key", keyId, encodedKey, additionalKeys);
        return new PinnedManifestVerifier(keys);
    }

    @Bean
    public ActivationDirectiveVerifier activationDirectiveVerifier(
            @Value("${marketing.release.trusted-key-id:}") String keyId,
            @Value("${marketing.release.public-key-base64:}") String encodedKey,
            @Value("${marketing.release.trusted-public-keys:}") String additionalKeys,
            @Value("${marketing.security.mode:DEV}") String securityMode) {
        var keys = trustedKeys(securityMode, "activation verification key", keyId, encodedKey, additionalKeys);
        return new PinnedActivationDirectiveVerifier(keys);
    }

    @Bean
    public RuntimeManifestRegistry runtimeManifestRegistry(ManifestVerifier verifier,
            ActivationDirectiveVerifier activationVerifier,
            PinnedArtifactVerifier artifactVerifier,
            @Value("${marketing.routing.secret:}") String configuredRoutingSecret,
            @Value("${marketing.security.mode:DEV}") String securityMode,
            @Value("${marketing.runtime.environment:local}") String environment,
            @Value("${marketing.runtime.cell:cell-a}") String cell,
            @Value("${marketing.runtime.namespace:main}") String namespace,
            ObjectMapper mapper, Clock clock, RuntimeStateStore stateStore) {
        requireConfiguredInProduction(securityMode, "routing secret", configuredRoutingSecret);
        String routingSecret = configuredRoutingSecret.isBlank()
                ? "local-routing-secret-change-me" : configuredRoutingSecret;
        return new RuntimeManifestRegistry(verifier, activationVerifier, artifactVerifier,
                routingSecret.getBytes(StandardCharsets.UTF_8),
                new RuntimeSlot(environment, cell, namespace), mapper, clock, stateStore);
    }

    @Bean
    public PinnedArtifactVerifier compilerArtifactVerifier(
            @Value("${marketing.compiler.trusted-key-id:}") String keyId,
            @Value("${marketing.compiler.public-key-base64:}") String encodedKey,
            @Value("${marketing.compiler.trusted-public-keys:}") String additionalKeys,
            @Value("${marketing.security.mode:DEV}") String securityMode) {
        var keys = trustedKeys(securityMode, "compiler verification key", keyId, encodedKey, additionalKeys);
        return new PinnedArtifactVerifier(keys);
    }

    @Bean
    public AudienceMembershipProjection audienceMembershipProjection(AudienceMembershipStore store, Clock clock) {
        return new AudienceMembershipProjection(store, clock);
    }

    @Bean
    public DecisionKillSwitchRegistry decisionKillSwitchRegistry(DecisionKillSwitchStore store,
            ObjectMapper mapper, Clock clock,
            @Value("${marketing.release.trusted-key-id:}") String keyId,
            @Value("${marketing.release.public-key-base64:}") String encodedKey,
            @Value("${marketing.release.trusted-public-keys:}") String additionalKeys,
            @Value("${marketing.runtime.namespace:main}") String namespace,
            @Value("${marketing.security.mode:DEV}") String securityMode) {
        var keys = trustedKeys(securityMode, "kill-switch verification key", keyId, encodedKey, additionalKeys);
        return new DecisionKillSwitchRegistry(store, mapper, clock, keys, namespace);
    }

    @Bean
    public SigningKeyRing offerSigningKeyRing(
            @Value("${marketing.offer.signing-key-id:}") String keyId,
            @Value("${marketing.offer.private-key-base64:}") String privateKey,
            @Value("${marketing.offer.public-key-base64:}") String publicKey,
            @Value("${marketing.security.mode:DEV}") String securityMode) {
        requireConfiguredInProduction(securityMode, "offer signing key", keyId, privateKey, publicKey);
        if (keyId.isBlank() || privateKey.isBlank() || publicKey.isBlank()) {
            return new SigningKeyRing("offer-dev-ephemeral", Ed25519.generateKeyPair());
        }
        return new SigningKeyRing(keyId, Ed25519KeyPairCodec.decode(privateKey, publicKey));
    }

    @Bean("runtimeAckSigningKeyRing")
    public SigningKeyRing runtimeAckSigningKeyRing(
            @Value("${marketing.runtime-ack.signing-key-id:}") String keyId,
            @Value("${marketing.runtime-ack.private-key-base64:}") String privateKey,
            @Value("${marketing.runtime-ack.public-key-base64:}") String publicKey,
            @Value("${marketing.security.mode:DEV}") String securityMode) {
        requireConfiguredInProduction(securityMode, "runtime ACK signing key", keyId, privateKey, publicKey);
        if (keyId.isBlank() || privateKey.isBlank() || publicKey.isBlank()) {
            return new SigningKeyRing("runtime-ack-dev-ephemeral", Ed25519.generateKeyPair());
        }
        return new SigningKeyRing(keyId, Ed25519KeyPairCodec.decode(privateKey, publicKey));
    }

    @Bean
    public TenantBulkhead decisionTenantBulkhead() {
        return new TenantBulkhead(64, Duration.ofMillis(2));
    }

    @Bean
    public DecisionApplicationService decisionApplicationService(RuntimeManifestRegistry manifests,
            AudienceMembershipProjection audiences, @Qualifier("offerSigningKeyRing") SigningKeyRing keys,
            Clock clock, TenantBulkhead bulkhead, DecisionKillSwitchRegistry killSwitch) {
        return new DecisionApplicationService(manifests, audiences, keys, clock, bulkhead,
                killSwitch::requireEnabled);
    }

    private static void requireConfiguredInProduction(String securityMode, String name, String... values) {
        if ("OIDC".equalsIgnoreCase(securityMode)
                && java.util.Arrays.stream(values).anyMatch(String::isBlank)) {
            throw new IllegalStateException(name + " must be configured in OIDC mode");
        }
    }

    private static Map<String, java.security.PublicKey> trustedKeys(String securityMode, String name,
            String keyId, String encodedKey, String additionalKeys) {
        Map<String, java.security.PublicKey> keys = TrustedPublicKeys.parse(keyId, encodedKey, additionalKeys);
        if ("OIDC".equalsIgnoreCase(securityMode) && keys.isEmpty()) {
            throw new IllegalStateException(name + " must be configured in OIDC mode");
        }
        return keys;
    }
}
