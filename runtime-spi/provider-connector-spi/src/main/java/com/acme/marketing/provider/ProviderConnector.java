package com.acme.marketing.provider;

public interface ProviderConnector {
    ProviderResult send(ProviderRequest request, ProviderPolicy policy);
}
