package com.acme.marketing.control.infrastructure;

import com.acme.marketing.control.MarketingControlApplication;
import com.acme.marketing.control.application.ControlRepository;
import com.acme.marketing.control.application.ReleaseAuditArchive;
import com.acme.marketing.control.application.ReleaseOperationAudit;
import com.acme.marketing.control.infrastructure.persistence.mapper.ReleaseAuditArchiveMapper;
import com.acme.marketing.platform.identity.TenantId;
import com.acme.marketing.platform.web.archive.AuditArchiveWorkflow;
import com.acme.marketing.platform.web.archive.AuditArchiveWorkflow.Source;
import com.acme.marketing.platform.web.archive.S3AuditArchiveStorage;
import com.acme.marketing.platform.web.archive.persistence.AuditArchiveManifestMapper;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.executor.impl.XxlJobSpringExecutor;
import com.xxl.job.core.handler.annotation.XxlJob;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/** 发布档案使用自己的 schema、对象前缀和调度身份。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "marketing.release-audit-archive", name = "enabled", havingValue = "true")
@MapperScan(basePackageClasses = {MarketingControlApplication.class, AuditArchiveManifestMapper.class}, annotationClass = Mapper.class)
class ReleaseAuditArchiveConfiguration {
    @Bean
    TenantId releaseArchiveTenant(@Value("${marketing.release-audit-archive.tenant}") String tenant) {
        return new TenantId(tenant);
    }

    @Bean
    S3AuditArchiveStorage releaseArchiveStorage(TenantId releaseArchiveTenant,
            @Value("${marketing.release-audit-archive.endpoint}") URI endpoint,
            @Value("${marketing.release-audit-archive.region}") String region,
            @Value("${marketing.release-audit-archive.bucket}") String bucket,
            @Value("${marketing.release-audit-archive.write-key}") String writeKey,
            @Value("${marketing.release-audit-archive.write-secret}") String writeSecret,
            @Value("${marketing.release-audit-archive.restore-key}") String restoreKey,
            @Value("${marketing.release-audit-archive.restore-secret}") String restoreSecret) {
        return new S3AuditArchiveStorage(endpoint, region, bucket, Source.RELEASE.prefix(releaseArchiveTenant),
                writeKey, writeSecret, restoreKey, restoreSecret);
    }

    @Bean
    AuditArchiveWorkflow releaseArchiveWorkflow(TenantId releaseArchiveTenant, S3AuditArchiveStorage storage, Clock clock,
            AuditArchiveManifestMapper manifest, ReleaseAuditArchiveMapper source, PlatformTransactionManager manager,
            @Value("${marketing.release-audit-archive.minimum-age}") Duration minimumAge) {
        return new AuditArchiveWorkflow(Source.RELEASE, releaseArchiveTenant, minimumAge, clock, manifest,
                (tenant, resource, cutoff, limit) -> {
                    var selection = ReleaseAuditArchive.Selection.parse(resource);
                    return source.eligible(tenant, selection.campaignId(), selection.version(), cutoff, limit);
                }, storage, manager);
    }

    @Bean
    ReleaseAuditArchive releaseAuditArchive(AuditArchiveWorkflow workflow, ControlRepository control, ReleaseOperationAudit audit) {
        return new ReleaseAuditArchive(workflow, control, audit);
    }

    @Bean
    @ConditionalOnProperty(prefix = "marketing.release-audit-archive", name = "scheduler-enabled", havingValue = "true")
    XxlJobSpringExecutor releaseArchiveExecutor(@Value("${marketing.release-audit-archive.scheduler-admin}") String admin,
            @Value("${marketing.release-audit-archive.scheduler-token}") String token,
            @Value("${marketing.release-audit-archive.scheduler-port}") int port) {
        if (admin.isBlank() || token.isBlank() || port < 1024 || port > 65535) {
            throw new IllegalArgumentException("发布归档执行器需要独立调度地址、凭据和端口");
        }
        var executor = new XxlJobSpringExecutor();
        executor.setAdminAddresses(admin);
        executor.setAccessToken(token);
        executor.setAppname("marketing-release-audit-archive");
        executor.setPort(port);
        return executor;
    }

    @Bean
    ReleaseArchiveJobs releaseArchiveJobs(AuditArchiveWorkflow workflow) {
        return new ReleaseArchiveJobs(workflow);
    }

    /** 只接收本来源的精确活动及版本，空参数补办一个待核验批次。 */
    record ReleaseArchiveJobs(AuditArchiveWorkflow workflow) {
        @XxlJob("marketingReleaseAuditArchiveVerify")
        public void verify() {
            XxlJobHelper.log("发布归档核验批次 {}", workflow.verify(XxlJobHelper.getJobParam()));
        }

        @XxlJob("marketingReleaseAuditArchiveCleanRestored")
        public void cleanup() {
            XxlJobHelper.log("发布归档已清理临时副本 {}", workflow.cleanupRestored());
        }
    }
}
