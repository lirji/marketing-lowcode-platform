package com.acme.marketing.referral.application.release;

import com.acme.marketing.contracts.release.*;
import com.acme.marketing.platform.identity.TenantScope;
import com.acme.marketing.referral.ReferralReleaseVerifier;
import com.acme.marketing.referral.ReferralRuntimeDirectiveVerifier;
import com.acme.marketing.referral.application.release.ReferralRuntimeRepository.Key;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * 本地指令状态机，显式装配，无自动 Bean/HTTP。测试 READY Port 不代表外部闭包已接通。
 * 本类不会签 ACK 或构造参与许可；LocalGuard 仅供后续适配器增加权威检查后使用。
 */
public final class ReferralRuntimeStateService {
    private final ReferralRuntimeRepository repository;
    private final ReferralReleaseVerifier releases;
    private final ReferralRuntimeDirectiveVerifier directives;
    private final ReferralRuntimeReadinessPort readiness;
    private final Clock clock;
    private final TransactionTemplate transaction;
    private final Duration freshness;
    private final String environment;
    private final String cell;
    private final String namespace;
    private final JsonMapper json = JsonMapper.builder().build();

    /** 缺外部 READY 时传 unavailable()；熔断新鲜窗口有界且不从请求读取。 */
    public ReferralRuntimeStateService(ReferralRuntimeRepository repository, ReferralReleaseVerifier releases,
            ReferralRuntimeDirectiveVerifier directives, ReferralRuntimeReadinessPort readiness, Clock clock,
            PlatformTransactionManager manager, String environment, String cell, String namespace, Duration freshness) {
        this.repository = Objects.requireNonNull(repository); this.releases = Objects.requireNonNull(releases);
        this.directives = Objects.requireNonNull(directives); this.readiness = Objects.requireNonNull(readiness);
        this.clock = Objects.requireNonNull(clock); this.transaction = new TransactionTemplate(manager);
        this.environment = Objects.requireNonNull(environment); this.cell = Objects.requireNonNull(cell); this.namespace = Objects.requireNonNull(namespace);
        require(freshness != null && freshness.compareTo(Duration.ZERO) > 0 && freshness.compareTo(Duration.ofSeconds(10)) <= 0,
                "REFERRAL_KILL_FRESHNESS_INVALID"); this.freshness = freshness;
    }

    /** 激活必须有真实 READY 适配器的有界证明；锁内不访问外部依赖。 */
    public long activate(TenantScope scope, ActivationDirective directive) {
        boundary(scope, "runtime:activate");
        Key key = activationKey(scope.tenantId().value());
        activationSlot(key.tenantId(), directive);
        var installed = repository.installed(key, directive.generation());
        require(installed != null, "REFERRAL_GENERATION_NOT_INSTALLED");
        ReleaseManifest manifest = json.readValue(installed.manifestJson(), ReleaseManifest.class);
        var verified = releases.verifyInstallation(key.tenantId(), installed.releaseKeyId(), manifest, installed.payload(), clock.instant());
        scope.requireOrganization(verified.plan().scope().organizationId()); scope.requireShop(verified.plan().scope().shopId());
        directives.activation(key.tenantId(), installed.releaseKeyId(), manifest, directive, clock.instant());
        var proof = readiness.current(key.tenantId(), manifest);
        proof(proof, manifest, clock.instant());
        return transaction.execute(status -> {
            var current = repository.lock(key);
            // 锁等待后使用原签名精度再验证，防止证明/指令在等待期间过期。
            directives.activation(key.tenantId(), installed.releaseKeyId(), manifest, directive, clock.instant());
            proof(proof, manifest, clock.instant());
            require(directive.activationSequence() >= current.sequence(), "REFERRAL_ACTIVATION_STALE");
            if (directive.activationSequence() == current.sequence()) {
                require(directive.equals(json.readValue(current.directiveJson(), ActivationDirective.class)), "REFERRAL_ACTIVATION_CONFLICT");
            } else repository.advance(key, current.sequence(), directive.activationSequence(), json.writeValueAsString(directive), clock.instant());
            return directive.activationSequence();
        });
    }

    /** 熔断与激活分流，暂停指令不依赖 READY，避免依赖故障阻止紧急停止。 */
    public long applyKill(TenantScope scope, KillSwitchDirective directive) {
        boundary(scope, "runtime:kill-switch");
        Key key = killKey(scope.tenantId().value());
        require(namespace.equals(directive.namespace()), "REFERRAL_KILL_SCOPE_INVALID");
        directives.kill(key.tenantId(), directive, clock.instant());
        return transaction.execute(status -> {
            var current = repository.lock(key);
            directives.kill(key.tenantId(), directive, clock.instant());
            require(directive.switchSequence() >= current.sequence(), "REFERRAL_KILL_STALE");
            if (directive.switchSequence() == current.sequence()) {
                require(directive.equals(json.readValue(current.directiveJson(), KillSwitchDirective.class)), "REFERRAL_KILL_CONFLICT");
            } else repository.advance(key, current.sequence(), directive.switchSequence(), json.writeValueAsString(directive), clock.instant());
            return directive.switchSequence();
        });
    }

