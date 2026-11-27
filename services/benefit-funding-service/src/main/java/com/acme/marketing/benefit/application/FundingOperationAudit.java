package com.acme.marketing.benefit.application;

import com.acme.marketing.benefit.application.BenefitFundingRepository.AuditEntry;
import com.acme.marketing.benefit.application.BenefitFundingRepository.AuditResult;
import com.acme.marketing.benefit.application.BenefitFundingRepository.AuditTarget;
import com.acme.marketing.platform.crypto.Digests;
import com.acme.marketing.platform.error.DomainException;
import com.acme.marketing.platform.error.ForbiddenException;
import com.acme.marketing.platform.identity.TenantScope;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 营销资金业务留痕。本地账本和实际提交证据共享事务。 */
@Component
public class FundingOperationAudit {
    private static final org.slf4j.Logger LOG = LoggerFactory.getLogger(FundingOperationAudit.class);
    private static final int PAGE_SIZE = 50;

    private final BenefitFundingRepository records;
    private final TransactionTemplate independent;

    public FundingOperationAudit(BenefitFundingRepository records, PlatformTransactionManager manager) {
        this.records = records;
        this.independent = new TransactionTemplate(manager);
        independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        independent.setTimeout(3);
    }

    public enum Action {
        CONFIRM("funding.confirm"), RELEASE("funding.release"), REFUND("funding.refund"),
        REVERSE("funding.reverse"), READ("funding.audit.list"),
        ARCHIVE_RESTORE("funding.archive.restore"), ARCHIVE_READ("funding.archive.read");

        private final String code;

        Action(String code) {
            this.code = code;
        }
    }

    /** 调用只携带稳定内部引用，命令原文和凭据不会写入审计。 */
    public record Attempt(String tenant, long id, Action action, String commandHash) { }

    /** 返回授权可读记录，使用结果流水游标。 */
    public record History(List<AuditEntry> items, String nextCursor) { }

    /** 请求尝试先可靠保存，失败时仅追加已确认的拒绝或未知状态。 */
    public <T> T capture(TenantScope scope, Action action, String command, AuditTarget verifiedTarget, Function<Attempt, T> apply) {
        String tenant = scope.tenantId().value();
        String requestId = UUID.randomUUID().toString();
        String actor = scope.actorId().length() <= 255 ? scope.actorId() : "sha256:" + Digests.sha256Hex(scope.actorId());
        String trace = MDC.get("traceId");
        String safeTrace = trace != null && trace.matches("[a-fA-F0-9-]{16,64}") ? trace : null;
        long id = independent.execute(status -> {
            requireWritten(records.beginAudit(new BenefitFundingRepository.AuditAttempt(
                    tenant, requestId, actor, action.code, safeTrace)));
            return records.auditAttemptId(tenant, requestId);
        });
        var attempt = new Attempt(tenant, id, action, command == null ? null : Digests.sha256Hex(command));
        try {
            return apply.apply(attempt);
        } catch (RuntimeException failure) {
            String code = failure instanceof DomainException domain ? domain.code() : null;
            String result = failure instanceof ForbiddenException ? "DENIED"
                    : failure instanceof IllegalArgumentException ? "REJECTED"
                    : code == null ? "UNKNOWN" : "CONFLICT";
            try {
                independent.executeWithoutResult(status -> append(attempt, verifiedTarget, result, code, null, null, null, null));
            } catch (RuntimeException unavailable) {
                LOG.error("营销资金审计追加失败 attemptId={} errorType={}", id, unavailable.getClass().getSimpleName());
            }
            throw failure;
        }
    }

    /** 从已核对的业务状态和刚提交的预算变化构建实际效果。 */
    public void committed(Attempt attempt, AuditTarget target, String before, String after, long amountMinor) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("资金审计需要业务事务");
        }
        append(attempt, target, "SUCCEEDED", null, null, before, after, amountMinor);
    }

    /** 运行既有幂等路径后，只为未产生新效果的尝试关联原提交。 */
    public void resolveReplay(Attempt attempt, AuditTarget target) {
        if (records.auditResultId(attempt.tenant(), attempt.id()) != null) {
            return;
        }
        Long original = records.originalAuditEffect(attempt.tenant(), target.applicationId(), attempt.action().code,
                attempt.commandHash());
        append(attempt, target, "REPLAYED", null, original, null, null, null);
    }

    /** 业务已核验资金申请和组织门店权限，数据库继续限制租户与申请。 */
    public History history(TenantScope scope, AuditTarget target, String cursor) {
        return capture(scope, Action.READ, null, target, attempt -> {
            Long before = parseCursor(cursor);
            List<AuditEntry> rows = records.auditHistory(attempt.tenant(), target.applicationId(), before, PAGE_SIZE + 1);
            List<AuditEntry> result = rows.stream().limit(PAGE_SIZE).toList();
            append(attempt, target, "SUCCEEDED", null, null, null, null, null);
            return new History(result, rows.size() > PAGE_SIZE ? result.getLast().id() : null);
        });
    }

    /** 工单操作及读取单独记录，命令字段只保留工单摘要。 */
    <T> T archiveAccess(TenantScope scope, String ticket, boolean restore, java.util.function.Supplier<T> supplier) {
        Action action = restore ? Action.ARCHIVE_RESTORE : Action.ARCHIVE_READ;
        return capture(scope, action, ticket, null, attempt -> {
            T result = supplier.get();
            append(attempt, null, "SUCCEEDED", null, null, null, null, null);
            return result;
        });
    }

    private void append(Attempt attempt, AuditTarget target, String result, String error, Long original,
            String before, String after, Long amount) {
        String safeError = error != null && error.length() <= 64 ? error : null;
        requireWritten(records.appendAudit(new AuditResult(attempt.tenant(), attempt.id(), target, attempt.commandHash(),
                attempt.action().code, result, safeError, original, before, after, amount)));
    }

    private static Long parseCursor(String cursor) {
        if (cursor == null) {
            return null;
        }
        try {
            long value = Long.parseLong(cursor);
            if (value > 0 && Long.toString(value).equals(cursor)) {
                return value;
            }
        } catch (NumberFormatException badValue) {
            throw new IllegalArgumentException("资金审计游标无效", badValue);
        }
        throw new IllegalArgumentException("资金审计游标无效");
    }

    private static void requireWritten(int count) {
        if (count != 1) {
            throw new IllegalStateException("营销资金审计写入结果无效");
        }
    }
}
