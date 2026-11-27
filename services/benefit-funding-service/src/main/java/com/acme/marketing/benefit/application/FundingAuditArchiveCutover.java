package com.acme.marketing.benefit.application;

import com.acme.marketing.benefit.application.FundingAuditArchiveRepository.EffectLocator;
import com.acme.marketing.benefit.application.FundingAuditArchiveRepository.Receipt;
import com.acme.marketing.platform.crypto.Digests;
import com.acme.marketing.platform.error.DependencyUnavailableException;
import com.acme.marketing.platform.identity.TenantId;
import com.acme.marketing.platform.web.archive.AuditArchiveStorage;
import com.acme.marketing.platform.web.archive.AuditArchiveWorkflow;
import com.acme.marketing.platform.web.archive.AuditArchiveWorkflow.Batch;
import com.acme.marketing.platform.web.archive.AuditArchiveWorkflow.Event;
import com.acme.marketing.platform.web.archive.AuditArchiveWorkflow.Repository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** 资金审计副本只按核验范围处理。切换不参与账本和额度更新事务，原效果由短定位记录接续。 */
public final class FundingAuditArchiveCutover {
    private static final String VERIFIED = "VERIFIED";
    private static final String ONLINE_PURGED = "ONLINE_PURGED";
    private static final String FAILURE = "资金审计切换凭证与当前证据不符";
    private final String tenant;
    private final String prefix;
    private final AuditArchiveWorkflow workflow;
    private final Repository manifest;
    private final FundingAuditArchiveRepository source;
    private final AuditArchiveStorage storage;
    private final TransactionTemplate transaction;
    private final JsonMapper json = JsonMapper.builder().build();

