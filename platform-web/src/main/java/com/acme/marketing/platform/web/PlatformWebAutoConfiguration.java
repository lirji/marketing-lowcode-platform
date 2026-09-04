package com.acme.marketing.platform.web;

import com.acme.marketing.platform.idempotency.IdempotentCommandExecutor;
import java.time.Clock;
import java.time.Duration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

@AutoConfiguration
@EnableConfigurationProperties(MarketingSecurityProperties.class)
public class PlatformWebAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    public Clock marketingClock() {
        return Clock.systemUTC();
    }

    @Bean
    @ConditionalOnMissingBean
    public IdempotentCommandExecutor idempotentCommandExecutor(Clock clock) {
        return new IdempotentCommandExecutor(clock, Duration.ofHours(24));
    }

    @Bean
    public ApiExceptionHandler apiExceptionHandler() {
        return new ApiExceptionHandler();
    }

    @Bean
    public TenantContextFilter tenantContextFilter(MarketingSecurityProperties properties) {
        return new TenantContextFilter(properties);
    }

    @Bean
    public FilterRegistrationBean<TenantContextFilter> disableServletAutoRegistration(TenantContextFilter filter) {
        FilterRegistrationBean<TenantContextFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    @Bean
    @ConditionalOnProperty(name = "marketing.security.mode", havingValue = "DEV", matchIfMissing = true)
    public SecurityFilterChain devSecurityFilterChain(HttpSecurity http, TenantContextFilter tenantFilter)
            throws Exception {
        return http.csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(requests -> requests.anyRequest().permitAll())
                .addFilterAfter(tenantFilter, BearerTokenAuthenticationFilter.class)
                .build();
    }

    @Bean
    @ConditionalOnProperty(name = "marketing.security.mode", havingValue = "OIDC")
    public SecurityFilterChain oidcSecurityFilterChain(HttpSecurity http, TenantContextFilter tenantFilter)
            throws Exception {
        return http.csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(resourceServer -> resourceServer.jwt(Customizer.withDefaults()))
                .addFilterAfter(tenantFilter, BearerTokenAuthenticationFilter.class)
                .build();
    }
}
