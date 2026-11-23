package com.acme.marketing.control.infrastructure;

import com.acme.marketing.control.application.ReleaseBundleError;
import com.nimbusds.jwt.SignedJWT;
import java.text.ParseException;
import java.util.HashMap;
import java.util.Map;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/** 按下游实际使用的 tenant_id 路由机器凭据；签名、权限、有效期仍由下游 OIDC 核验。 */
final class BundleMachineCredential {
    private final Map<String, String> tokens;
    BundleMachineCredential(ObjectMapper mapper, String configured) {
        var values = new HashMap<String, String>();
        if (!configured.isBlank()) {
            try {
                if (configured.stripLeading().startsWith("{")) {
                    var entries = mapper.readTree(configured);
                    if (!entries.isObject() || entries.isEmpty()) throw ReleaseBundleError.RUNTIME_CONFIG.invalid();
                    for (var entry : entries.properties()) {
                        String token = entry.getValue().asString();
                        if (!entry.getKey().equals(tenant(token))) throw ReleaseBundleError.RUNTIME_CONFIG.invalid();
                        values.put(entry.getKey(), token);
                    }
                } else {
                    values.put(tenant(configured), configured);
                }
            } catch (ParseException | JacksonException invalid) {
                // 解析异常可能包含原始输入；只返回配置错误码。
                throw ReleaseBundleError.RUNTIME_CONFIG.invalid();
            }
        }
        this.tokens = Map.copyOf(values);
    }

    private static String tenant(String token) throws ParseException {
        var claims = SignedJWT.parse(token).getJWTClaimsSet();
        String tenant = claims.getStringClaim("tenant_id");
        if (tenant == null || tenant.isBlank()) {
            var properties = claims.getJSONObjectClaim("properties");
            if (properties != null && properties.get("tenant_id") instanceof String value) tenant = value;
        }
        if (tenant == null || tenant.isBlank()) throw ReleaseBundleError.RUNTIME_CONFIG.invalid();
        return tenant.trim();
    }

    /** 单租户令牌绝不能为其他租户的同名权益或资源出具资格判断。 */
    String authorization(String tenant, boolean dev) {
        String token = tokens.get(tenant);
        if (token != null) return "Bearer " + token;
        if (dev && tokens.isEmpty()) return null;
        throw ReleaseBundleError.RUNTIME_CONFIG.conflict();
    }
}
