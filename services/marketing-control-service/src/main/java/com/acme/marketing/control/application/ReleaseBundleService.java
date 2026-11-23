package com.acme.marketing.control.application;

import static com.acme.marketing.platform.time.SqlTime.format;

import com.acme.marketing.contracts.release.ReleaseManifest;
import com.acme.marketing.contracts.release.ReleaseManifestSigner;
import com.acme.marketing.contracts.release.ReleaseBundleManifest;
import com.acme.marketing.contracts.release.ReleaseBundleManifestSigner;
import com.acme.marketing.platform.crypto.SigningKeyRing;
import com.acme.marketing.control.domain.DefinitionVersion;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.acme.marketing.platform.identity.TenantScope;
import com.acme.marketing.platform.web.TenantContextHolder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** 准备不可变活动组合；准备成功不产生任何运行端激活副作用。 */
@Service
public class ReleaseBundleService {
    public static final String SAVE_OPERATION = "release.bundle.prepare";
    private static final String PREPARED_ACTION = "RELEASE_BUNDLE_PREPARED";
    private static final java.util.Set<String> SUPPORTED_RUNTIMES = java.util.Set.of("decision", "journey");
    private static final int MAX_CONTENT_BYTES = 512 * 1024;
    private final ControlRepository repository;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final SigningKeyRing keys;
    private final ReleaseManifestSigner signer = new ReleaseManifestSigner();

    /** 版本、成员归属与审计均由同一个控制库事务拥有。 */
    public ReleaseBundleService(ControlRepository repository, ObjectMapper mapper, Clock clock, SigningKeyRing keys) {
        this.repository = repository; this.mapper = mapper; this.clock = clock; this.keys = keys;
    }

    /** 幂等回放前先检查当前权限和活动范围，防止旧回执越权读取。 */
    public void authorizeWrite(String campaignId) {
        var scope = TenantContextHolder.requireCurrent();
        scope.requirePermission("release:write");
        campaign(scope, campaignId, false);
    }

    /** 清单按固定顺序锁定，与单清单激活竞争时只能一方成功。 */
    @Transactional(timeout = 10)
    public BundleView prepare(String campaignId, PrepareRequest request, String commandId) {
        var scope = TenantContextHolder.requireCurrent();
        scope.requirePermission("release:write");
        campaign(scope, campaignId, true);
        long previous = repository.lockReleaseBundleVersion(scope.tenantId().value(), campaignId);
        if (request.expectedVersion() != previous) throw ReleaseBundleError.VERSION_CONFLICT.conflict();
        Instant now = clock.instant();
        List<ReleaseManifest> members = new ArrayList<>();
        var slots = new HashSet<List<String>>();
        for (String id : request.manifestIds()) {
            var row = repository.findManifest(scope.tenantId().value(), id, true)
                    .orElseThrow(ReleaseBundleError.MEMBER_UNAVAILABLE::conflict);
            ReleaseManifest manifest = mapper.readValue(row.manifestJson(), ReleaseManifest.class);
            validateMember(scope, campaignId, id, row.stateName(), manifest, now);
            if (repository.findReleaseBundleOwner(scope.tenantId().value(), id, true).isPresent())
                throw ReleaseBundleError.MEMBER_ALREADY_BOUND.conflict();
            if (!members.isEmpty() && (!members.getFirst().environment().equals(manifest.environment())
                    || !members.getFirst().cell().equals(manifest.cell())))
                throw ReleaseBundleError.MEMBER_SCOPE_MISMATCH.conflict();
            if (!slots.add(List.of(manifest.runtime(), manifest.namespace())))
                throw ReleaseBundleError.DUPLICATE_SLOT.conflict();
            members.add(manifest);
        }
        // 成员锁全部取得后再按完整slot排序，避免多个组合以不同成员ID顺序争用slot而死锁。
        for (ReleaseManifest member : members.stream().sorted(java.util.Comparator
                .comparing(ReleaseManifest::environment).thenComparing(ReleaseManifest::cell)
                .thenComparing(ReleaseManifest::runtime).thenComparing(ReleaseManifest::namespace)).toList()) {
            var slot = repository.findReleaseSlot(scope.tenantId().value(), member.environment(), member.cell(),
                    member.runtime(), member.namespace(), true).orElseThrow(ReleaseBundleError.MEMBER_UNAVAILABLE::conflict);
            if (!slot.bundleManaged() && repository.markReleaseSlotBundleManaged(scope.tenantId().value(),
                    member.environment(), member.cell(), member.runtime(), member.namespace()) != 1)
                throw ReleaseBundleError.MEMBER_UNAVAILABLE.conflict();
        }
        var view = new BundleView(UUID.randomUUID().toString(), campaignId, previous + 1,
                members.getFirst().environment(), members.getFirst().cell(), State.PREPARING,
                ReleaseBundleManifest.contentHash(scope.tenantId().value(), campaignId, members), members, scope.actorId(), now);
        String content = mapper.writeValueAsString(view);
        if (content.getBytes(StandardCharsets.UTF_8).length > MAX_CONTENT_BYTES)
            throw ReleaseBundleError.REQUEST_TOO_LARGE.invalid();
        if (repository.advanceReleaseBundleVersion(scope.tenantId().value(), campaignId, previous) != 1)
            throw ReleaseBundleError.VERSION_CONFLICT.conflict();
        repository.saveReleaseBundle(new ControlRepository.ReleaseBundleRow(scope.tenantId().value(),
                view.bundleId(), campaignId, view.version(), view.environment(), view.cell(), view.state().code(),
                content, view.contentHash(), commandId, scope.actorId(), format(now)));
        members.forEach(member -> repository.saveReleaseBundleMember(new ControlRepository.ReleaseBundleMemberWrite(
                scope.tenantId().value(), member.manifestId(), view.bundleId())));
        ControlAuditChain.append(repository, scope.tenantId().value(), scope.actorId(), PREPARED_ACTION,
                view.bundleId(), now);
        return view;
    }

