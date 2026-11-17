package com.acme.marketing.decision.application;

import static com.acme.marketing.platform.time.SqlTime.format;

import com.acme.marketing.contracts.release.PinnedReleaseBundleActivationVerifier;
import com.acme.marketing.contracts.release.ReleaseBundleActivationDirective;
import com.acme.marketing.contracts.release.ReleaseBundleProtocolError;
import com.acme.marketing.contracts.release.ReleaseManifest;
import com.acme.marketing.decision.runtime.RuntimeManifestRegistry;
import com.acme.marketing.platform.web.TenantContextHolder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;

/** 持久接收整体授权，RECORDED不能作为线上应用或参与绑定的成功回执。 */
@Service
public class DecisionBundleDirectiveService {
    public static final String RECEIVE_PERMISSION = "runtime:activate";
    private static final String RUNTIME_NAME = "decision";
    public static final int MAX_REQUEST_BYTES = 1024 * 1024;
    private static final String RECORDED = "RECORDED";
    private final BundleDirectiveRepository repository;
    private final PinnedReleaseBundleActivationVerifier verifier;
    private final RuntimeManifestRegistry registry;
    private final RuntimeManifestRegistry.RuntimeSlot slot;
    private final ObjectMapper mapper;
    private final Clock clock;

    /** 只复用Decision本库及已有控制面信任配置，不访问控制库或外部运行端。 */
    public DecisionBundleDirectiveService(BundleDirectiveRepository repository, PinnedReleaseBundleActivationVerifier verifier,
            RuntimeManifestRegistry registry, ObjectMapper mapper, Clock clock,
            @Value("${marketing.runtime.environment:local}") String environment,
            @Value("${marketing.runtime.cell:cell-a}") String cell,
            @Value("${marketing.runtime.namespace:main}") String namespace) {
        this.repository = repository; this.verifier = verifier; this.registry = registry; this.mapper = mapper;
        this.clock = clock; this.slot = new RuntimeManifestRegistry.RuntimeSlot(environment, cell, namespace);
    }

    /** head锁先于数据库快照；重复/乱序仍重验当前信任和本站实际制品。 */
    @Transactional(timeout = 10)
    public Receipt record(ReleaseBundleActivationDirective directive) {
        var scope = TenantContextHolder.requireCurrent(); scope.requirePermission(RECEIVE_PERMISSION);
        verifier.requireValid(scope.tenantId().value(), directive, clock.instant());
        ReleaseManifest member = localMember(directive);
        var key = key(scope.tenantId().value(), directive.bundle().campaignId());
        Long previous = repository.lockHead(key, format(clock.instant()));
        Instant now = clock.instant();
        verifier.requireValid(scope.tenantId().value(), directive, now);
        registry.verifyInstalledMember(scope.tenantId().value(), member);
        // 原slot行锁与单清单写入共用；后续冲突或事务故障必须一起撤销此标记。
        repository.manageSlot(key, format(now));
        var existing = repository.history(key, directive.activationSequence());
        if (existing.isPresent()) {
            var row = existing.orElseThrow();
            if (!directive.equals(read(key, row))) throw ReleaseBundleProtocolError.ACTIVATION_SEQUENCE_CONFLICT.conflict();
            if (previous == null || previous < directive.activationSequence()) throw ReleaseBundleProtocolError.ACTIVATION_STORED_INVALID.conflict();
            return receipt(key, row, previous);
        }
        if (repository.sequenceForId(key, directive.directiveId()).isPresent())
            throw ReleaseBundleProtocolError.ACTIVATION_SEQUENCE_CONFLICT.conflict();
        if (previous != null && directive.activationSequence() <= previous)
            throw ReleaseBundleProtocolError.ACTIVATION_SEQUENCE_STALE.conflict();
        String content = mapper.writeValueAsString(directive);
        if (content.getBytes(StandardCharsets.UTF_8).length > MAX_REQUEST_BYTES)
            throw ReleaseBundleProtocolError.ACTIVATION_REQUEST_INVALID.invalid();
        var row = new BundleDirectiveRepository.StoredDirective(directive.activationSequence(), directive.directiveId(), content, format(now));
        repository.save(key, row, scope.actorId());
        if (repository.advance(key, previous, directive.activationSequence(), format(now)) != 1)
            throw ReleaseBundleProtocolError.ACTIVATION_SEQUENCE_CONFLICT.conflict();
        return receipt(key, row, directive.activationSequence());
    }

    /** 读历史接收事实不会延长授权期限；使用方仍须在实际应用前重新验签和检查当前门禁。 */
    public Receipt latest(String campaignId) {
        var scope = TenantContextHolder.requireCurrent(); scope.requirePermission(RECEIVE_PERMISSION);
        if (campaignId == null || !campaignId.matches("[A-Za-z0-9_.:-]{1,64}"))
            throw ReleaseBundleProtocolError.ACTIVATION_REQUEST_INVALID.invalid();
        var key = key(scope.tenantId().value(), campaignId);
        var row = repository.latest(key).orElseThrow(ReleaseBundleProtocolError.ACTIVATION_NOT_RECORDED::notFound);
        return receipt(key, row, row.activationSequence());
    }

    private BundleDirectiveRepository.Key key(String tenant, String campaign) {
        return new BundleDirectiveRepository.Key(tenant, slot.environment(), slot.cell(), slot.namespace(), campaign);
    }

    private ReleaseManifest localMember(ReleaseBundleActivationDirective directive) {
        return directive.bundle().members().stream().filter(member -> RUNTIME_NAME.equals(member.runtime()) && slot.matches(member))
                .findFirst().orElseThrow(ReleaseBundleProtocolError.MEMBER_MISMATCH::conflict);
    }

    private Receipt receipt(BundleDirectiveRepository.Key key, BundleDirectiveRepository.StoredDirective row, long latest) {
        var directive = read(key, row);
        try {
            return new Receipt(RECORDED, directive, latest, row.activationSequence() == latest, Instant.parse(row.recordedAt()));
        } catch (java.time.DateTimeException corrupt) { throw ReleaseBundleProtocolError.ACTIVATION_STORED_INVALID.conflict(); }
    }

    private ReleaseBundleActivationDirective read(BundleDirectiveRepository.Key key, BundleDirectiveRepository.StoredDirective row) {
        try {
            if (row.directiveJson().length() > MAX_REQUEST_BYTES) throw ReleaseBundleProtocolError.ACTIVATION_STORED_INVALID.conflict();
            ReleaseBundleActivationDirective value = mapper.readerFor(ReleaseBundleActivationDirective.class)
                    .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readValue(row.directiveJson());
            if (value == null || !key.tenantId().equals(value.bundle().tenantId().value())
                    || !key.campaignId().equals(value.bundle().campaignId()) || !row.directiveId().equals(value.directiveId())
                    || row.activationSequence() != value.activationSequence()) throw ReleaseBundleProtocolError.ACTIVATION_STORED_INVALID.conflict();
            localMember(value);
            return value;
        } catch (JacksonException | IllegalArgumentException corrupt) {
            throw ReleaseBundleProtocolError.ACTIVATION_STORED_INVALID.conflict();
        }
    }

    /** current仅表示最高已接收序号，不能用它表示线上配置已生效。 */
    public record Receipt(String state, ReleaseBundleActivationDirective directive, long latestSequence, boolean current, Instant recordedAt) { }
}
