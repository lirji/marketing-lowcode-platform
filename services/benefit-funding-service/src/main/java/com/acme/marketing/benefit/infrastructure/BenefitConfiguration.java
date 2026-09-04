package com.acme.marketing.benefit.infrastructure;

import com.acme.marketing.benefit.application.OfferTokenTrust;
import com.acme.marketing.platform.crypto.TrustedPublicKeys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class BenefitConfiguration {
    @Bean
    public OfferTokenTrust offerTokenTrust(
            @Value("${marketing.offer.trusted-key-id:}") String trustedKeyId,
            @Value("${marketing.offer.public-key-base64:}") String encodedKey,
            @Value("${marketing.offer.trusted-public-keys:}") String additionalKeys,
            @Value("${marketing.security.mode:DEV}") String securityMode) {
        var keys = TrustedPublicKeys.parse(trustedKeyId, encodedKey, additionalKeys);
        if ("OIDC".equalsIgnoreCase(securityMode) && keys.isEmpty()) {
            throw new IllegalStateException("offer verification key must be configured in OIDC mode");
        }
        return keys::get;
    }
}
