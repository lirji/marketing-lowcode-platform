package com.acme.marketing.compiler.infrastructure;

import com.acme.marketing.platform.crypto.Ed25519;
import com.acme.marketing.platform.crypto.Ed25519KeyPairCodec;
import com.acme.marketing.platform.crypto.SigningKeyRing;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CompilerConfiguration {
    /** JSON 转成 Map 前拒绝重复属性，防止两个 role/ruleId 静默覆盖后被编译器签名。 */
    @Bean
    public org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer compilerStrictJson() {
        return builder -> builder.enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION);
    }

    @Bean
    public SigningKeyRing compilerSigningKeyRing(
            @Value("${marketing.compiler.signing-key-id:}") String keyId,
            @Value("${marketing.compiler.private-key-base64:}") String privateKey,
            @Value("${marketing.compiler.public-key-base64:}") String publicKey,
            @Value("${marketing.security.mode:DEV}") String securityMode) {
        if ("OIDC".equalsIgnoreCase(securityMode)
                && (keyId.isBlank() || privateKey.isBlank() || publicKey.isBlank())) {
            throw new IllegalStateException("compiler signing key must be configured in OIDC mode");
        }
        if (keyId.isBlank() || privateKey.isBlank() || publicKey.isBlank()) {
            return new SigningKeyRing("compiler-dev-ephemeral", Ed25519.generateKeyPair());
        }
        return new SigningKeyRing(keyId, Ed25519KeyPairCodec.decode(privateKey, publicKey));
    }

    @Bean
    public Clock compilerClock() {
        return Clock.systemUTC();
    }
}
