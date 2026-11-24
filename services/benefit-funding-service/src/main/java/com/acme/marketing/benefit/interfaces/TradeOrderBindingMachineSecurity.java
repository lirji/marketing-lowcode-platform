package com.acme.marketing.benefit.interfaces;

import com.acme.marketing.benefit.application.TradeOrderBindingMachineAuthority;
import com.acme.marketing.benefit.application.TradeOrderBindingMachineAuthority.Grant;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.web.client.RestTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;

/** 独立验签链只匹配一个 PUT，机器受众无需加入普通资源服务的受众列表。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = {"marketing.trade-order-bindings.enabled",
        "marketing.trade-order-bindings.casdoor.enabled"}, havingValue = "true")
@ConditionalOnProperty(name = "marketing.security.mode", havingValue = "OIDC")
class TradeOrderBindingMachineSecurity {
    private static final int MAX_GRANTS_JSON_LENGTH = 128_000;
    private static final Set<String> LOOPBACK_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]");
    private static final Duration JWKS_TIMEOUT = Duration.ofSeconds(2);

    @Bean
    TradeOrderBindingMachineAuthority tradeOrderBindingMachineAuthority(Clock clock, ObjectMapper json,
            @Value("${marketing.trade-order-bindings.casdoor.grants}") String grantsJson) {
        if (grantsJson.length() > MAX_GRANTS_JSON_LENGTH) {
            throw new IllegalArgumentException("Casdoor 交易订单绑定授权配置过长");
        }
        try {
            List<Grant> grants = json.readerForListOf(Grant.class)
                    .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                            DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .with(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .readValue(grantsJson);
            return new TradeOrderBindingMachineAuthority(clock, grants);
        } catch (JacksonException invalid) {
            throw new IllegalArgumentException("Casdoor 交易订单绑定授权必须为合法的 JSON 配置");
        }
    }

    @Bean
    @Order(1)
    SecurityFilterChain tradeOrderBindingMachineFilterChain(HttpSecurity http,
            @Value("${marketing.trade-order-bindings.casdoor.issuer-uri}") String issuer,
            @Value("${marketing.trade-order-bindings.casdoor.jwk-set-uri}") String jwks) throws Exception {
        requireTrustedUri(issuer);
        requireTrustedUri(jwks);
        var requests = new SimpleClientHttpRequestFactory();
        requests.setConnectTimeout(JWKS_TIMEOUT);
        requests.setReadTimeout(JWKS_TIMEOUT);
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwks).jwsAlgorithm(SignatureAlgorithm.RS256)
                .restOperations(new RestTemplate(requests)).build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(issuer));
        return http.securityMatcher(PathPatternRequestMatcher.withDefaults().matcher(
                        HttpMethod.PUT, TradeOrderBindingMachineController.PATH))
                .csrf(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(sessions -> sessions.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .oauth2ResourceServer(server -> server.jwt(jwt -> jwt.decoder(decoder)))
                .build();
    }

    private static void requireTrustedUri(String value) {
        URI uri = URI.create(value);
        String host = uri.getHost();
        // 明文只允许一次性本机验收签发器，正式身份源使用 HTTPS。
        if (host == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || (!"https".equals(uri.getScheme())
                    && !("http".equals(uri.getScheme()) && LOOPBACK_HOSTS.contains(host)))) {
            throw new IllegalArgumentException("Casdoor 交易订单绑定签发器及 JWKS 必须使用受信 URI");
        }
    }
}
