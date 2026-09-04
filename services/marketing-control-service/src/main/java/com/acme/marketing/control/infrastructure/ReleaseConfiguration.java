package com.acme.marketing.control.infrastructure;

import com.acme.marketing.contracts.artifact.PinnedArtifactVerifier;
import com.acme.marketing.contracts.release.PinnedRuntimeAckVerifier;
import com.acme.marketing.platform.crypto.Ed25519;
import com.acme.marketing.platform.crypto.Ed25519KeyPairCodec;
import com.acme.marketing.platform.crypto.SigningKeyRing;
import com.acme.marketing.platform.crypto.TrustedPublicKeys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ReleaseConfiguration {
    @Bean
    public PinnedRuntimeAckVerifier runtimeAckVerifier(
            @Value("${marketing.runtime-ack.trusted-key-id:}") String keyId,
            @Value("${marketing.runtime-ack.public-key-base64:}") String publicKey,
            @Value("${marketing.runtime-ack.trusted-public-keys:}") String additionalKeys,
            @Value("${marketing.security.mode:DEV}") String securityMode) {
        var keys = TrustedPublicKeys.parse(keyId, publicKey, additionalKeys);
        if ("OIDC".equalsIgnoreCase(securityMode) && keys.isEmpty()) {
            throw new IllegalStateException("runtime ACK verification key must be configured in OIDC mode");
        }
        return new PinnedRuntimeAckVerifier(keys);
    }

    @Bean
    public PinnedArtifactVerifier compilerArtifactVerifier(
            @Value("${marketing.compiler.trusted-key-id:}") String keyId,
            @Value("${marketing.compiler.public-key-base64:}") String publicKey,
            @Value("${marketing.compiler.trusted-public-keys:}") String additionalKeys,
            @Value("${marketing.security.mode:DEV}") String securityMode) {
        var keys = TrustedPublicKeys.parse(keyId, publicKey, additionalKeys);
        if ("OIDC".equalsIgnoreCase(securityMode) && keys.isEmpty()) {
            throw new IllegalStateException("compiler verification key must be configured in OIDC mode");
        }
        return new PinnedArtifactVerifier(keys);
    }

    @Bean
    public SigningKeyRing releaseSigningKeyRing(
            @Value("${marketing.release.signing-key-id:}") String keyId,
            @Value("${marketing.release.private-key-base64:}") String privateKey,
            @Value("${marketing.release.public-key-base64:}") String publicKey,
            @Value("${marketing.security.mode:DEV}") String securityMode) {
        if ("OIDC".equalsIgnoreCase(securityMode)
                && (keyId.isBlank() || privateKey.isBlank() || publicKey.isBlank())) {
            throw new IllegalStateException("release signing key must be configured in OIDC mode");
        }
        if (keyId.isBlank() || privateKey.isBlank() || publicKey.isBlank()) {
            return new SigningKeyRing("release-dev-ephemeral", Ed25519.generateKeyPair());
        }
        return new SigningKeyRing(keyId, Ed25519KeyPairCodec.decode(privateKey, publicKey));
    }

}
