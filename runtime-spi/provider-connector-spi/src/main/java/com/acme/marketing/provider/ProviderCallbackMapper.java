package com.acme.marketing.provider;

import java.util.Map;

public interface ProviderCallbackMapper {
    boolean verify(byte[] body, Map<String, String> headers);

    ProviderCallback map(byte[] body, Map<String, String> headers);
}
