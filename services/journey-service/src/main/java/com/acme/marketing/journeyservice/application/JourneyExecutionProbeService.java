package com.acme.marketing.journeyservice.application;

import static com.acme.marketing.platform.time.SqlTime.format;

import com.acme.marketing.contracts.event.JourneyBundleExecutionAckEvent;
import com.acme.marketing.contracts.release.JourneyBundleExecutionAck;
import com.acme.marketing.contracts.release.JourneyBundleExecutionProbe;
import com.acme.marketing.contracts.release.PinnedJourneyBundleExecutionVerifier;
import com.acme.marketing.contracts.release.ReleaseBundleActivationDirective;
import com.acme.marketing.contracts.release.ReleaseBundleProtocolError;
import com.acme.marketing.journeyservice.application.JourneyExecutionProbeRepository.Key;
import com.acme.marketing.journeyservice.application.JourneyExecutionProbeRepository.StoredProbe;
import com.acme.marketing.journeyservice.application.JourneyExecutionProbeRepository.StoredAck;
import com.acme.marketing.platform.identity.TenantId;
import com.acme.marketing.platform.identity.TenantScope;
import com.acme.marketing.platform.web.TenantContextHolder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;

/** 只保存真实执行挑战和证据，READY仅表示本站子任务覆盖，不写整体应用指针。 */
@Service
public class JourneyExecutionProbeService {
    public static final String PERMISSION = "runtime:warm";
    public static final int MAX_PROBE_BYTES = 8 * 1024;
    public static final int MAX_ACK_BYTES = 16 * 1024;
    private static final int TRANSACTION_SECONDS = 10;
    private final JourneyExecutionProbeRepository repository;
    private final BundleDirectiveRepository directives;
    private final JourneyRuntimeReleaseService releases;
    private final JourneyExecutionDeployment deployment;
    private final PinnedJourneyBundleExecutionVerifier verifier;
    private final ObjectMapper mapper;
    private final Clock clock;

    /** 只访问Journey所属库和既有安装校验，不在本地事务内调用Kafka或控制面。 */
    public JourneyExecutionProbeService(JourneyExecutionProbeRepository repository, BundleDirectiveRepository directives,
            JourneyRuntimeReleaseService releases, JourneyExecutionDeployment deployment,
            PinnedJourneyBundleExecutionVerifier verifier, ObjectMapper mapper, Clock clock) {
        this.repository = repository; this.directives = directives; this.releases = releases;
        this.deployment = deployment; this.verifier = verifier; this.mapper = mapper; this.clock = clock;
    }

