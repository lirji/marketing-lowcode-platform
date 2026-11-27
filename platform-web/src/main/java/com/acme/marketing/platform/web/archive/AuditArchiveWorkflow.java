package com.acme.marketing.platform.web.archive;

import com.acme.marketing.platform.archive.AuditArchiveDocument;
import com.acme.marketing.platform.crypto.Digests;
import com.acme.marketing.platform.error.DependencyUnavailableException;
import com.acme.marketing.platform.error.NotFoundException;
import com.acme.marketing.platform.identity.TenantId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/** 固定营销档案的状态流转和恢复完整性。每个来源接入自己的 SQL、权限检查和保留决策。 */
public final class AuditArchiveWorkflow {
    private static final String CLAIMED = "CLAIMED";
    private static final String UPLOADED = "UPLOADED";
    private static final String VERIFIED = "VERIFIED";
    private static final String ONLINE_PURGED = "ONLINE_PURGED";
    private static final String TEMPORARY_ERROR = "营销审计档案暂不可用";
    private final Source source;
    private final String tenant;
    private final Duration minimumAge;
    private final Clock clock;
    private final Repository repository;
    private final SourceReader reader;
    private final AuditArchiveStorage storage;
    private final TransactionTemplate transaction;
    private final JsonMapper json = JsonMapper.builder().build();
    private final Semaphore lane = new Semaphore(1);
    private final Semaphore recoveries = new Semaphore(1);

    public AuditArchiveWorkflow(Source source, TenantId tenant, Duration minimumAge, Clock clock,
            Repository repository, SourceReader reader, AuditArchiveStorage storage, PlatformTransactionManager transactions) {
        if (minimumAge.compareTo(Duration.ofDays(1)) < 0) throw new IllegalArgumentException("营销审计归档年龄小于允许窗口");
        this.source = Objects.requireNonNull(source);
        this.tenant = tenant.value();
        this.minimumAge = minimumAge;
        this.clock = clock;
        this.repository = repository;
        this.reader = reader;
        this.storage = storage;
        this.transaction = new TransactionTemplate(transactions);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        transaction.setTimeout(5);
    }

    /** 精确业务范围或单项待核验恢复；对象请求始终在领取事务之外。 */
    public String verify(String resourceId) {
        if (!lane.tryAcquire()) throw unavailable();
        try {
            var batch = resourceId == null || resourceId.isBlank()
                    ? repository.pending(tenant) : transaction.execute(status -> claim(resourceId));
            if (batch == null) return null;
            validateKey(batch);
            if (CLAIMED.equals(batch.state())) {
                validateDocument(batch, batch.payload());
                String version = storage.putOnce(batch.objectKey(), batch.payload(), batch.sha256());
                repository.uploaded(tenant, batch.batchId(), version);
                batch = requireBatch(batch.batchId());
                if (!version.equals(batch.objectVersion())) throw unavailable();
            }
            if (UPLOADED.equals(batch.state())) {
                byte[] bytes = storage.verify(batch.objectKey(), batch.objectVersion(), batch.payloadBytes());
                validateDocument(batch, bytes);
                if (!Arrays.equals(bytes, batch.payload())) throw unavailable();
                repository.verified(tenant, batch.batchId(), batch.objectVersion());
                batch = requireBatch(batch.batchId());
            }
            if (!VERIFIED.equals(batch.state())) throw unavailable();
            return batch.batchId();
        } finally {
            lane.release();
        }
    }

    /** 来源必须在返回证据前重新核对当前业务权限。存储回读之后再次锁定恢复授权。 */
    public Snapshot restore(String authorizedTenant, String ticket, String actor, ResourceAuthorization authorize) {
        var grant = requireGrant(authorizedTenant, ticket, actor, false);
        if (!recoveries.tryAcquire()) throw unavailable();
        try {
            var batch = requireBatch(grant.batchId());
            if (!readable(batch)) throw missing();
            authorize.check(batch.resourceId());
            var snapshot = validateDocument(batch, storage.recover(batch.objectKey(), batch.objectVersion(), batch.payloadBytes()));
            return transaction.execute(status -> {
                if (!grant.equals(requireGrant(authorizedTenant, ticket, actor, true))) throw missing();
                authorize.check(batch.resourceId());
                repository.storeRestored(tenant, grant, json.writeValueAsString(snapshot));
                return snapshot;
            });
        } finally {
            recoveries.release();
        }
    }

    /** 临时查询只返回有效工单绑定的精确批次，来源服务仍负责业务范围权限。 */
    public Snapshot restored(String authorizedTenant, String ticket, String actor, ResourceAuthorization authorize) {
        var grant = requireGrant(authorizedTenant, ticket, actor, false);
        var batch = requireBatch(grant.batchId());
        if (!readable(batch)) throw missing();
        authorize.check(batch.resourceId());
        String payload = repository.restored(tenant, ticket, actor, batch.batchId());
        if (payload == null || !grant.equals(requireGrant(authorizedTenant, ticket, actor, false))) throw missing();
        return json.readValue(payload, Snapshot.class);
    }

    /** 已核验快照供来源做冻结、保留周期和在线证据逐条比对。 */
    public Snapshot verifiedSnapshot(String id) {
        var batch = requireBatch(id);
        if (!readable(batch)) throw unavailable();
        return validateDocument(batch, storage.verify(batch.objectKey(), batch.objectVersion(), batch.payloadBytes()));
    }

