package com.acme.marketing.control.application;

import static com.acme.marketing.control.application.BundleSchedules.State.*;

import com.acme.marketing.platform.error.DomainException;
import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Service;

/** 每次 XXL-JOB 唤醒只推进一个到期任务；远程发布与领取事务相互独立。 */
@Service
public class BundleScheduleWorker {
    private final BundleSchedules schedules;
    private final BundleReleaseOperations operations;
    private final Clock clock;

    public BundleScheduleWorker(BundleSchedules schedules, BundleReleaseOperations operations, Clock clock) {
        this.schedules = schedules; this.operations = operations; this.clock = clock;
    }

    /** 已过期只读回原操作，从而区分“尚未开始”和“应用已提交但响应丢失”。 */
    public Tick tick() {
        var row = schedules.claim();
        if (row == null) return new Tick(false, false);
        var cutoff = Instant.parse(row.applyBefore());
        String key = row.scheduleId();
        BundleReleaseOperations.View result;
        try {
            if (!clock.instant().isBefore(cutoff)) {
                var found = operations.recoverByKey(row.campaignId(), key);
                if (found.isEmpty())
                    return new Tick(true, schedules.finish(row, EXPIRED, null, BundleSchedules.EXPIRED_REASON));
                result = found.get();
            } else {
                result = operations.start(row.campaignId(), key,
                        new BundleReleaseOperations.Request(row.version(),"ACTIVATE", row.expectedAppliedSequence()), cutoff);
            }
        } catch (DomainException unavailable) {
            // 操作可能在另一进程提交；保留原键、触发截止点和可追踪原因，接管时查询原结果。
            return new Tick(true, schedules.finish(row, RUNNING, row.operationId(), unavailable.code()));
        }
        var state = switch (result.state()) {
            case "APPLIED" -> APPLIED;
            case "EXPIRED" -> EXPIRED;
            case "REJECTED" -> REJECTED;
            case "PENDING" -> RUNNING;
            default -> throw new IllegalStateException("发布操作返回未知状态");
        };
        return new Tick(true, schedules.finish(row, state, result.operationId(), result.reasonCode()));
    }
    public record Tick(boolean claimed, boolean recorded) { }
}
