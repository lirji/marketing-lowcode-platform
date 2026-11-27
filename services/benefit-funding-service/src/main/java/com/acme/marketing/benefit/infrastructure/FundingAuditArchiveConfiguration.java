package com.acme.marketing.benefit.infrastructure;

import com.acme.marketing.benefit.BenefitFundingApplication;
import com.acme.marketing.benefit.application.BenefitFundingService;
import com.acme.marketing.benefit.application.FundingAuditArchive;
import com.acme.marketing.benefit.application.FundingAuditArchiveCutover;
import com.acme.marketing.benefit.application.FundingOperationAudit;
import com.acme.marketing.benefit.infrastructure.persistence.mapper.FundingAuditArchiveMapper;
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

/** 资金档案使用自己的 schema、对象前缀和调度身份。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "marketing.funding-audit-archive", name = "enabled", havingValue = "true")
@MapperScan(basePackageClasses = {BenefitFundingApplication.class, AuditArchiveManifestMapper.class}, annotationClass = Mapper.class)
class FundingAuditArchiveConfiguration {
    @Bean
    TenantId fundingArchiveTenant(@Value("${marketing.funding-audit-archive.tenant}") String tenant) {
        return new TenantId(tenant);
    }

    @Bean
    S3AuditArchiveStorage fundingArchiveStorage(TenantId fundingArchiveTenant,
            @Value("${marketing.funding-audit-archive.endpoint}") URI endpoint,
            @Value("${marketing.funding-audit-archive.region}") String region,
            @Value("${marketing.funding-audit-archive.bucket}") String bucket,
            @Value("${marketing.funding-audit-archive.write-key}") String writeKey,
            @Value("${marketing.funding-audit-archive.write-secret}") String writeSecret,
            @Value("${marketing.funding-audit-archive.restore-key}") String restoreKey,
            @Value("${marketing.funding-audit-archive.restore-secret}") String restoreSecret) {
        return new S3AuditArchiveStorage(endpoint, region, bucket, Source.FUNDING.prefix(fundingArchiveTenant),
                writeKey, writeSecret, restoreKey, restoreSecret);
    }

    @Bean
    AuditArchiveWorkflow fundingArchiveWorkflow(TenantId fundingArchiveTenant, S3AuditArchiveStorage storage, Clock clock,
            AuditArchiveManifestMapper manifest, FundingAuditArchiveMapper source, PlatformTransactionManager manager,
            @Value("${marketing.funding-audit-archive.minimum-age}") Duration minimumAge) {
        return new AuditArchiveWorkflow(Source.FUNDING, fundingArchiveTenant, minimumAge, clock, manifest,
                source::eligible, storage, manager);
    }

    @Bean
    FundingAuditArchive fundingAuditArchive(AuditArchiveWorkflow workflow, BenefitFundingService funding, FundingOperationAudit audit) {
        return new FundingAuditArchive(workflow, funding, audit);
    }

    @Bean
    @ConditionalOnProperty(prefix = "marketing.funding-audit-archive", name = "scheduler-enabled", havingValue = "true")
    XxlJobSpringExecutor fundingArchiveExecutor(@Value("${marketing.funding-audit-archive.scheduler-admin}") String admin,
            @Value("${marketing.funding-audit-archive.scheduler-token}") String token,
            @Value("${marketing.funding-audit-archive.scheduler-port}") int port) {
        if (admin.isBlank() || token.isBlank() || port < 1024 || port > 65535) {
            throw new IllegalArgumentException("资金归档执行器需要独立调度地址、凭据和端口");
        }
        var executor = new XxlJobSpringExecutor();
        executor.setAdminAddresses(admin);
        executor.setAccessToken(token);
        executor.setAppname("marketing-funding-audit-archive");
        executor.setPort(port);
        return executor;
    }

    @Bean
    FundingAuditArchiveCutover fundingAuditArchiveCutover(TenantId tenant, AuditArchiveWorkflow workflow,
            AuditArchiveManifestMapper manifest, FundingAuditArchiveMapper source, S3AuditArchiveStorage storage,
            PlatformTransactionManager manager) {
        return new FundingAuditArchiveCutover(tenant, workflow, manifest, source, storage, manager);
    }

    @Bean
    FundingArchiveJobs fundingArchiveJobs(AuditArchiveWorkflow workflow, FundingAuditArchiveCutover cutover,
            @Value("${marketing.funding-audit-archive.online-cutover-enabled}") boolean cutoverEnabled,
            @Value("${marketing.funding-audit-archive.recovery-replay-enabled}") boolean replayEnabled) {
        if (cutoverEnabled && replayEnabled) {
            throw new IllegalArgumentException("资金审计原库切换与隔离恢复需要独立应用配置");
        }
        return new FundingArchiveJobs(workflow, cutover, cutoverEnabled, replayEnabled);
    }

    /** 只接收本来源的精确申请号，空参数补办一个待核验批次。 */
    record FundingArchiveJobs(AuditArchiveWorkflow workflow, FundingAuditArchiveCutover cutover,
                              boolean cutoverEnabled, boolean replayEnabled) {
        /** 核验当前来源申请或补办待核验档案。 */
        @XxlJob("marketingFundingAuditArchiveVerify")
        public void verify() {
            if (replayEnabled) throw new IllegalStateException("隔离恢复配置只处理备份补放");
            XxlJobHelper.log("资金归档核验批次 {}", workflow.verify(XxlJobHelper.getJobParam()));
        }

        /** 有界删除到期查询副本。 */
        @XxlJob("marketingFundingAuditArchiveCleanRestored")
        public void cleanup() {
            XxlJobHelper.log("资金归档已清理临时副本 {}", workflow.cleanupRestored());
        }

        /** 复核批准、冻结与精确证据后执行已核准的在线副本移出。 */
        @XxlJob("marketingFundingAuditArchiveOnlineCutover")
        public void move() {
            if (!cutoverEnabled) throw new IllegalStateException("资金审计在线切换未启用");
            XxlJobHelper.log("资金归档在线切换结果 {}", cutover.move(XxlJobHelper.getJobParam()));
        }

        /** 恢复中断期间尚未生成外部提交凭证的一项批次。 */
        @XxlJob("marketingFundingAuditArchiveRepairProof")
        public void repair() {
            if (!cutoverEnabled) throw new IllegalStateException("资金审计源库提交凭证补发未启用");
            XxlJobHelper.log("资金归档补发提交凭证批次 {}", cutover.repairNext());
        }

        /** 发现未决准备记录时阻止恢复放行，配置内的当前页结果返回给值班负责人。 */
        @XxlJob("marketingFundingAuditArchiveRecoveryInventory")
        public void inventory() {
            if (!replayEnabled) throw new IllegalStateException("资金审计隔离恢复清点未启用");
            String cursor = XxlJobHelper.getJobParam();
            var result = cutover.inventory(cursor == null || cursor.isBlank() ? null : cursor);
            XxlJobHelper.log("资金审计恢复清点 {}", new tools.jackson.databind.ObjectMapper().writeValueAsString(result));
            if (!result.unresolved().isEmpty()) XxlJobHelper.handleFail("仍有资金审计准备记录等待核对");
        }

        /** 只在隔离库消费明确的版本化提交凭证，不从业务接口触发。 */
        @XxlJob("marketingFundingAuditArchiveReplayRecovery")
        public void replay() {
            if (!replayEnabled) throw new IllegalStateException("资金审计隔离恢复补放未启用");
            var proof = new tools.jackson.databind.ObjectMapper().readValue(XxlJobHelper.getJobParam(),
                    FundingAuditArchiveCutover.Reference.class);
            XxlJobHelper.log("资金审计恢复补放结果 {}", cutover.replay(proof));
        }
    }
}