    /** 从备份域外取得的已核验批次元数据只在范围和正文都可重验时使用。 */
    public Snapshot recoverVerifiedSnapshot(Batch batch) {
        validateKey(batch);
        if (!readable(batch)) throw unavailable();
        return validateDocument(batch, storage.recover(batch.objectKey(), batch.objectVersion(), batch.payloadBytes()));
    }

    /** 单次清理有界，恢复副本到期立即停止读取。 */
    public int cleanupRestored() {
        return repository.cleanupRestored(tenant);
    }

    private Batch claim(String resourceId) {
        if (resourceId.length() > 128) throw unavailable();
        var rows = reader.eligible(tenant, resourceId, clock.instant().minus(minimumAge), AuditArchiveDocument.MAX_ROWS + 1);
        if (rows.isEmpty()) return null;
        var header = new Header(AuditArchiveDocument.FORMAT, source.id, tenant, resourceId);
        byte[] payload = AuditArchiveDocument.pack(json.writeValueAsString(header),
                rows.stream().map(json::writeValueAsString).toList());
        String batchId = UUID.randomUUID().toString();
        String sha256 = Digests.sha256Hex(payload);
        var from = rows.stream().map(Event::recordedAt).min(Instant::compareTo).orElseThrow();
        var until = rows.stream().map(Event::recordedAt).max(Instant::compareTo).orElseThrow();
        repository.claim(new Batch(tenant, batchId, resourceId, from, until, rows.size(), key(batchId),
                null, sha256, payload.length, payload, CLAIMED));
        var batch = repository.byDigest(tenant, resourceId, sha256);
        if (batch == null) throw unavailable();
        return batch;
    }

    private Snapshot validateDocument(Batch batch, byte[] bytes) {
        if (bytes == null) throw unavailable();
        try {
            var document = AuditArchiveDocument.unpack(bytes, batch.sha256(), batch.payloadBytes(), batch.rowCount());
            Header header = json.readValue(document.header(), Header.class);
            if (!new Header(AuditArchiveDocument.FORMAT, source.id, tenant, batch.resourceId()).equals(header)) throw unavailable();
            var events = document.rows().stream().map(row -> json.readValue(row, Event.class)).toList();
            if (events.stream().map(Event::id).distinct().count() != events.size()) throw unavailable();
            return new Snapshot(header, events);
        } catch (IllegalArgumentException | JacksonException failure) {
            throw unavailable();
        }
    }

    private Batch requireBatch(String id) {
        var batch = repository.batch(tenant, id);
        if (batch == null) throw missing();
        validateKey(batch);
        return batch;
    }

    private void validateKey(Batch batch) {
        if (!tenant.equals(batch.tenantId()) || !key(batch.batchId()).equals(batch.objectKey())) throw unavailable();
    }

    private Grant requireGrant(String authorizedTenant, String ticket, String actor, boolean lock) {
        if (!tenant.equals(authorizedTenant) || ticket == null || !ticket.matches("[A-Za-z0-9_-]{1,64}")) throw missing();
        var grant = repository.grant(tenant, ticket, actor, lock);
        if (grant == null) throw missing();
        return grant;
    }

    private String key(String batchId) {
        return source.prefix(new TenantId(tenant)) + batchId + ".ndjson.gz";
    }

    private static boolean readable(Batch batch) {
        return VERIFIED.equals(batch.state()) || ONLINE_PURGED.equals(batch.state());
    }

    private static DependencyUnavailableException unavailable() {
        return new DependencyUnavailableException("AUDIT_ARCHIVE_UNAVAILABLE", TEMPORARY_ERROR);
    }

    private static NotFoundException missing() {
        return new NotFoundException("AUDIT_ARCHIVE_NOT_FOUND", "营销审计归档工单或批次不存在");
    }

    public enum Source {
        FUNDING("funding"),
        RELEASE("release");

        private final String id;

        Source(String id) { this.id = id; }

        public String prefix(TenantId tenant) {
            return "marketing/" + id + "/operation-audit/v1/" + tenant.value() + "/";
        }
    }

    public record Batch(String tenantId, String batchId, String resourceId, Instant factsFrom, Instant factsUntil,
                        int rowCount, String objectKey, String objectVersion, String sha256, int payloadBytes,
                        byte[] payload, String state) {}

    public record Grant(String ticketId, String batchId, Instant expiresAt) {}

    public record Header(int schemaVersion, String source, String tenantId, String resourceId) {}

    public record Event(String id, Instant recordedAt, tools.jackson.databind.JsonNode fact) {}

    public record Snapshot(Header header, List<Event> events) {}

    @FunctionalInterface
    public interface ResourceAuthorization {
        void check(String resourceId);
    }

    @FunctionalInterface
    public interface SourceReader {
        List<Event> eligible(String tenant, String resourceId, Instant cutoff, int limit);
    }

    /** 每个来源使用独立 schema，归档状态不跨数据源。 */
    public interface Repository {
        void claim(Batch batch);

        Batch byDigest(String tenant, String resourceId, String digest);

        Batch batch(String tenant, String batchId);

        Batch pending(String tenant);

        void uploaded(String tenant, String batchId, String version);

        void verified(String tenant, String batchId, String version);

        Grant grant(String tenant, String ticket, String actor, boolean lock);

        void storeRestored(String tenant, Grant grant, String payload);

        String restored(String tenant, String ticket, String actor, String batchId);

        int cleanupRestored(String tenant);
    }
}
