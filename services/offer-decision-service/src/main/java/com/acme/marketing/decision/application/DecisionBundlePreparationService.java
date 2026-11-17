package com.acme.marketing.decision.application;

import com.acme.marketing.contracts.release.PinnedReleaseBundleVerifier;
import com.acme.marketing.contracts.release.ReleaseBundleAck;
import com.acme.marketing.contracts.release.ReleaseBundlePrepareRequest;
import com.acme.marketing.decision.runtime.RuntimeAckFactory;
import com.acme.marketing.decision.runtime.RuntimeManifestRegistry;
import com.acme.marketing.platform.web.TenantContextHolder;
import java.time.Clock;
import org.springframework.stereotype.Service;

/** 仅证明指定组合成员当前可读取，不修改已激活的决策路由。 */
@Service
public class DecisionBundlePreparationService {
    private final PinnedReleaseBundleVerifier verifier;
    private final RuntimeManifestRegistry registry;
    private final RuntimeAckFactory acks;
    private final Clock clock;

    /** 复用既有运行状态库与ACK身份，组合准备不另建状态来源。 */
    public DecisionBundlePreparationService(PinnedReleaseBundleVerifier verifier, RuntimeManifestRegistry registry,
            RuntimeAckFactory acks, Clock clock) {
        this.verifier = verifier; this.registry = registry; this.acks = acks; this.clock = clock;
    }

    /** 验签先于持久化查询，准确成员校验先于签发新协议ACK。 */
    public ReleaseBundleAck prepare(ReleaseBundlePrepareRequest request) {
        var scope = TenantContextHolder.requireCurrent();
        scope.requirePermission("runtime:warm");
        verifier.requireValid(scope.tenantId().value(), request.bundle(), clock.instant());
        var member = request.member();
        registry.verifyInstalledMember(scope.tenantId().value(), member);
        return acks.ready(request.bundle(), member);
    }
}
