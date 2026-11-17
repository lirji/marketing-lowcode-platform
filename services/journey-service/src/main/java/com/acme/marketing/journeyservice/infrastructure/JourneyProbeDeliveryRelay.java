package com.acme.marketing.journeyservice.infrastructure;

import com.acme.marketing.journeyservice.application.JourneyProbeDeliveryWorker;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.jvm.ExecutorServiceMetrics;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.List;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/** 专用单线程只唤醒数据库任务；队列中至多一个周期任务，不承载业务定时发布。 */
public final class JourneyProbeDeliveryRelay implements SmartLifecycle {
    private static final Logger LOG = LoggerFactory.getLogger(JourneyProbeDeliveryRelay.class);
    private static final long POLL_MILLIS = 250;
    private static final int STOP_SECONDS = 20;
    private final JourneyProbeDeliveryWorker worker;
    private final ObservationRegistry observations;
    private final MeterRegistry meters;
    private ScheduledThreadPoolExecutor executor;
    private volatile boolean running;
    public JourneyProbeDeliveryRelay(JourneyProbeDeliveryWorker worker, ObservationRegistry observations, MeterRegistry meters) {
        this.worker = worker; this.observations = observations; this.meters = meters;
    }
    /** 只开放一个固定延迟入口；不暴露executor给业务提交任意任务。 */
    @Override public synchronized void start() {
        if (running) return;
        executor = new ScheduledThreadPoolExecutor(1, runnable -> new Thread(runnable, "journey-probe-delivery"), new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
        executor.setRemoveOnCancelPolicy(true); executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        executor.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        new ExecutorServiceMetrics(executor, "journey-probe-delivery", List.of()).bindTo(meters);
        running = true;
        executor.scheduleWithFixedDelay(this::tick, POLL_MILLIS, POLL_MILLIS, TimeUnit.MILLISECONDS);
    }
    private void tick() {
        if (!running) return;
        // 非HTTP线程以新的观察范围运行，不复制已结束请求的租户或事务；退出自动恢复上下文。
        Observation.createNotStarted("marketing.journey.probe.relay", observations).observe(() -> {
            try { worker.runOnce(); }
            catch (RuntimeException unavailable) {
                meters.counter("marketing.journey.probe.relay.failure").increment();
                LOG.warn("Journey探针投递批次未提交，保留租约等待恢复；异常类型={}", unavailable.getClass().getSimpleName());
            }
        });
    }
    /** 停止领取并在预算内等待；未完成的持久任务留给下一进程，不能丢弃检查点。 */
    @Override public synchronized void stop() {
        running = false; if (executor == null) return;
        executor.shutdown();
        try { if (!executor.awaitTermination(STOP_SECONDS, TimeUnit.SECONDS)) executor.shutdownNow(); }
        catch (InterruptedException interrupted) { executor.shutdownNow(); Thread.currentThread().interrupt(); }
    }
    @Override public boolean isRunning() { return running; }
    @Override public int getPhase() { return Integer.MAX_VALUE; }
}