    /** 重复登记返回原挑战；RC保证等待并发唯一仲裁后读取最新证据集合。 */
    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = TRANSACTION_SECONDS)
    public Receipt register(JourneyBundleExecutionProbe probe) {
        var scope = TenantContextHolder.requireCurrent(); scope.requirePermission(PERMISSION);
        if (probe == null || !scope.tenantId().equals(probe.tenantId())) throw ReleaseBundleProtocolError.EXECUTION_PROBE_INVALID.conflict();
        Key key = deployment.key(scope.tenantId().value(), probe.campaignId(), probe.probeId());
        var source = source(key, probe.activationSequence()); requireScope(scope, source);
        var member = verifier.requireProbe(deployment.expected(scope.tenantId(), key.campaignId()), probe, source, clock.instant());
        releases.verifyInstalledMember(key.tenantId(), member);
        String json = mapper.writeValueAsString(probe);
        if (json.getBytes(StandardCharsets.UTF_8).length > MAX_PROBE_BYTES) throw ReleaseBundleProtocolError.EXECUTION_REQUEST_INVALID.invalid();
        repository.ensure(key, new StoredProbe(probe.activationSequence(), json, deployment.fingerprint(), format(probe.expiresAt()), scope.actorId(), format(clock.instant())));
        var row = locked(key);
        if (!probe.equals(probe(key, row))) throw ReleaseBundleProtocolError.EXECUTION_PROBE_CONFLICT.conflict();
        return receipt(key, row, scope);
    }

    /** 锁定一条挑战取得一致集合，历史过期事实可以查询但不恢复发布资格。 */
    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = TRANSACTION_SECONDS)
    public Receipt get(String campaignId, String probeId) {
        var scope = TenantContextHolder.requireCurrent(); scope.requirePermission(PERMISSION);
        Key key = deployment.key(scope.tenantId().value(), campaignId, probeId);
        return receipt(key, locked(key), scope);
    }

    /** 只解析类型，不开启数据库事务；旧业务输出保持原有单次投影事务。 */
    public boolean isExecutionAck(String payload) {
        var tree = mapper.readTree(payload);
        return tree != null && JourneyBundleExecutionAckEvent.EVENT_TYPE.equals(tree.path("eventType").asString());
    }

    /** 实际回执由独立事务收集，业务唯一下标与父行锁保证至少一次交付下不虚增覆盖。 */
    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = TRANSACTION_SECONDS)
    public void collect(String payload) {
        var event = read(payload, JourneyBundleExecutionAckEvent.class, MAX_ACK_BYTES, false);
        var ack = event.ack(); var candidate = ack.probe();
        Key key = deployment.key(candidate.tenantId().value(), candidate.campaignId(), candidate.probeId());
        var row = locked(key); var probe = probe(key, row);
        var source = source(key, row.activationSequence());
        verifier.requireAck(deployment.expected(new TenantId(key.tenantId()), key.campaignId()), probe, source, ack, clock.instant());
        List<StoredAck> previous = repository.acknowledgements(key);
        if (previous.size() > probe.targetParallelism()) throw ReleaseBundleProtocolError.EXECUTION_STORED_INVALID.conflict();
        if (previous.stream().noneMatch(value -> value.subtaskIndex() == ack.subtaskIndex()))
            repository.saveAck(key, new StoredAck(ack.subtaskIndex(), mapper.writeValueAsString(ack), format(clock.instant())));
    }

    private Receipt receipt(Key key, StoredProbe row, TenantScope scope) {
        requireScope(scope, source(key, row.activationSequence()));
        return receipt(key, row);
    }

    /** 仅供本包已领取持久投递任务使用；调用方必须在短事务中持锁校验，不能映射到HTTP。 */
    Receipt inspectDelivery(Key key) {
        if (!key.equals(deployment.key(key.tenantId(), key.campaignId(), key.probeId())))
            throw ReleaseBundleProtocolError.EXECUTION_DELIVERY_INVALID.conflict();
        return receipt(key, locked(key));
    }

    private Receipt receipt(Key key, StoredProbe row) {
        var probe = probe(key, row); var source = source(key, row.activationSequence());
        Instant now = clock.instant(); boolean expired = !now.isBefore(probe.expiresAt());
        // 过期仅查看历史：仍检查当前信任，按有效窗末端验证当时证据；不能把该时刻用于实际应用。
        Instant verificationAt = expired ? probe.expiresAt().minusNanos(1) : now;
        var expected = deployment.expected(new TenantId(key.tenantId()), key.campaignId());
        var member = verifier.requireProbe(expected, probe, source, verificationAt);
        if (!expired) releases.verifyInstalledMember(key.tenantId(), member);
        var rows = repository.acknowledgements(key); var acks = new ArrayList<JourneyBundleExecutionAck>();
        if (rows.size() > expected.parallelism()) throw ReleaseBundleProtocolError.EXECUTION_STORED_INVALID.conflict();
        int previousIndex = -1;
        for (StoredAck saved : rows) {
            var ack = read(saved.ackJson(), JourneyBundleExecutionAck.class, MAX_ACK_BYTES, true);
            if (saved.subtaskIndex() != ack.subtaskIndex() || saved.subtaskIndex() <= previousIndex)
                throw ReleaseBundleProtocolError.EXECUTION_STORED_INVALID.conflict();
            verifier.requireAck(expected, probe, source, ack, verificationAt);
            previousIndex = saved.subtaskIndex(); acks.add(ack);
        }
        ProbeState state = expired ? ProbeState.EXPIRED : acks.size() == expected.parallelism() ? ProbeState.READY : ProbeState.PENDING;
        return new Receipt(state.code(), probe, List.copyOf(acks), now);
    }

    private StoredProbe locked(Key key) { return repository.lock(key).orElseThrow(ReleaseBundleProtocolError.EXECUTION_PROBE_NOT_FOUND::notFound); }
    private JourneyBundleExecutionProbe probe(Key key, StoredProbe row) {
        var value = read(row.probeJson(), JourneyBundleExecutionProbe.class, MAX_PROBE_BYTES, true);
        if (!key.equals(deployment.key(value.tenantId().value(), value.campaignId(), value.probeId()))
                || !key.environment().equals(value.environment()) || !key.cell().equals(value.cell()) || !key.namespace().equals(value.namespace())
                || row.activationSequence() != value.activationSequence() || !format(value.expiresAt()).equals(row.expiresAt()))
            throw ReleaseBundleProtocolError.EXECUTION_STORED_INVALID.conflict();
        if (!deployment.fingerprint().equals(row.deploymentFingerprint())) throw ReleaseBundleProtocolError.EXECUTION_DEPLOYMENT_UNAVAILABLE.conflict();
        return value;
    }
    private ReleaseBundleActivationDirective source(Key key, long sequence) {
        var row = directives.history(new BundleDirectiveRepository.Key(key.tenantId(), key.environment(), key.cell(), key.namespace(), key.campaignId()), sequence)
                .orElseThrow(ReleaseBundleProtocolError.ACTIVATION_NOT_RECORDED::conflict);
        var value = read(row.directiveJson(), ReleaseBundleActivationDirective.class, JourneyBundleDirectiveService.MAX_REQUEST_BYTES, true);
        if (row.activationSequence() != value.activationSequence() || !row.directiveId().equals(value.directiveId()))
            throw ReleaseBundleProtocolError.EXECUTION_STORED_INVALID.conflict();
        return value;
    }
    private <T> T read(String content, Class<T> type, int maximum, boolean stored) {
        var error = stored ? ReleaseBundleProtocolError.EXECUTION_STORED_INVALID : ReleaseBundleProtocolError.EXECUTION_REQUEST_INVALID;
        try {
            if (content == null || content.getBytes(StandardCharsets.UTF_8).length > maximum) throw error.conflict();
            T value = mapper.readerFor(type).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).without(DeserializationFeature.ACCEPT_FLOAT_AS_INT).readValue(content);
            if (value == null) throw error.conflict();
            return value;
        } catch (JacksonException | IllegalArgumentException corrupt) { throw error.conflict(); }
    }
    private static void requireScope(TenantScope scope, ReleaseBundleActivationDirective source) {
        scope.requireOrganization(source.organizationId());
        if (!source.shopId().isEmpty()) scope.requireShop(source.shopId());
    }
    /** 状态使用显式稳定code；没有APPLIED等业务发布状态。 */
    private enum ProbeState {
        PENDING("PENDING"), READY("READY"), EXPIRED("EXPIRED");
        private final String code;
        ProbeState(String code) { this.code = code; }
        String code() { return code; }
    }
    /** 返回原证据供下一层重验；字段不表示活动已整体生效。 */
    public record Receipt(String state, JourneyBundleExecutionProbe probe, List<JourneyBundleExecutionAck> acknowledgements, Instant observedAt) {
        public boolean ready() { return ProbeState.READY.code().equals(state); }
        public boolean expired() { return ProbeState.EXPIRED.code().equals(state); }
    }
}
