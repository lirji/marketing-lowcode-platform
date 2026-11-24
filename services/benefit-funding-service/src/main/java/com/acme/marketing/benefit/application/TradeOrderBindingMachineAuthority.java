package com.acme.marketing.benefit.application;

import com.acme.marketing.platform.identity.TenantId;
import com.acme.marketing.platform.identity.TenantScope;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.security.oauth2.jwt.Jwt;

/** 将通过专用安全链验证的 Casdoor 应用身份映射到本服务批准的绑定范围。 */
public final class TradeOrderBindingMachineAuthority {
    private static final Duration MAX_TOKEN_LIFETIME = Duration.ofMinutes(5);
    private static final int MAX_GRANTS = 100;
    private static final int MAX_SHOPS = 100;
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}");
    private static final Pattern SUBJECT = Pattern.compile("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+");

    private final Clock clock;
    private final Map<String, AuthorizedMachine> grants;

    /** 每个受众只能有一个授权范围，防止请求通过选择租户或门店扩大权限。 */
    public TradeOrderBindingMachineAuthority(Clock clock, List<Grant> configuredGrants) {
        if (configuredGrants == null || configuredGrants.isEmpty() || configuredGrants.size() > MAX_GRANTS) {
            throw new IllegalArgumentException("Casdoor 交易订单绑定需要配置 1 至 100 个机器授权");
        }
        this.clock = clock;
        Map<String, AuthorizedMachine> approved = new HashMap<>();
        for (Grant grant : configuredGrants) {
            if (grant == null || !identifier(grant.clientId()) || !identifier(grant.organizationId())
                    || grant.marketingTenantId() == null
                    || grant.subject() == null || grant.subject().length() > 255
                    || !SUBJECT.matcher(grant.subject()).matches()
                    || grant.shopIds() == null || grant.shopIds().isEmpty() || grant.shopIds().size() > MAX_SHOPS
                    || !grant.shopIds().stream().allMatch(TradeOrderBindingMachineAuthority::identifier)
                    || Set.copyOf(grant.shopIds()).size() != grant.shopIds().size()) {
                throw new IllegalArgumentException("Casdoor 交易订单绑定机器授权字段无效");
            }
            TenantScope scope = new TenantScope(new TenantId(grant.marketingTenantId()), Set.of(grant.organizationId()),
                    Set.copyOf(grant.shopIds()), grant.subject(), Set.of(TradeOrderBindingService.PERMISSION));
            long sourceTenant = TradeOrderBindingService.nativeId(grant.sourceTenantId(), "sourceTenantId");
            if (approved.putIfAbsent(grant.clientId(), new AuthorizedMachine(scope, sourceTenant)) != null) {
                throw new IllegalArgumentException("Casdoor 交易订单绑定的客户端授权不能重复");
            }
        }
        this.grants = Map.copyOf(approved);
    }

    /** 只认验签后的应用访问令牌，JWT 中请求的 scope 和业务范围不参与授权。 */
    public AuthorizedMachine authorize(Jwt jwt) {
        if (jwt == null || jwt.getAudience() == null || jwt.getAudience().size() != 1
                || !identifier(jwt.getAudience().getFirst())) {
            throw TradeOrderBindingService.forbidden();
        }
        String clientId = jwt.getAudience().getFirst();
        var machine = grants.get(clientId);
        if (machine == null || !clientId.equals(jwt.getClaims().get("azp"))
                || !machine.scope.actorId().equals(jwt.getSubject())
                || !"application".equals(jwt.getClaims().get("type"))
                || !"access-token".equals(jwt.getClaims().get("tokenType"))
                || !isCurrent(jwt, clock.instant())) {
            throw TradeOrderBindingService.forbidden();
        }
        return machine;
    }

    private static boolean isCurrent(Jwt jwt, Instant now) {
        Instant issued = jwt.getIssuedAt();
        Instant expires = jwt.getExpiresAt();
        Instant notBefore = jwt.getNotBefore();
        return issued != null && !issued.isAfter(now) && expires != null && expires.isAfter(now)
                && Duration.between(issued, expires).compareTo(MAX_TOKEN_LIFETIME) <= 0
                && (notBefore == null || !notBefore.isAfter(now));
    }

    private static boolean identifier(String value) {
        return value != null && IDENTIFIER.matcher(value).matches();
    }

    /** 配置仅保存公开身份与授权标识，客户端密钥留在调用方。 */
    public record Grant(String clientId, String subject, String marketingTenantId, String sourceTenantId,
            String organizationId, List<String> shopIds) { }

    /** 仅授权表可构造此对象，绑定事务消费已经确认的范围。 */
    public static final class AuthorizedMachine {
        private final TenantScope scope;
        private final long sourceTenantId;

        private AuthorizedMachine(TenantScope scope, long sourceTenantId) {
            this.scope = scope;
            this.sourceTenantId = sourceTenantId;
        }

        TenantScope scope() {
            return scope;
        }

        long sourceTenantId() {
            return sourceTenantId;
        }
    }
}
