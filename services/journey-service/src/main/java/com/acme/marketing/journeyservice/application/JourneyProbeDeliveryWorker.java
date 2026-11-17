package com.acme.marketing.journeyservice.application;

import static com.acme.marketing.platform.time.SqlTime.format;
import static com.acme.marketing.journeyservice.application.JourneyProbeDeliverySettings.*;

import com.acme.marketing.contracts.release.ReleaseBundleProtocolError;
import com.acme.marketing.journeyservice.application.JourneyProbeDeliveryRepository.Completion;
import com.acme.marketing.journeyservice.application.JourneyProbeDeliveryService.Claim;
import com.acme.marketing.platform.error.DomainException;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 基于持久检查点恢复一批；实例崩溃或提交结果未知时由租约接管重发。 */
public final class JourneyProbeDeliveryWorker {
    private static final int MAX_BACKOFF_SECONDS = 16;
    private static final int JITTER_MILLIS = 251;
    private final JourneyProbeDeliveryService service;
    private final JourneyProbeSender sender;
    private final Clock clock;
    private final MeterRegistry meters;
    public JourneyProbeDeliveryWorker(JourneyProbeDeliveryService service, JourneyProbeSender sender, Clock clock, MeterRegistry meters) {
        this.service = service; this.sender = sender; this.clock = clock; this.meters = meters;
    }
    /** 没有任务返回false；发送前后分别提交，禁止调用者用外层事务包住网络等待。 */
    public boolean runOnce() {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw ReleaseBundleProtocolError.EXECUTION_DELIVERY_INVALID.conflict();
        var claimed = service.claim(); if (claimed.isEmpty()) return false;
        Claim claim = claimed.orElseThrow(); JourneyExecutionProbeService.Receipt receipt;
        try { receipt = service.inspect(claim); }
        catch (DomainException rejected) { finish(claim, stopped(claim, JourneyProbeDeliveryState.DEAD, ReleaseBundleProtocolError.EXECUTION_DELIVERY_INVALID)); return true; }
        catch (DataAccessException unavailable) { finish(claim, retry(claim, ReleaseBundleProtocolError.EXECUTION_DELIVERY_STORAGE_UNAVAILABLE)); return true; }
        if (receipt.expired() || !clock.instant().isBefore(receipt.probe().expiresAt())) {
            finish(claim, stopped(claim, JourneyProbeDeliveryState.EXPIRED, ReleaseBundleProtocolError.EXECUTION_DELIVERY_EXPIRED)); return true;
        }
        if (receipt.ready()) {
            finish(claim, new Completion(claim.delivery().nextNonce(), JourneyProbeDeliveryState.COVERED.code(), 0, format(clock.instant()), "")); return true;
        }
        int end = Math.min(claim.delivery().totalRoutes(), claim.delivery().nextNonce() + BATCH_SIZE);
        boolean confirmed = sender.send(claim.delivery().destinationTopic(), receipt.probe(), claim.delivery().nextNonce(), end);
        // 成功发送也不能扩展签名期限；迟到数据库提交仍会被租约条件拒绝。
        if (!clock.instant().isBefore(receipt.probe().expiresAt()))
            finish(claim, stopped(claim, JourneyProbeDeliveryState.EXPIRED, ReleaseBundleProtocolError.EXECUTION_DELIVERY_EXPIRED));
        else if (!confirmed) finish(claim, retry(claim, ReleaseBundleProtocolError.EXECUTION_DELIVERY_UNCONFIRMED));
        else finish(claim, new Completion(end, (end == claim.delivery().totalRoutes() ? JourneyProbeDeliveryState.SENT : JourneyProbeDeliveryState.QUEUED).code(),
                0, format(clock.instant()), ""));
        return true;
    }
    private Completion retry(Claim claim, ReleaseBundleProtocolError error) {
        int failures = claim.delivery().failureCount() + 1;
        Instant now = clock.instant(); long delay = Math.min(MAX_BACKOFF_SECONDS, 1L << Math.min(MAX_FAILURES, failures - 1));
        Instant next = now.plusSeconds(delay).plusMillis(ThreadLocalRandom.current().nextInt(JITTER_MILLIS));
        boolean expired = format(now).compareTo(claim.delivery().expiresAt()) >= 0;
        var state = expired ? JourneyProbeDeliveryState.EXPIRED : failures >= MAX_FAILURES ? JourneyProbeDeliveryState.DEAD : JourneyProbeDeliveryState.RETRY;
        return new Completion(claim.delivery().nextNonce(), state.code(), failures, format(next),
                (expired ? ReleaseBundleProtocolError.EXECUTION_DELIVERY_EXPIRED : error).code());
    }
    private Completion stopped(Claim claim, JourneyProbeDeliveryState state, ReleaseBundleProtocolError error) {
        return new Completion(claim.delivery().nextNonce(), state.code(), claim.delivery().failureCount(), format(clock.instant()), error.code());
    }
    private void finish(Claim claim, Completion completion) {
        // 提交异常向relay传播，不能把内存成功当持久完成；下一进程重查原cursor。
        boolean updated = service.finish(claim, completion);
        meters.counter("marketing.journey.probe.delivery", "result", updated ? completion.state() : "STALE").increment();
    }
}
