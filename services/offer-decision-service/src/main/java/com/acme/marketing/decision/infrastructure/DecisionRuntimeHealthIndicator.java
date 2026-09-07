package com.acme.marketing.decision.infrastructure;

import com.acme.marketing.decision.runtime.RuntimeManifestRegistry;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Decision 专用 readiness 门禁。进程存活不代表能够决策；只有至少一个已验签、
 * 已激活且未过期的 generation 可路由时，Pod 才能进入 Service endpoints。
 */
@Component("decisionRuntime")
public final class DecisionRuntimeHealthIndicator implements HealthIndicator {
    private final RuntimeManifestRegistry registry;

    public DecisionRuntimeHealthIndicator(RuntimeManifestRegistry registry) {
        this.registry = registry;
    }

    @Override
    public Health health() {
        int usable = registry.usableGenerationCount();
        if (usable < 1) {
            return Health.down()
                    .withDetail("code", "DECISION_GENERATION_NOT_READY")
                    .withDetail("usableGenerations", 0)
                    .build();
        }
        return Health.up().withDetail("usableGenerations", usable).build();
    }
}
