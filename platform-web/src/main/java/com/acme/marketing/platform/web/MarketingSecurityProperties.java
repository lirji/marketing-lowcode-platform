package com.acme.marketing.platform.web;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("marketing.security")
public record MarketingSecurityProperties(Mode mode, boolean devHeadersEnabled) {
    public MarketingSecurityProperties {
        mode = mode == null ? Mode.DEV : mode;
    }

    public enum Mode {
        DEV,
        OIDC
    }
}
