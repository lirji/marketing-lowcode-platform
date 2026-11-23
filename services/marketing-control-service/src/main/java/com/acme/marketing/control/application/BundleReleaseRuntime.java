package com.acme.marketing.control.application;

import com.acme.marketing.contracts.release.ReleaseBundleAck;
import com.acme.marketing.contracts.release.ReleaseBundleActivationDirective;
import com.acme.marketing.contracts.release.ReleaseBundleManifest;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 控制面只通过机器接口读取执行事实，准入位置由 Decision 的本地事务独占。 */
public interface BundleReleaseRuntime {
    List<ReleaseBundleAck> prepare(ReleaseBundleManifest bundle);
    Optional<Applied> findApplied(ReleaseBundleActivationDirective directive, Instant confirmAbsentAfter);
    Applied apply(ReleaseBundleActivationDirective directive, long expectedSequence, Instant deadline,
            Runnable finalControlGate);

    /** 读取与写入返回相同的持久结果，历史查询允许游标已前进。 */
    record Applied(String state, ReleaseBundleActivationDirective directive, Instant appliedAt, long currentSequence) {}
}