    public FundingAuditArchiveCutover(TenantId scope, AuditArchiveWorkflow workflow, Repository manifest,
            FundingAuditArchiveRepository source, AuditArchiveStorage storage, PlatformTransactionManager manager) {
        this.tenant = scope.value();
        this.prefix = AuditArchiveWorkflow.Source.FUNDING.prefix(scope) + "recovery/";
        this.workflow = workflow;
        this.manifest = manifest;
        this.source = source;
        this.storage = storage;
        transaction = new TransactionTemplate(manager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        transaction.setTimeout(5);
    }

    /** 外部准备凭证先于移出，已确认事务可重复补发提交凭证。 */
    public Outcome move(String batchId) {
        requireId(batchId);
        var batch = manifest.batch(tenant, batchId);
        if (batch == null || !(VERIFIED.equals(batch.state()) || ONLINE_PURGED.equals(batch.state()))) return Outcome.NOT_READY;
        if (ONLINE_PURGED.equals(batch.state())) {
            seal(batch);
            return Outcome.ALREADY_MOVED;
        }
        var grant = source.cutoverGrant(tenant, batchId, false);
        if (grant == null || batch.factsUntil().isAfter(grant.reviewedThrough())) return Outcome.NOT_APPROVED;
        var archived = workflow.verifiedSnapshot(batchId);
        write(batch, "prepared", prepared(batch));
        var outcome = transaction.execute(status -> apply(batch, archived.events()));
        if (outcome == Outcome.MOVED || outcome == Outcome.ALREADY_MOVED) seal(batch);
        return outcome;
    }

    /** 中断时按同一稳定对象键补发一个已确认事务。 */
    public String repairNext() {
        String batch = source.pendingProof(tenant);
        if (batch == null) return null;
        if (move(batch) != Outcome.ALREADY_MOVED) throw mismatch();
        return batch;
    }

    /** 从备份之外逐页发现未确认的切换，不把目标库现有清单作为清点依据。 */
    public Inventory inventory(String continuation) {
        String start = prefix + "prepared/";
        var page = storage.inventory(start, continuation, 20);
        var ready = new ArrayList<Reference>();
        var unresolved = new ArrayList<String>();
        for (String key : page.keys()) {
            if (!key.startsWith(start) || !key.endsWith(".json")) throw mismatch();
            String batch = key.substring(start.length(), key.length() - ".json".length());
            requireId(batch);
            var proof = storage.current(prefix + "committed/" + batch + ".json");
            if (proof == null) unresolved.add(batch);
            else ready.add(new Reference(batch, proof.version(), proof.sha256(), proof.bytes()));
        }
        return new Inventory(List.copyOf(ready), List.copyOf(unresolved), page.continuation());
    }

    /** 只用于隔离恢复。内容、范围、原始对象和旧库里仍有的证据逐层校验。 */
    public Replay replay(Reference reference) {
        requireId(reference.batchId());
        if (reference.version() == null || reference.version().isBlank()
                || reference.sha256() == null || !reference.sha256().matches("[0-9a-f]{64}")) throw mismatch();
        byte[] bytes = storage.recover(prefix + "committed/" + reference.batchId() + ".json",
                reference.version(), reference.bytes());
        if (reference.bytes() != bytes.length || !reference.sha256().equals(Digests.sha256Hex(bytes))) throw mismatch();
        var proof = json.readValue(bytes, Proof.class);
        if (proof == null || proof.prepared() == null || proof.movedAt() == null
                || proof.approvalReference() == null || proof.approvalReference().isBlank()) throw mismatch();
        var data = proof.prepared();
        if (data.schemaVersion() != 1 || !tenant.equals(data.tenant()) || !reference.batchId().equals(data.batchId())
                || data.factsFrom() == null || data.factsUntil() == null || data.factsFrom().isAfter(data.factsUntil())) throw mismatch();
        var batch = new Batch(tenant, data.batchId(), data.resource(), data.factsFrom(), data.factsUntil(), data.rowCount(),
                data.objectKey(), data.objectVersion(), data.sha256(), data.objectBytes(), null, ONLINE_PURGED);
        var archived = workflow.recoverVerifiedSnapshot(batch);
        var receipt = new Receipt(proof.approvalReference(), data.rowCount(), proof.movedAt(),
                reference.version(), reference.sha256(), reference.bytes());
        return transaction.execute(status -> applyReplay(batch, archived.events(), receipt));
    }

    private Replay applyReplay(Batch batch, List<Event> facts, Receipt expected) {
        String state = source.lockBatchState(tenant, batch.batchId());
        if (state == null) {
            if (source.restoreBatch(tenant, batch) != 1) throw mismatch();
        } else {
            var current = manifest.batch(tenant, batch.batchId());
            if (current == null || !prepared(batch).sameArchive(current)) throw mismatch();
        }
        if (!source.lockHolds(tenant, batch.resourceId()).isEmpty()) throw mismatch();
        List<Long> ids = facts.stream().map(event -> Long.parseLong(event.id())).toList();
        List<Long> attempts = facts.stream().map(FundingAuditArchiveCutover::attemptId).toList();
        var present = source.lockExistingResults(tenant, ids);
        var stillOnline = source.lockExistingAttempts(tenant, attempts);
        var actual = present.isEmpty() ? List.<Event>of() : source.lockedEvidence(tenant, batch.resourceId(), present);
        var known = facts.stream().collect(Collectors.toUnmodifiableMap(Event::id, Function.identity()));
        if (actual.size() != present.size() || actual.stream().anyMatch(row -> !row.equals(known.get(row.id())))
                || !actual.stream().map(FundingAuditArchiveCutover::attemptId).sorted().toList().equals(stillOnline)) {
            throw mismatch();
        }
        var effects = facts.stream().filter(FundingAuditArchiveCutover::hasEffect)
                .map(event -> new EffectLocator(Long.parseLong(event.id()), batch.resourceId(),
                        event.fact().path("action").asString(), event.fact().path("commandHash").asString(), batch.batchId()))
                .toList();
        if (!effects.isEmpty()) {
            source.restoreEffects(tenant, effects);
            if (!effects.equals(source.restoredEffects(tenant, effects.stream().map(EffectLocator::effectId).toList()))) throw mismatch();
        }
        if (!actual.isEmpty() && (source.removeResults(tenant, batch.resourceId(), present) != present.size()
                || source.removeAttempts(tenant, stillOnline) != stillOnline.size())) throw mismatch();
        if (state != null && !ONLINE_PURGED.equals(state) && source.closeRestoredBatch(tenant, batch) != 1) throw mismatch();
        var current = source.receipt(tenant, batch.batchId());
        if (current == null) {
            if (source.restoreReceipt(tenant, batch.batchId(), expected) != 1) throw mismatch();
        } else {
            if (!current.approvalReference().equals(expected.approvalReference()) || current.movedCount() != expected.movedCount()
                    || !current.movedAt().equals(expected.movedAt())) throw mismatch();
            if (current.recoveryVersion() == null) {
                source.sealProof(tenant, batch.batchId(), expected.recoveryVersion(), expected.recoverySha256(), expected.recoveryBytes());
            }
            if (!expected.equals(source.receipt(tenant, batch.batchId()))) throw mismatch();
        }
        return new Replay(batch.batchId(), present.size());
    }

    private Outcome apply(Batch batch, List<Event> facts) {
        String state = source.lockBatchState(tenant, batch.batchId());
        if (ONLINE_PURGED.equals(state)) return Outcome.ALREADY_MOVED;
        if (!VERIFIED.equals(state)) return Outcome.NOT_READY;
        var grant = source.cutoverGrant(tenant, batch.batchId(), true);
        if (grant == null || batch.factsUntil().isAfter(grant.reviewedThrough())) return Outcome.NOT_APPROVED;
        if (!source.lockHolds(tenant, batch.resourceId()).isEmpty()) return Outcome.HELD;
        List<Long> results = facts.stream().map(fact -> Long.parseLong(fact.id())).toList();
        List<Long> attempts = facts.stream().map(FundingAuditArchiveCutover::attemptId).toList();
        // 回读完整事实再取得精确审计行锁，避免额外锁住资金申请、账户或账本。
        if (!facts.equals(source.lockedEvidence(tenant, batch.resourceId(), results))) return Outcome.CHANGED;
        long expectedLocators = facts.stream().filter(FundingAuditArchiveCutover::hasEffect).count();
        int count = facts.size();
        if (source.retainEffects(tenant, batch.resourceId(), batch.batchId(), results) != expectedLocators
                || source.removeResults(tenant, batch.resourceId(), results) != count
                || source.removeAttempts(tenant, attempts) != count
                || source.markOnlinePurged(tenant, batch.batchId(), batch.objectVersion()) != 1
                || source.recordCutover(tenant, batch.batchId(), grant.approvalReference(), count) != 1) throw mismatch();
        return Outcome.MOVED;
    }

    private void seal(Batch batch) {
        Receipt receipt = source.receipt(tenant, batch.batchId());
        if (receipt == null || receipt.movedCount() != batch.rowCount()) throw mismatch();
        if (receipt.recoveryVersion() != null) return;
        var stored = write(batch, "committed", new Proof(prepared(batch), receipt.approvalReference(), receipt.movedAt()));
        source.sealProof(tenant, batch.batchId(), stored.version(), stored.sha256(), stored.bytes());
        var confirmed = source.receipt(tenant, batch.batchId());
        if (confirmed == null || !stored.version().equals(confirmed.recoveryVersion())
                || !stored.sha256().equals(confirmed.recoverySha256()) || !Objects.equals(stored.bytes(), confirmed.recoveryBytes())) {
            throw mismatch();
        }
    }

    private JournalRef write(Batch batch, String phase, Object document) {
        String key = prefix + phase + "/" + batch.batchId() + ".json";
        byte[] bytes = json.writeValueAsBytes(document);
        String hash = Digests.sha256Hex(bytes);
        String version = storage.putOnce(key, bytes, hash);
        if (!Arrays.equals(bytes, storage.verify(key, version, bytes.length))) throw mismatch();
        return new JournalRef(version, hash, bytes.length);
    }

    private Prepared prepared(Batch batch) {
        return new Prepared(1, tenant, batch.batchId(), batch.resourceId(), batch.objectKey(), batch.objectVersion(),
                batch.sha256(), batch.payloadBytes(), batch.rowCount(), batch.factsFrom(), batch.factsUntil());
    }

    private static long attemptId(Event event) {
        return Long.parseLong(event.fact().path("attemptId").asString());
    }

    private static boolean hasEffect(Event event) {
        return "SUCCEEDED".equals(event.fact().path("result").asString()) && event.fact().path("commandHash").isString();
    }

    private static void requireId(String id) {
        if (id == null || !id.matches("[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}")) {
            throw new IllegalArgumentException("资金审计切换需要精确批次号");
        }
    }

    private static DependencyUnavailableException mismatch() {
        return new DependencyUnavailableException("AUDIT_ARCHIVE_UNAVAILABLE", FAILURE);
    }

    /** 可直接作为值班检查结果，在暂缓状态下保留在线副本。 */
    public enum Outcome { MOVED, ALREADY_MOVED, NOT_READY, NOT_APPROVED, HELD, CHANGED }

    /** 工单已确认的批次和版本引用。 */
    public record Reference(String batchId, String version, String sha256, int bytes) {}

    /** 可执行和待核对的回执以有限一页返回。 */
    public record Inventory(List<Reference> committed, List<String> unresolved, String continuation) {}

    /** 返回本次实际移出的匹配副本数量。 */
    public record Replay(String batchId, int removedRows) {}

    record Prepared(int schemaVersion, String tenant, String batchId, String resource, String objectKey,
                    String objectVersion, String sha256, int objectBytes, int rowCount, Instant factsFrom, Instant factsUntil) {
        boolean sameArchive(Batch batch) {
            return resource.equals(batch.resourceId()) && objectKey.equals(batch.objectKey()) && sha256.equals(batch.sha256())
                    && rowCount == batch.rowCount() && objectBytes == batch.payloadBytes()
                    && factsFrom.equals(batch.factsFrom()) && factsUntil.equals(batch.factsUntil())
                    && (batch.objectVersion() == null || objectVersion.equals(batch.objectVersion()));
        }
    }

    record Proof(Prepared prepared, String approvalReference, java.time.Instant movedAt) {}

    record JournalRef(String version, String sha256, int bytes) {}
}