    /** 返回最新已持久化的准备版本，不将其展示为已激活组合。 */
    public BundleView latest(String campaignId) {
        var scope = TenantContextHolder.requireCurrent();
        scope.requirePermission("release:read");
        campaign(scope, campaignId, false);
        return read(repository.findLatestReleaseBundle(scope.tenantId().value(), campaignId, false)
                .orElseThrow(ReleaseBundleError.NOT_FOUND::notFound));
    }

    /** 历史内容不可覆盖，读取仍遵循当前活动数据范围。 */
    public BundleView version(String campaignId, long version) {
        var scope = TenantContextHolder.requireCurrent();
        scope.requirePermission("release:read");
        if (version < 1) throw ReleaseBundleError.INPUT_INVALID.invalid();
        campaign(scope, campaignId, false);
        return read(repository.findReleaseBundle(scope.tenantId().value(), campaignId, version)
                .orElseThrow(ReleaseBundleError.NOT_FOUND::notFound));
    }

    /** 重新鉴权后导出冻结内容；签名不修改准备版本，也不提供运行端激活授权。 */
    public ReleaseBundleManifest manifest(String campaignId, long version) {
        var scope = TenantContextHolder.requireCurrent();
        scope.requirePermission("release:read");
        return manifestForOperation(campaignId, version);
    }

    /** 操作路径独立检查具体发布权限，公共读接口仍只允许运营读取权限。 */
    ReleaseBundleManifest manifestForOperation(String campaignId, long version) {
        if (version < 1) throw ReleaseBundleError.INPUT_INVALID.invalid();
        var scope = TenantContextHolder.requireCurrent();
        campaign(scope, campaignId, false);
        BundleView stored = read(repository.findReleaseBundle(scope.tenantId().value(), campaignId, version)
                .orElseThrow(ReleaseBundleError.NOT_FOUND::notFound));
        Instant expiresAt = stored.members().stream().map(ReleaseManifest::expiresAt).min(Instant::compareTo).orElseThrow();
        // 重新核验成员，避免存储损坏或已退出信任集合的旧密钥被外层新签名重新背书。
        if (!expiresAt.isAfter(clock.instant()) || stored.members().stream().anyMatch(member ->
                keys.publicKeys().values().stream().noneMatch(key -> signer.verify(member, key))))
            throw ReleaseBundleError.MEMBER_UNAVAILABLE.conflict();
        var unsigned = new ReleaseBundleManifest(stored.bundleId(), TenantContextHolder.requireCurrent().tenantId(),
                stored.campaignId(), stored.version(), stored.environment(), stored.cell(), stored.contentHash(),
                stored.members(), stored.createdAt(), expiresAt, keys.activeKeyId(), "");
        return ReleaseBundleManifestSigner.sign(keys.activePrivateKey(), unsigned);
    }

