package com.acme.marketing.referral.application.release;

import com.acme.marketing.contracts.release.ReleaseManifest;
import com.acme.marketing.platform.identity.TenantScope;
import com.acme.marketing.referral.ReferralReleaseVerifier;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * 耐久安装的内部应用入口，只写 VERIFIED 事实。尚未配置 HTTP/Bean 接线，避免误开放发布。
 * 后续预热必须另行完成可信闭包；本类没有 ACK、激活或参与许可的构造能力。
 */
public final class ReferralReleaseInstallationService {
    private final ReferralReleaseVerifier verifier;
    private final ReferralReleaseRepository repository;
    private final Clock clock;
    private final TransactionTemplate transaction;
    private final JsonMapper json = JsonMapper.builder().build();

    /** 显式装配受控信任键与本服务事务管理器，不接受请求提供连接或密钥。 */
    public ReferralReleaseInstallationService(ReferralReleaseVerifier verifier, ReferralReleaseRepository repository,
            Clock clock, PlatformTransactionManager manager) {
        this.verifier = Objects.requireNonNull(verifier);
        this.repository = Objects.requireNonNull(repository);
        this.clock = Objects.requireNonNull(clock);
        this.transaction = new TransactionTemplate(Objects.requireNonNull(manager));
    }

    /**
     * 首次安装及重试都按当前信任键、原始有效期验证；过期制品不能靠历史成功刷新有效期。
     * 验签先于数据库事务，锁后重新检查原始纳秒精度时间，返回只在提交成功后发生。
     */
    public Installation install(TenantScope scope, String releaseKeyId, ReleaseManifest manifest, byte[] payload) {
        Objects.requireNonNull(scope).requirePermission("runtime:warm");
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("REFERRAL_INSTALL_REQUIRES_TRANSACTION_BOUNDARY");
        var verified = verifier.verifyInstallation(scope.tenantId().value(), releaseKeyId, manifest, payload, clock.instant());
        scope.requireOrganization(verified.plan().scope().organizationId());
        scope.requireShop(verified.plan().scope().shopId());
        var proposed = new ReferralReleaseRepository.Stored(scope.tenantId().value(), manifest.environment(),
                manifest.cell(), manifest.namespace(), manifest.generation(), manifest.manifestId(), releaseKeyId,
                json.writeValueAsString(manifest), verified.payload(), clock.instant());
        return transaction.execute(status -> {
            var stored = repository.reserveAndLock(proposed);
            if (!stored.releaseKeyId().equals(releaseKeyId)
                    || !json.readValue(stored.manifestJson(), ReleaseManifest.class).equals(manifest)
                    || !Arrays.equals(stored.payload(), verified.payload()))
                throw new IllegalArgumentException("REFERRAL_GENERATION_CONFLICT");
            Instant now = clock.instant();
            if (now.isBefore(manifest.createdAt()) || !now.isBefore(manifest.expiresAt()))
                throw new IllegalArgumentException("REFERRAL_RELEASE_EXPIRED_DURING_INSTALL");
            return new Installation(stored.manifestId(), stored.generation(), verified.reference().artifactId(),
                    "VERIFIED", stored.verifiedAt());
        });
    }

    /** 审计收据不包含 READY/许可，也不允许改写原代次。 */
    public record Installation(String manifestId, long generation, String artifactId, String state, Instant verifiedAt) { }
}
