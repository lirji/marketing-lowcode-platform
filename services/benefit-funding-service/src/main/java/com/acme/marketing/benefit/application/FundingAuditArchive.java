package com.acme.marketing.benefit.application;

import com.acme.marketing.platform.web.TenantContextHolder;
import com.acme.marketing.platform.web.archive.AuditArchiveWorkflow;

/** 资金档案使用原资金申请的当前组织、门店和恢复工单授权。 */
public final class FundingAuditArchive {
    private final AuditArchiveWorkflow workflow;
    private final BenefitFundingService funding;
    private final FundingOperationAudit audit;

    FundingAuditArchive(AuditArchiveWorkflow workflow, BenefitFundingService funding, FundingOperationAudit audit) {
        this.workflow = workflow;
        this.funding = funding;
        this.audit = audit;
    }

    /** 独立取证通过工单发布临时副本，返回证据行数。 */
    public int restore(String ticket) {
        var scope = TenantContextHolder.requireCurrent();
        return audit.archiveAccess(scope, ticket, true, () -> {
            scope.requirePermission("funding:audit-archive-restore");
            return workflow.restore(scope.tenantId().value(), ticket, scope.actorId(),
                    resource -> funding.authorizedAuditTarget(scope, resource)).events().size();
        });
    }

    /** 读取仍受资金申请的当前组织、门店条件约束。 */
    public AuditArchiveWorkflow.Snapshot restored(String ticket) {
        var scope = TenantContextHolder.requireCurrent();
        return audit.archiveAccess(scope, ticket, false, () -> {
            scope.requirePermission("funding:audit-archive-read");
            var result = workflow.restored(scope.tenantId().value(), ticket, scope.actorId(),
                    resource -> funding.authorizedAuditTarget(scope, resource));
            var events = result.events().stream().map(event -> {
                var fact = (tools.jackson.databind.node.ObjectNode) event.fact().deepCopy();
                fact.remove("commandHash");
                return new AuditArchiveWorkflow.Event(event.id(), event.recordedAt(), fact);
            }).toList();
            return new AuditArchiveWorkflow.Snapshot(result.header(), events);
        });
    }
}