    private BundleView read(ControlRepository.ReleaseBundleRow row) {
        return mapper.readValue(row.contentJson(), BundleView.class);
    }

    private void campaign(TenantScope scope, String campaignId, boolean lock) {
        if (!identifier(campaignId)) throw ReleaseBundleError.INPUT_INVALID.invalid();
        ControlCampaignScope.require(repository, scope, campaignId, lock);
    }

    void validateMember(TenantScope scope, String campaignId, String id, String state,
            ReleaseManifest manifest, Instant now) {
        if (!ReleaseApplicationService.State.STAGED.code().equals(state)
                || !scope.tenantId().equals(manifest.tenantId()) || !id.equals(manifest.manifestId())
                || !manifest.expiresAt().isAfter(now) || manifest.signature().isBlank()
                || manifest.canaryBasisPoints() != 0 || !SUPPORTED_RUNTIMES.contains(manifest.runtime())
                || manifest.approvalCaseIds().isEmpty()
                || keys.publicKeys().values().stream().noneMatch(key -> signer.verify(manifest, key))) throw ReleaseBundleError.MEMBER_UNAVAILABLE.conflict();
        for (var artifact : manifest.artifacts()) {
            var definition = repository.findDefinition(scope.tenantId().value(), artifact.definitionId(),
                    artifact.definitionVersion()).orElseThrow(ReleaseBundleError.MEMBER_UNAVAILABLE::conflict);
            if (!campaignId.equals(definition.campaignId())) throw ReleaseBundleError.MEMBER_CAMPAIGN_MISMATCH.conflict();
            if (!DefinitionVersion.Status.APPROVED.name().equals(definition.status()) || !artifact.sourceDigest().equals(definition.semanticHash()))
                throw ReleaseBundleError.MEMBER_UNAVAILABLE.conflict();
            for (String approval : manifest.approvalCaseIds()) {
                if (repository.countApprovedCase(scope.tenantId().value(), approval, artifact.definitionId(),
                        artifact.definitionVersion()) != 1) throw ReleaseBundleError.MEMBER_UNAVAILABLE.conflict();
            }
        }
    }

    private static boolean identifier(String value) {
        return value != null && value.matches("[A-Za-z0-9_.:-]{1,64}");
    }

    /** 输入成员按ID排序，同一个集合换序重试仍是同一意图。 */
    public record PrepareRequest(long expectedVersion, List<String> manifestIds) {
        public PrepareRequest {
            if (expectedVersion < 0 || expectedVersion == Long.MAX_VALUE || manifestIds == null
                    || manifestIds.size() < 2 || manifestIds.size() > ReleaseBundleManifest.MAX_MEMBERS
                    || manifestIds.stream().anyMatch(id -> !identifier(id))
                    || new HashSet<>(manifestIds).size() != manifestIds.size())
                throw ReleaseBundleError.INPUT_INVALID.invalid();
            manifestIds = manifestIds.stream().sorted().toList();
        }
    }

    /** 状态使用显式稳定code；后续就绪/激活由对应切片新增，不能自动晋升。 */
    public enum State {
        PREPARING("PREPARING");
        private final String code;
        State(String code) { this.code = code; }
        @JsonValue public String code() { return code; }
        /** 未知状态不能默认解释成可发布。 */
        @JsonCreator public static State fromCode(String code) {
            for (State value : values()) if (value.code.equals(code)) return value;
            throw ReleaseBundleError.INPUT_INVALID.invalid();
        }
    }

    /** 原始签名清单随组合冻结，读取不拼接后来变化的最新定义。 */
    public record BundleView(String bundleId, String campaignId, long version, String environment, String cell,
            State state, String contentHash, List<ReleaseManifest> members, String createdBy, Instant createdAt) {
        public BundleView { members = List.copyOf(members); }
    }
}
