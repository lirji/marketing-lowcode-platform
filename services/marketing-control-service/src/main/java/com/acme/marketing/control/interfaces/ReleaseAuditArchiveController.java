package com.acme.marketing.control.interfaces;

import com.acme.marketing.control.application.ReleaseAuditArchive;
import com.acme.marketing.platform.web.archive.AuditArchiveWorkflow;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 独立工单回读，发布档案查询按实时权限决定是否展示。 */
@RestController
@RequestMapping("/api/v1/releases/audit-archives")
@ConditionalOnProperty(prefix = "marketing.release-audit-archive", name = {"enabled", "recovery-enabled"}, havingValue = "true")
public class ReleaseAuditArchiveController {
    private final ReleaseAuditArchive archive;

    public ReleaseAuditArchiveController(ReleaseAuditArchive archive) {
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