    /**
     * 重启后从关系库恢复并重新验签；缺记录/证明/新鲜状态一律拒绝。
     * 返回是一个时点快照，后续参与事务仍需 route epoch 栅栏和活动映射，不能直接当作许可。
     */
    public LocalGuard inspect(TenantScope scope) {
        boundary(scope, "runtime:read");
        String tenant = scope.tenantId().value();
        try {
            var active = repository.current(activationKey(tenant)); var kill = repository.current(killKey(tenant));
            if (active == null || active.sequence() == 0 || kill == null || kill.sequence() == 0) return LocalGuard.denied();
            var activation = json.readValue(active.directiveJson(), ActivationDirective.class);
            var stop = json.readValue(kill.directiveJson(), KillSwitchDirective.class);
            activationSlot(tenant, activation);
            require(namespace.equals(stop.namespace()), "REFERRAL_KILL_SCOPE_INVALID");
            require(active.sequence() == activation.activationSequence() && kill.sequence() == stop.switchSequence(), "REFERRAL_CURSOR_CORRUPT");
            var stored = repository.installed(activationKey(tenant), activation.generation());
            if (stored == null) return LocalGuard.denied();
            var manifest = json.readValue(stored.manifestJson(), ReleaseManifest.class);
            var verified = releases.verifyInstallation(tenant, stored.releaseKeyId(), manifest, stored.payload(), clock.instant());
            scope.requireOrganization(verified.plan().scope().organizationId()); scope.requireShop(verified.plan().scope().shopId());
            // READY 查询可以耗时；所有短期时间检查在其返回之后重新执行。
            var ready = readiness.current(tenant, manifest);
            Instant now = clock.instant();
            directives.activation(tenant, stored.releaseKeyId(), manifest, activation, now); directives.kill(tenant, stop, now);
            proof(ready, manifest, now);
            Instant clearUntil = ReferralRuntimeDirectiveVerifier.clearUntil(stop, freshness, now);
            if (clearUntil == null) return LocalGuard.denied();
            Instant until = min(clearUntil, min(ready.expiresAt(), activation.expiresAt()));
            return new LocalGuard(true, activation.activationSequence(), activation.generation(), until);
        } catch (RuntimeException unavailable) {
            // 不输出签名原文或外部依赖异常；任何不确定性都不能变成开放结果。
            return LocalGuard.denied();
        }
    }

    private static void proof(ReferralRuntimeReadinessPort.Proof proof, ReleaseManifest manifest, Instant now) {
        require(proof != null && manifest.tenantId().value().equals(proof.tenantId()) && manifest.manifestId().equals(proof.manifestId())
                && manifest.generation() == proof.generation() && manifest.signature().equals(proof.manifestSignature())
                && proof.issuedAt() != null && proof.expiresAt() != null && !now.isBefore(proof.issuedAt())
                && now.isBefore(proof.expiresAt()) && proof.expiresAt().isAfter(proof.issuedAt())
                && !proof.expiresAt().isAfter(proof.issuedAt().plusSeconds(10)), "REFERRAL_READY_UNAVAILABLE");
    }
    private void activationSlot(String tenant, ActivationDirective directive) {
        require(tenant.equals(directive.tenantId().value()) && environment.equals(directive.environment())
                && cell.equals(directive.cell()) && namespace.equals(directive.namespace())
                && "referral".equals(directive.runtime()), "REFERRAL_ACTIVATION_SCOPE_INVALID");
    }
    private Key activationKey(String tenant) { return new Key(tenant, "ACTIVATION", environment, cell, namespace); }
    private Key killKey(String tenant) { return new Key(tenant, "KILL", "", "", namespace); }
    private static Instant min(Instant a, Instant b) { return a.isBefore(b) ? a : b; }
    private static void boundary(TenantScope scope, String permission) {
        Objects.requireNonNull(scope).requirePermission(permission);
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("REFERRAL_RUNTIME_REQUIRES_TRANSACTION_BOUNDARY");
    }
    private static void require(boolean valid, String code) { if (!valid) throw new IllegalArgumentException(code); }
    /** 本地可检查快照，不是用户参与许可；没有 campaign、主体或权威绑定信息。 */
    public record LocalGuard(boolean locallyEligible, long activationSequence, long generation, Instant expiresAt) {
        /** 拒绝结果不保留可被误用的版本或期限。 */
        public static LocalGuard denied() { return new LocalGuard(false, 0, 0, null); }
    }
}
