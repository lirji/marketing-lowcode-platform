package com.acme.marketing.benefit.interfaces;

import com.acme.marketing.benefit.application.FundingAuditArchive;
import com.acme.marketing.platform.web.archive.AuditArchiveWorkflow;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 独立工单回读，资金档案查询按实时权限决定是否展示。 */
@RestController
@RequestMapping("/api/v1/funding/audit-archives")
@ConditionalOnProperty(prefix = "marketing.funding-audit-archive", name = {"enabled", "recovery-enabled"}, havingValue = "true")
public class FundingAuditArchiveController {
    private final FundingAuditArchive archive;

    public FundingAuditArchiveController(FundingAuditArchive archive) {
        this.archive = archive;
    }

    @PostMapping("/{ticket}/restore")
    public Restoration restore(@PathVariable String ticket) {
        return new Restoration(archive.restore(ticket));
    }

    @GetMapping("/{ticket}")
    public AuditArchiveWorkflow.Snapshot restored(@PathVariable String ticket) {
        return archive.restored(ticket);
    }

    public record Restoration(int factCount) {}
}
