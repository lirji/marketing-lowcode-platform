package com.acme.marketing.control.application;

import com.acme.marketing.platform.web.TenantContextHolder;
import com.acme.marketing.platform.web.archive.AuditArchiveWorkflow;

/** 发布档案按活动与版本重新核对当前归属，运行回执和原链引用随档案回读。 */
public final class ReleaseAuditArchive {
    private final AuditArchiveWorkflow workflow;
    private final ControlRepository control;
    private final ReleaseOperationAudit audit;

    /** 使用本来源用例与共享归档工作流执行经过授权的工单。 */
    public ReleaseAuditArchive(AuditArchiveWorkflow workflow, ControlRepository control, ReleaseOperationAudit audit) {
        this.workflow = workflow;
        this.control = control;
        this.audit = audit;
    }

    /** 只在独立工单有效时发布临时查询副本。 */
    public int restore(String ticket) {
        var scope = TenantContextHolder.requireCurrent();
        return audit.archiveAccess(ticket, true, () -> {
            scope.requirePermission("release:audit-archive-restore");
            return workflow.restore(scope.tenantId().value(), ticket, scope.actorId(),
                    resource -> ControlCampaignScope.require(control, scope, Selection.parse(resource).campaignId(), false))
                    .events().size();
        });
    }

    /** 返回当前操作者能访问的活动版本的精确档案。 */
    public AuditArchiveWorkflow.Snapshot restored(String ticket) {
        var scope = TenantContextHolder.requireCurrent();
        return audit.archiveAccess(ticket, false, () -> {
            scope.requirePermission("release:audit-archive-read");
            return workflow.restored(scope.tenantId().value(), ticket, scope.actorId(),
                    resource -> ControlCampaignScope.require(control, scope, Selection.parse(resource).campaignId(), false));
        });
    }

    /** 调度输入为单个活动 @ 版本，对象范围与同一工单保持一致。 */
    public record Selection(String campaignId, long version) {
        /** 解析调度指定的单一活动版本。 */
        public static Selection parse(String selection) {
            if (selection != null && selection.matches("[^@\\s]{1,64}@[1-9][0-9]{0,18}")) {
                int separator = selection.lastIndexOf('@');
                try {
                    return new Selection(selection.substring(0, separator), Long.parseLong(selection.substring(separator + 1)));
                } catch (NumberFormatException overflow) {
                    throw new IllegalArgumentException("发布归档版本超出允许范围");
                }
            }
            throw new IllegalArgumentException("发布归档需要活动编号和精确版本");
        }
    }
}
