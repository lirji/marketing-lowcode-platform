package com.acme.marketing.journeyservice.infrastructure;

import com.acme.marketing.journey.JourneyReleaseVerifier;
import com.acme.marketing.journeyservice.application.JourneyKillSwitchRegistry;
import com.acme.marketing.journeyservice.application.JourneyRepository;
import com.acme.marketing.platform.crypto.Ed25519;
import com.acme.marketing.platform.crypto.Ed25519KeyPairCodec;
import com.acme.marketing.platform.crypto.SigningKeyRing;
import com.acme.marketing.platform.crypto.TrustedPublicKeys;
import java.security.PublicKey;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.time.Clock;
import tools.jackson.databind.ObjectMapper;

@Configuration
public class JourneyReleaseConfiguration {
    @Bean
    public JourneyReleaseVerifier journeyReleaseVerifier(
            @Value("${marketing.release.trusted-key-id:}") String releaseKeyId,
            @Value("${marketing.release.public-key-base64:}") String releasePublicKey,
            @Value("${marketing.release.trusted-public-keys:}") String additionalReleaseKeys,
            @Value("${marketing.compiler.trusted-key-id:}") String compilerKeyId,
            @Value("${marketing.compiler.public-key-base64:}") String compilerPublicKey,
            @Value("${marketing.compiler.trusted-public-keys:}") String additionalCompilerKeys,
            @Value("${marketing.runtime.environment:local}") String environment,
            @Value("${marketing.runtime.cell:cell-a}") String cell,
            @Value("${marketing.runtime.namespace:main}") String namespace,
            @Value("${marketing.security.mode:DEV}") String securityMode) {
        Map<String, PublicKey> release = trustedKeys(releaseKeyId, releasePublicKey, additionalReleaseKeys);
        Map<String, PublicKey> compiler = trustedKeys(compilerKeyId, compilerPublicKey, additionalCompilerKeys);
        requireTrustInProduction(securityMode, java.util.List.of(release, compiler));
        return new JourneyReleaseVerifier(release, compiler, environment, cell, namespace);
    }

    @Bean
    public JourneyKillSwitchRegistry journeyKillSwitchRegistry(JourneyRepository repository,
            ObjectMapper mapper, Clock clock,
            @Value("${marketing.release.trusted-key-id:}") String releaseKeyId,
            @Value("${marketing.release.public-key-base64:}") String releasePublicKey,
            @Value("${marketing.release.trusted-public-keys:}") String additionalReleaseKeys,
            @Value("${marketing.runtime.namespace:main}") String namespace,
            @Value("${marketing.security.mode:DEV}") String securityMode) {
        Map<String, PublicKey> keys = trustedKeys(releaseKeyId, releasePublicKey, additionalReleaseKeys);
        requireTrustInProduction(securityMode, java.util.List.of(keys));
        return new JourneyKillSwitchRegistry(repository, mapper, clock, keys, namespace);
    }

    @Bean("journeyRuntimeAckSigningKeyRing")
    public SigningKeyRing journeyRuntimeAckSigningKeyRing(
            @Value("${marketing.runtime-ack.signing-key-id:}") String keyId,
            @Value("${marketing.runtime-ack.private-key-base64:}") String privateKey,
            @Value("${marketing.runtime-ack.public-key-base64:}") String publicKey,
            @Value("${marketing.security.mode:DEV}") String securityMode) {
        requireProduction(securityMode, keyId, privateKey, publicKey);
        if (keyId.isBlank() || privateKey.isBlank() || publicKey.isBlank()) {
            return new SigningKeyRing("journey-runtime-ack-dev", Ed25519.generateKeyPair());
        }
        return new SigningKeyRing(keyId, Ed25519KeyPairCodec.decode(privateKey, publicKey));
    }

    private static Map<String, PublicKey> trustedKeys(String keyId, String encoded, String additional) {
        return TrustedPublicKeys.parse(keyId, encoded, additional);
    }

    private static void requireTrustInProduction(String mode,
            java.util.List<Map<String, PublicKey>> keySets) {
        if ("OIDC".equalsIgnoreCase(mode) && keySets.stream().anyMatch(Map::isEmpty)) {
            throw new IllegalStateException("journey runtime trust keys must be configured in OIDC mode");
        }
    }

    private static void requireProduction(String mode, String... values) {
        if ("OIDC".equalsIgnoreCase(mode) && java.util.Arrays.stream(values).anyMatch(String::isBlank)) {
            throw new IllegalStateException("journey runtime signing key must be configured in OIDC mode");
        }
    }
}
