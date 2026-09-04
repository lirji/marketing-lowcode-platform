package com.acme.marketing.engagement.application;

import java.net.URI;

@FunctionalInterface
public interface ProviderRoute {
    URI endpoint(String tenantId, String channel);
}
