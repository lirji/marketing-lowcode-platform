package com.acme.marketing.benefit.application;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 按租户选择发放主链路。默认模式只允许 LEGACY 或 SHADOW，避免新部署在未灰度时直接真实发奖。
 */
public final class AwardDispatchModeRouter {
    private final DeliveryMode defaultMode;
    private final Map<String, DeliveryMode> tenantModes;

    /** 解析并校验 fail-safe 默认模式及逐租户覆盖配置。 */
    public AwardDispatchModeRouter(String defaultMode, String tenantModes) {
        this.defaultMode = parseMode(defaultMode == null || defaultMode.isBlank() ? "LEGACY" : defaultMode);
        if (this.defaultMode == DeliveryMode.CENTER) {
            throw new IllegalArgumentException("award default mode must be LEGACY or SHADOW");
        }
        this.tenantModes = parseTenantModes(tenantModes);
    }

    /** 返回租户的固定发放模式；只有显式配置的租户能够进入 CENTER。 */
    public DeliveryMode modeFor(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId is required");
        }
        return tenantModes.getOrDefault(tenantId, defaultMode);
    }

    private static Map<String, DeliveryMode> parseTenantModes(String value) {
        if (value == null || value.isBlank()) return Map.of();
        Map<String, DeliveryMode> result = new LinkedHashMap<>();
        for (String entry : value.split(",")) {
            String[] fields = entry.trim().split("=", -1);
            if (fields.length != 2 || fields[0].isBlank() || fields[1].isBlank()) {
                throw new IllegalArgumentException("award tenant modes must use tenant=MODE entries");
            }
            DeliveryMode previous = result.putIfAbsent(fields[0].trim(), parseMode(fields[1]));
            if (previous != null) {
                throw new IllegalArgumentException("award tenant mode is duplicated: " + fields[0].trim());
            }
        }
        return Map.copyOf(result);
    }

    private static DeliveryMode parseMode(String value) {
        try {
            return DeliveryMode.valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("unknown award delivery mode: " + value, invalid);
        }
    }

    public enum DeliveryMode { LEGACY, SHADOW, CENTER }
}
