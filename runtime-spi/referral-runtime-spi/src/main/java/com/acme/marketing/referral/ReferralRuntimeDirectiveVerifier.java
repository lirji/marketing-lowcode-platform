package com.acme.marketing.referral;

import com.acme.marketing.contracts.release.*;
import java.security.PublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/** 本地运行指令信任边界；不把有效激活签名当成 READY 或用户参与授权。 */
public final class ReferralRuntimeDirectiveVerifier {
    private final Map<String, PublicKey> keys;
    private final String environment;
    private final String cell;
    private final String namespace;

    /** 固定部署槽位及受控公钥，不从消息内 URI 获取信任。 */
    public ReferralRuntimeDirectiveVerifier(Map<String, PublicKey> keys, String environment, String cell, String namespace) {
        this.keys = Map.copyOf(keys);
        this.environment = required(environment); this.cell = required(cell); this.namespace = required(namespace);
    }

    /** 验证激活与已安装清单的完整绑定；回滚可使用旧 generation，但序号由数据库保证递增。 */
    public void activation(String tenant, String manifestKeyId, ReleaseManifest manifest, ActivationDirective directive, Instant now) {
        var key = keys.get(directive.signatureKeyId());
        var manifestKey = keys.get(manifestKeyId);
        require(key != null && ActivationDirectiveSigner.verify(key, directive)
                && manifestKey != null && new ReleaseManifestSigner().verify(manifest, manifestKey), "REFERRAL_ACTIVATION_UNTRUSTED");
        require(tenant.equals(directive.tenantId().value()) && tenant.equals(manifest.tenantId().value())
                && slot(directive.environment(), directive.cell(), directive.runtime(), directive.namespace())
                && slot(manifest.environment(), manifest.cell(), manifest.runtime(), manifest.namespace()), "REFERRAL_ACTIVATION_SCOPE_INVALID");
        require(directive.manifestId().equals(manifest.manifestId()) && directive.manifestSignature().equals(manifest.signature())
                && directive.generation() == manifest.generation() && directive.stableGeneration() == directive.generation()
                && directive.canaryBasisPoints() == 0 && manifest.canaryBasisPoints() == 0
                && manifest.canaryGenerations().isEmpty(), "REFERRAL_ACTIVATION_BINDING_INVALID");
        require(!now.isBefore(directive.activatedAt()) && now.isBefore(directive.expiresAt())
                && !now.isBefore(manifest.createdAt()) && !now.isBefore(manifest.activationAt())
                && !directive.activatedAt().isBefore(manifest.activationAt())
                && !directive.expiresAt().isAfter(manifest.expiresAt()), "REFERRAL_ACTIVATION_TIME_INVALID");
    }

    /** 熔断启用无自动失效；关闭状态的新鲜期限由原签名时间决定，不能被投递时间刷新。 */
    public void kill(String tenant, KillSwitchDirective directive, Instant now) {
        var key = keys.get(directive.signatureKeyId());
        require(key != null && KillSwitchDirectiveSigner.verify(key, directive), "REFERRAL_KILL_UNTRUSTED");
        require(tenant.equals(directive.tenantId().value()) && namespace.equals(directive.namespace()), "REFERRAL_KILL_SCOPE_INVALID");
        require(!now.isBefore(directive.activatedAt()), "REFERRAL_KILL_TIME_INVALID");
    }

    /** 仅判断已验证指令的本地新鲜关闭状态，健康水位协议接通前不推断持续开放。 */
    public static Instant clearUntil(KillSwitchDirective directive, Duration freshness, Instant now) {
        require(freshness != null && !freshness.isNegative() && !freshness.isZero()
                && freshness.compareTo(Duration.ofSeconds(10)) <= 0, "REFERRAL_KILL_FRESHNESS_INVALID");
        Instant until = directive.activatedAt().plus(freshness);
        return !directive.enabled() && !now.isBefore(directive.activatedAt()) && now.isBefore(until) ? until : null;
    }

    private boolean slot(String env, String candidateCell, String runtime, String candidateNamespace) {
        return environment.equals(env) && cell.equals(candidateCell) && "referral".equals(runtime) && namespace.equals(candidateNamespace);
    }
    private static String required(String value) { require(value != null && !value.isBlank(), "REFERRAL_SLOT_REQUIRED"); return value; }
    private static void require(boolean valid, String code) { if (!valid) throw new IllegalArgumentException(code); }
}
