package com.acme.marketing.control.application;

import static com.acme.marketing.platform.time.SqlTime.format;
import java.time.Instant;
import java.util.UUID;

/** 复用原控制面审计链算法，调用方必须与业务变更处于同一数据库事务。 */
final class ControlAuditChain {
    private ControlAuditChain() { }

    /** 锁定租户链头并追加审计；任一失败回滚调用方整个用例。 */
    static void append(ControlRepository repository, String tenantId, String actorId, String action,
            String resource, Instant now) {
        // 无操作 upsert 会立即取得排他行锁，避免先捕获重复键再锁头记录时发生锁升级死锁。
        repository.ensureAuditHead(tenantId, format(now));
        ControlRepository.AuditHeadRow stored = repository.findAuditHeadForUpdate(tenantId).orElse(null);
        AuditHead head = stored == null ? null : new AuditHead(stored.chainIndex(), stored.entryHash());
        if (head == null) throw new IllegalStateException("tenant audit head was not created");
        long chainIndex = Math.addExact(head.chainIndex(), 1);
        String previous = head.entryHash();
        String hash = com.acme.marketing.platform.crypto.Digests.sha256Hex(
                previous + '|' + chainIndex + '|' + tenantId + '|' + actorId + '|' + action + '|' + resource
                        + '|' + format(now));
        repository.saveAudit(new ControlRepository.AuditWrite(tenantId, UUID.randomUUID().toString(), chainIndex,
                actorId, action, resource, format(now), previous, hash));
        int updated = repository.compareAndSetAuditHead(new ControlRepository.AuditHeadWrite(tenantId, chainIndex,
                hash, format(now), head.chainIndex(), head.entryHash()));
        if (updated != 1) throw new IllegalStateException("tenant audit head compare-and-set failed");
    }
    private record AuditHead(long chainIndex, String entryHash) { }
}
