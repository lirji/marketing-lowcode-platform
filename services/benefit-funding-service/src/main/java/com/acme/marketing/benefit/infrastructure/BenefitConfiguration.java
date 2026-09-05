package com.acme.marketing.benefit.infrastructure;

import com.acme.marketing.benefit.application.OfferTokenTrust;
import com.acme.marketing.benefit.application.AwardDispatchModeRouter;
import com.acme.marketing.benefit.application.RiskEvaluationGateway;
import com.acme.marketing.platform.crypto.TrustedPublicKeys;
import com.acme.marketing.platform.isolation.TenantBulkhead;
import java.time.Duration;
import tools.jackson.databind.ObjectMapper;
import java.time.Clock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Benefit/Funding 上下文的外部端口、租户隔离和发放 Relay 配置。 */
@Configuration
public class BenefitConfiguration {
    /** 配置权益中台 BFF 客户端及 tenant 隔离的短时 L1 缓存。 */
    @Bean
    public HttpBenefitSkuCatalog benefitSkuCatalog(ObjectMapper mapper,
            @Value("${marketing.benefit-center.base-url:http://127.0.0.1:8183}") String baseUrl,
            @Value("${marketing.benefit-center.bearer-token:}") String bearerToken,
            @Value("${marketing.benefit-center.connect-timeout-ms:2000}") long connectTimeoutMs,
            @Value("${marketing.benefit-center.request-timeout-ms:3000}") long requestTimeoutMs,
            @Value("${marketing.benefit-center.cache-ttl-seconds:20}") long cacheTtlSeconds) {
        return new HttpBenefitSkuCatalog(mapper, baseUrl, bearerToken, Duration.ofMillis(connectTimeoutMs),
                Duration.ofMillis(requestTimeoutMs), Duration.ofSeconds(cacheTtlSeconds));
    }

    /** 构造 OfferToken 验签信任源；生产模式不允许缺少公钥。 */
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

    /** 构造 fail-safe 的租户发放路由；CENTER 只能逐租户显式配置。 */
    @Bean
    public AwardDispatchModeRouter awardDispatchModeRouter(
            @Value("${marketing.award.default-mode:LEGACY}") String defaultMode,
            @Value("${marketing.award.tenant-modes:}") String tenantModes) {
        return new AwardDispatchModeRouter(defaultMode, tenantModes);
    }

    /** 限制单租户并发触发和查询，避免热点租户耗尽服务线程。 */
    @Bean
    public TenantBulkhead awardIntentTenantBulkhead(
            @Value("${marketing.award.bulkhead-permits:64}") int permits,
            @Value("${marketing.award.bulkhead-wait-ms:5}") long waitMs) {
        return new TenantBulkhead(permits, Duration.ofMillis(waitMs));
    }

    /** 构造使用 risk.evaluate 服务身份、独立线程池和 fail-closed 保护的风控端口。 */
    @Bean
    public RiskEvaluationGateway riskEvaluationGateway(ObjectMapper mapper, Clock clock,
            @Value("${marketing.risk.base-url:http://127.0.0.1:8082}") String baseUrl,
            @Value("${marketing.risk.bearer-token:}") String bearerToken,
            @Value("${marketing.security.mode:DEV}") String securityMode,
            @Value("${marketing.risk.connect-timeout-ms:500}") long connectTimeoutMs,
            @Value("${marketing.risk.request-timeout-ms:1000}") long requestTimeoutMs,
            @Value("${marketing.risk.bulkhead-permits:16}") int permits,
            @Value("${marketing.risk.bulkhead-wait-ms:5}") long bulkheadWaitMs,
            @Value("${marketing.risk.thread-count:16}") int threadCount,
            @Value("${marketing.risk.queue-capacity:64}") int queueCapacity,
            @Value("${marketing.risk.circuit-failure-threshold:5}") int circuitFailureThreshold,
            @Value("${marketing.risk.circuit-open-ms:30000}") long circuitOpenMs) {
        if ("OIDC".equalsIgnoreCase(securityMode) && (bearerToken == null || bearerToken.isBlank())) {
            throw new IllegalStateException("risk.evaluate service bearer token must be configured in OIDC mode");
        }
        return new HttpRiskEvaluationGateway(mapper, clock, baseUrl, bearerToken,
                Duration.ofMillis(connectTimeoutMs), Duration.ofMillis(requestTimeoutMs), permits,
                Duration.ofMillis(bulkheadWaitMs), threadCount, queueCapacity, circuitFailureThreshold,
                Duration.ofMillis(circuitOpenMs));
    }

    /** 构造使用服务身份投递权益中台的 AwardIntent Relay。 */
    @Bean
    public AwardIntentRelay awardIntentRelay(JdbcTemplate jdbc, ObjectMapper mapper, Clock clock,
            PlatformTransactionManager transactionManager,
            @Value("${marketing.benefit-center.base-url:http://127.0.0.1:8183}") String baseUrl,
            @Value("${marketing.benefit-center.bearer-token:}") String bearerToken,
            @Value("${marketing.security.mode:DEV}") String securityMode,
            @Value("${marketing.award.relay-enabled:false}") boolean enabled,
            @Value("${marketing.award.relay-batch-size:100}") int batchSize,
            @Value("${marketing.award.relay-tenant-batch-size:10}") int tenantBatchSize,
            @Value("${marketing.award.relay-max-attempts:10}") int maxAttempts,
            @Value("${marketing.award.relay-circuit-failure-threshold:5}") int circuitFailureThreshold,
            @Value("${marketing.benefit-center.connect-timeout-ms:2000}") long connectTimeoutMs,
            @Value("${marketing.benefit-center.request-timeout-ms:3000}") long requestTimeoutMs,
            @Value("${marketing.award.relay-lease-ms:30000}") long leaseMs,
            @Value("${marketing.award.relay-circuit-open-ms:30000}") long circuitOpenMs) {
        if (enabled && "OIDC".equalsIgnoreCase(securityMode)
                && (bearerToken == null || bearerToken.isBlank())) {
            throw new IllegalStateException("award relay service bearer token must be configured in OIDC mode");
        }
        return new AwardIntentRelay(jdbc, mapper, clock, transactionManager, baseUrl, bearerToken,
                enabled, batchSize, tenantBatchSize, maxAttempts, circuitFailureThreshold,
                Duration.ofMillis(connectTimeoutMs), Duration.ofMillis(requestTimeoutMs),
                Duration.ofMillis(leaseMs), Duration.ofMillis(circuitOpenMs));
    }
}
