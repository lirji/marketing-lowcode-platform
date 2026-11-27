package com.acme.marketing.control.application;

import com.acme.marketing.platform.crypto.Digests;
import com.acme.marketing.platform.error.DomainException;
import com.acme.marketing.platform.error.ForbiddenException;
import com.acme.marketing.platform.identity.TenantScope;
import com.acme.marketing.platform.time.SqlTime;
import com.acme.marketing.platform.web.TenantContextHolder;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** 区分入口尝试、控制面提交和运行端结果；提交事实复用原审计链。 */
@Component
public class ReleaseOperationAudit {
    private static final Logger LOG = LoggerFactory.getLogger(ReleaseOperationAudit.class);
    private static final int PAGE_SIZE = 50;
    private final ControlRepository control;
    private final TransactionTemplate independent;

    public ReleaseOperationAudit(ControlRepository control,
            PlatformTransactionManager manager) {
        this.control = control;
        this.independent = new TransactionTemplate(manager);
        independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        independent.setTimeout(3);
    }

    enum Action {
        PUBLISH("release.publish"), ROLLBACK("release.rollback"), RETRY("release.retry"),
        PAUSE_SCHEDULE("release.schedule.pause"), RESUME_SCHEDULE("release.schedule.resume"),
        READ("release.audit.read");

        final String code;
        Action(String code) { this.code = code; }

        static Action publishOrRollback(String action) {
            return "ROLLBACK".equals(action) ? ROLLBACK : PUBLISH;
        }
    }

    /** 未通过权限校验的尝试不在目标活动的历史中显示。 */
    <T> T capture(Action action, Function<Attempt, T> operation) {
        var scope = TenantContextHolder.requireCurrent();
        var attempt = new Attempt(UUID.randomUUID().toString(), scope, action);
        independent.executeWithoutResult(tx -> append(attempt, action, "ATTEMPT", Target.EMPTY,
                null, null, null, null, null, null));
        try {
            return operation.apply(attempt);
        } catch (RuntimeException failure) {
            String stage = failure instanceof ForbiddenException ? "DENIED" : failure instanceof DomainException ? "FAILED" : "UNKNOWN";
            String reason = failure instanceof DomainException domain ? domain.code() : null;
            try {
                independent.executeWithoutResult(tx -> append(attempt, action, stage, attempt.verified,
                        null, null, reason, null, null, null));
            } catch (RuntimeException unavailable) {
                LOG.error("追加营销发布审计失败 attemptId={} errorType={}", attempt.id, unavailable.getClass().getSimpleName());
            }
            throw failure;
        }
    }

    /** 已授权后再给尝试绑定可信归属，禁止以请求自报值写范围。 */
    void verify(Attempt attempt, String campaignId, ControlRepository.CampaignOwnershipRow owner) {
        attempt.verified = new Target(campaignId, owner.organizationId(), owner.shopId(), null, null, null, null);
    }

    Target target(String campaignId, String resourceId, String businessNo, long version, Long sequence) {
        var owner = ControlCampaignScope.require(control, TenantContextHolder.requireCurrent(), campaignId, false);
        return new Target(campaignId, owner.organizationId(), owner.shopId(), resourceId, businessNo, version, sequence);
    }

    /** 使用业务事务追加已确认事实；链节点和状态更新同时提交。 */
    void committed(Attempt attempt, Action action, String stage, Target target, String before,
            String after, String reason, String chainId, Instant observedAt) {
        append(attempt, action, stage, target, before, after, reason, chainId, null, observedAt);
    }

    /** 重复请求引用原活动操作，历史命令允许缺少新版审计事件。 */
    void replayed(Attempt attempt, Target target, String after) {
        append(attempt, attempt.action, "REPLAYED", target, null, after, null, null,
                control.originalReleaseAudit(attempt.scope.tenantId().value(), target.resourceId()), null);
    }

    /** 前置权限和 SQL 同时限制当前租户、组织及门店。 */
    public History history(String campaign, Long before) {
        return capture(Action.READ, attempt -> {
            attempt.scope.requirePermission("release:read");
            verify(attempt, campaign, ControlCampaignScope.require(control, attempt.scope, campaign, false));
            if (before != null && before < 1) throw ReleaseBundleError.OPERATION_INVALID.invalid();
            var verified = attempt.verified;
            var rows = control.releaseAuditHistory(attempt.scope.tenantId().value(), campaign, verified.organizationId(),
                    verified.shopId(), before, PAGE_SIZE + 1);
            var items = rows.stream().limit(PAGE_SIZE).toList();
            independent.executeWithoutResult(tx -> append(attempt, Action.READ, "SUCCEEDED", verified,
                    null, null, null, null, null, null));
            return new History(items, rows.size() > PAGE_SIZE ? String.valueOf(items.getLast().eventId()) : null);
        });
    }

    private void append(Attempt attempt, Action action, String stage, Target target, String before, String after,
            String reason, String chainId, Long original, Instant observedAt) {
        var scope = attempt == null ? TenantContextHolder.requireCurrent() : attempt.scope;
        String actor = scope.actorId().length() <= 128 ? scope.actorId() : "sha256:" + Digests.sha256Hex(scope.actorId());
        String trace = MDC.get("traceId");
        String safeTrace = trace != null && trace.matches("[a-fA-F0-9-]{16,64}") ? trace : null;
        String safeReason = reason != null && reason.matches("[A-Z][A-Z0-9_]{1,95}") ? reason : null;
        int written = control.appendReleaseAudit(new Write(scope.tenantId().value(), attempt == null ? null : attempt.id, actor,
                safeTrace, action.code, stage, target, before, after, safeReason, chainId, original,
                observedAt == null ? null : SqlTime.format(observedAt)));
        if (written != 1) throw new IllegalStateException("营销发布审计写入结果无效");
    }

    static final class Attempt {
        private final String id;
        private final TenantScope scope;
        private final Action action;
        private Target verified = Target.EMPTY;

        private Attempt(String id, TenantScope scope, Action action) {
            this.id = id;
            this.scope = scope;
            this.action = action;
        }
    }

    record Target(String campaignId, String organizationId, String shopId, String resourceId,
            String businessNo, Long version, Long sequence) {
        private static final Target EMPTY = new Target(null, null, null, null, null, null, null);
    }

    public record Write(String tenant, String attemptId, String actorId, String traceId, String action, String stage,
            Target target, String before, String after, String reason, String chainId, Long originalEventId, String observedAt) {}

    public record Entry(long eventId, String attemptId, String actorId, String traceId, String actionCode, String stageCode,
            String resourceId, String businessNo, Long versionNo, Long activationSequence, String beforeState, String afterState,
            String reasonCode, String chainAuditId, Long originalEventId, String observedAt, Instant recordedAt) {}

    public record History(List<Entry> items, String nextCursor) {}
}
