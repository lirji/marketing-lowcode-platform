package com.acme.marketing.benefit.infrastructure;

import com.acme.marketing.benefit.application.AwardFulfillmentService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** 沿用现有耐久任务轮询，仅用独立单线程唤醒；数据库租约仲裁多实例。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "marketing.award.fulfillment-enabled", havingValue = "true")
public class AwardFulfillmentWorker {
    private final AwardFulfillmentService service;

    /** 后台任务身份来自持久化记录，不继承某次 HTTP 用户的租户或事务。 */
    public AwardFulfillmentWorker(AwardFulfillmentService service) { this.service = service; }

    /** 显式保留原默认调度器，避免唯一具名调度器被其他 @Scheduled 隐式复用。 */
    @Bean
    public ThreadPoolTaskScheduler taskScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("benefit-scheduled-");
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        scheduler.setAwaitTerminationSeconds(5);
        return scheduler;
    }

    /** 单个周期任务没有业务排队堆积；停机打断在途查询，残余租约可接管。 */
    @Bean
    public ThreadPoolTaskScheduler awardFulfillmentScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("award-fulfillment-");
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        scheduler.setAwaitTerminationSeconds(5);
        return scheduler;
    }

    /** 每轮查询数量、超时和失败次数有界，不阻塞既有投递调度器。 */
    @Scheduled(scheduler = "awardFulfillmentScheduler",
            fixedDelayString = "${marketing.award.fulfillment-delay-ms:5000}",
            initialDelayString = "${marketing.award.fulfillment-initial-delay-ms:5000}")
    public void observe() { service.runBatch(); }
}
