package com.acme.marketing.gateway;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.StringRedisTemplate;

/** Gateway 的集群准入保护配置；过滤器位于认证链之后，租户只能取自已验证上下文。 */
@Configuration
public class GatewayProtectionConfiguration {
    @Bean
    public ClusterRateLimitFilter clusterRateLimitFilter(StringRedisTemplate redis, MeterRegistry meters,
            @Value("${marketing.gateway.rate-limit.enabled:true}") boolean enabled,
            @Value("${marketing.gateway.rate-limit.events-per-second:10000}") long eventRate,
            @Value("${marketing.gateway.rate-limit.events-burst:10000}") long eventBurst,
            @Value("${marketing.gateway.rate-limit.decisions-per-second:30000}") long decisionRate,
            @Value("${marketing.gateway.rate-limit.decisions-burst:30000}") long decisionBurst,
            @Value("${marketing.gateway.rate-limit.writes-per-second:2000}") long writeRate,
            @Value("${marketing.gateway.rate-limit.writes-burst:4000}") long writeBurst,
            @Value("${marketing.gateway.rate-limit.reads-per-second:5000}") long readRate,
            @Value("${marketing.gateway.rate-limit.reads-burst:10000}") long readBurst) {
        return new ClusterRateLimitFilter(redis, meters, enabled, eventRate, eventBurst,
                decisionRate, decisionBurst, writeRate, writeBurst, readRate, readBurst);
    }

    @Bean
    public FilterRegistrationBean<ClusterRateLimitFilter> clusterRateLimitRegistration(
            ClusterRateLimitFilter filter) {
        FilterRegistrationBean<ClusterRateLimitFilter> registration = new FilterRegistrationBean<>(filter);
        // Spring Security（含 TenantContextFilter）默认 order=-100；下游过滤器执行时上下文仍然绑定。
        registration.setOrder(Ordered.LOWEST_PRECEDENCE - 100);
        return registration;
    }
}
