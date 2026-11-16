package com.acme.marketing.provider;

import java.time.Duration;
import java.util.Set;

/** 渠道调用预算；限速与熔断作用于单进程内的租户、渠道和服务商地址组合。 */
public record ProviderPolicy(
        Duration timeout,
        int maxAttempts,
        Duration initialBackoff,
        Set<Integer> retryableStatusCodes,
        int requestsPerSecond,
        int circuitFailureThreshold,
        Duration circuitOpenDuration) {
    private static final Duration DEFAULT_CIRCUIT_OPEN_DURATION = Duration.ofSeconds(30);

    /** 保留原调用方构造方式，冷却后允许一次恢复探测。 */
    public ProviderPolicy(Duration timeout, int maxAttempts, Duration initialBackoff,
            Set<Integer> retryableStatusCodes, int requestsPerSecond, int circuitFailureThreshold) {
        this(timeout, maxAttempts, initialBackoff, retryableStatusCodes, requestsPerSecond,
                circuitFailureThreshold, DEFAULT_CIRCUIT_OPEN_DURATION);
    }

    /** 拒绝不能恢复或没有有效资源预算的策略。 */
    public ProviderPolicy {
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        if (maxAttempts < 1 || initialBackoff == null || initialBackoff.isNegative()) {
            throw new IllegalArgumentException("invalid retry policy");
        }
        retryableStatusCodes = Set.copyOf(retryableStatusCodes);
        if (requestsPerSecond < 1 || circuitFailureThreshold < 1) {
            throw new IllegalArgumentException("rate and circuit threshold must be positive");
        }
        if (circuitOpenDuration == null || circuitOpenDuration.isNegative() || circuitOpenDuration.isZero()) {
            throw new IllegalArgumentException("circuit open duration must be positive");
        }
    }

    /** 既有渠道的默认调用策略。 */
    public static ProviderPolicy defaults() {
        return new ProviderPolicy(Duration.ofSeconds(2), 3, Duration.ofMillis(100),
                Set.of(408, 425, 429, 500, 502, 503, 504), 100, 5);
    }
}
