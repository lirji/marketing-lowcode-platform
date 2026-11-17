package com.acme.marketing.control.application;

import static com.acme.marketing.platform.time.SqlTime.format;

import com.acme.marketing.contracts.release.ArtifactReference;
import com.acme.marketing.contracts.release.PinnedRuntimeAckVerifier;
import com.acme.marketing.contracts.release.ReleaseBundleAck;
import com.acme.marketing.contracts.release.ReleaseBundleManifest;
import com.acme.marketing.contracts.release.ReleaseManifest;
import com.acme.marketing.contracts.release.ReleaseManifestSigner;
import com.acme.marketing.control.application.ReleaseBundleService.BundleView;
import com.acme.marketing.platform.crypto.SigningKeyRing;
import com.acme.marketing.platform.identity.TenantId;
import com.acme.marketing.platform.web.TenantContextHolder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/** 组合运行证据由控制面拥有；就绪是当前时点的计算结果，绝不直接激活。 */
@Service
public class ReleaseBundleReadinessService {
    public static final int MAX_ACK_BYTES = 16 * 1024;
    private static final int MAX_RUNTIME_IDS_PER_MEMBER = 64;
    private static final int MAX_ACK_ROWS = ReleaseBundleManifest.MAX_MEMBERS * MAX_RUNTIME_IDS_PER_MEMBER;
    private static final Duration ACK_FRESHNESS = Duration.ofMinutes(5);
    private static final Duration CLOCK_SKEW = Duration.ofMinutes(1);
    private static final String ACK_ACTION = "RELEASE_BUNDLE_ACKNOWLEDGED";
    private final ControlRepository repository;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final PinnedRuntimeAckVerifier verifier;
    private final SigningKeyRing releaseKeys;
    private final ReleaseManifestSigner manifestSigner = new ReleaseManifestSigner();
    private final int minimumReplicas;
    private final long minimumCapacity;
    private final long maximumCapacity;
    private final String allowedBuild;

    /** 复用原release-policy配置，组合门槛按各个成员独立计算。 */
    public ReleaseBundleReadinessService(ControlRepository repository, ObjectMapper mapper, Clock clock,
            PinnedRuntimeAckVerifier verifier, SigningKeyRing releaseKeys,
            @Value("${marketing.release-policy.minimum-ready-replicas:1}") int minimumReplicas,
            @Value("${marketing.release-policy.minimum-ready-capacity:1}") long minimumCapacity,
            @Value("${marketing.release-policy.max-ready-capacity-per-runtime:1000}") long maximumCapacity,
            @Value("${marketing.release-policy.allowed-runtime-build-digest:}") String allowedBuild) {
        if (minimumReplicas < 1 || minimumReplicas > MAX_RUNTIME_IDS_PER_MEMBER || minimumCapacity < 1
                || maximumCapacity < 1 || maximumCapacity > Long.MAX_VALUE / MAX_RUNTIME_IDS_PER_MEMBER)
            throw new IllegalArgumentException("组合运行就绪策略超出有界副本或容量范围");
        this.repository = repository; this.mapper = mapper; this.clock = clock; this.verifier = verifier;
        this.releaseKeys = releaseKeys; this.minimumReplicas = minimumReplicas;
        this.minimumCapacity = minimumCapacity; this.maximumCapacity = maximumCapacity;
        this.allowedBuild = allowedBuild == null ? "" : allowedBuild.trim();
    }

    /** 在解析较大的ACK前校验权限和当前活动范围；本方法不建立写事务快照。 */
    public void authorizeAck(String campaignId) {
        var scope = TenantContextHolder.requireCurrent();
        scope.requirePermission("runtime:ack");
        ControlCampaignScope.require(repository, scope, campaignId, false);
    }

    /** 先锁既有组合头，再读取范围和证据，避免RR在等待锁之前固定旧快照。 */
    @Transactional(timeout = 10)
    public BundleReadinessView acknowledge(String campaignId, long version, ReleaseBundleAck ack) {
        var scope = TenantContextHolder.requireCurrent();
        scope.requirePermission("runtime:ack");
        if (version < 1) throw ReleaseBundleError.INPUT_INVALID.invalid();
        var row = repository.lockReleaseBundle(scope.tenantId().value(), campaignId, version)
                .orElseThrow(ReleaseBundleError.NOT_FOUND::notFound);
        ControlCampaignScope.require(repository, scope, campaignId, false);
        BundleView bundle = read(row);
        Instant now = clock.instant();
        if (!valid(scope.tenantId().value(), bundle, ack, now, bundle.members().stream().allMatch(member -> current(scope.tenantId().value(), member, now)))) throw ReleaseBundleError.ACK_INVALID.conflict();
        List<ControlRepository.ReleaseBundleAckRow> evidence = evidence(scope.tenantId().value(), bundle.bundleId());
        var previous = evidence.stream().filter(item -> item.manifestId().equals(ack.runtimeAck().manifestId())
                && item.runtimeId().equals(ack.runtimeAck().runtimeId())).findFirst();
        if (previous.isPresent()) {
            String incoming = format(ack.runtimeAck().acknowledgedAt());
            int order = incoming.compareTo(previous.orElseThrow().acknowledgedAt());
            if (order == 0 && !ack.signature().equals(previous.orElseThrow().signatureValue()))
                throw ReleaseBundleError.ACK_CONFLICT.conflict();
            if (order <= 0) return evaluate(scope.tenantId().value(), bundle, evidence, now);
        } else if (evidence.stream().filter(item -> item.manifestId().equals(ack.runtimeAck().manifestId())).count()
                >= MAX_RUNTIME_IDS_PER_MEMBER) throw ReleaseBundleError.ACK_LIMIT.conflict();
        String content = mapper.writeValueAsString(ack);
        if (content.getBytes(StandardCharsets.UTF_8).length > MAX_ACK_BYTES) throw ReleaseBundleError.REQUEST_TOO_LARGE.invalid();
        var saved = new ControlRepository.ReleaseBundleAckRow(scope.tenantId().value(), bundle.bundleId(),
                ack.runtimeAck().manifestId(), ack.runtimeAck().runtimeId(), content,
                format(ack.runtimeAck().acknowledgedAt()), ack.signature());
        repository.saveReleaseBundleAck(saved);
        ControlAuditChain.append(repository, scope.tenantId().value(), scope.actorId(), ACK_ACTION, bundle.bundleId(), now);
        var updated = new ArrayList<>(evidence);
        previous.ifPresent(updated::remove); updated.add(saved);
        return evaluate(scope.tenantId().value(), bundle, updated, now);
    }

    /** 就绪会随时间或信任变化而失效，读取时重新计算，不修改冻结的PREPARING历史。 */
    @Transactional(readOnly = true, timeout = 10)
    public BundleReadinessView readiness(String campaignId, long version) {
        var scope = TenantContextHolder.requireCurrent();
        scope.requirePermission("release:read");
        if (version < 1) throw ReleaseBundleError.INPUT_INVALID.invalid();
        ControlCampaignScope.require(repository, scope, campaignId, false);
        BundleView bundle = read(repository.findReleaseBundle(scope.tenantId().value(), campaignId, version)
                .orElseThrow(ReleaseBundleError.NOT_FOUND::notFound));
        return evaluate(scope.tenantId().value(), bundle, evidence(scope.tenantId().value(), bundle.bundleId()), clock.instant());
    }

    private List<ControlRepository.ReleaseBundleAckRow> evidence(String tenantId, String bundleId) {
        var result = repository.findReleaseBundleAcks(tenantId, bundleId, MAX_ACK_ROWS + 1);
        if (result.size() > MAX_ACK_ROWS || result.stream().collect(Collectors.groupingBy(
                ControlRepository.ReleaseBundleAckRow::manifestId, Collectors.counting())).values().stream()
                .anyMatch(count -> count > MAX_RUNTIME_IDS_PER_MEMBER)) throw ReleaseBundleError.ACK_LIMIT.conflict();
        return result;
    }

    private BundleView read(ControlRepository.ReleaseBundleRow row) {
        try {
            var bundle = mapper.readValue(row.contentJson(), BundleView.class);
            if (!row.bundleId().equals(bundle.bundleId()) || !row.campaignId().equals(bundle.campaignId())
                    || row.versionNo() != bundle.version() || !row.contentHash().equals(bundle.contentHash())
                    || !row.environmentName().equals(bundle.environment()) || !row.cellId().equals(bundle.cell())
                    || bundle.members().size() < 2 || bundle.members().size() > ReleaseBundleManifest.MAX_MEMBERS
                    || !bundle.contentHash().equals(ReleaseBundleManifest.contentHash(row.tenantId(), row.campaignId(), bundle.members())))
                throw ReleaseBundleError.CONTENT_INVALID.conflict();
            // 复用签名合同的成员/slot/时间约束；历史过期仍可读取，当前有效性在计算时判断。
            new ReleaseBundleManifest(bundle.bundleId(), new TenantId(row.tenantId()), bundle.campaignId(), bundle.version(),
                    bundle.environment(), bundle.cell(), bundle.contentHash(), bundle.members(), bundle.createdAt(),
                    bundle.members().stream().map(ReleaseManifest::expiresAt).min(Instant::compareTo).orElseThrow(),
                    releaseKeys.activeKeyId(), "");
            return bundle;
        } catch (JacksonException | IllegalArgumentException invalid) { throw ReleaseBundleError.CONTENT_INVALID.conflict(); }
    }

    private boolean valid(String tenantId, BundleView bundle, ReleaseBundleAck ack, Instant now, boolean currentBundle) {
        var runtime = ack.runtimeAck();
        if (!currentBundle || !bundle.bundleId().equals(ack.bundleId()) || !bundle.campaignId().equals(ack.campaignId())
                || bundle.version() != ack.version() || !bundle.contentHash().equals(ack.contentHash())
                || !runtime.runtimeId().matches("[A-Za-z0-9_.:-]{1,128}") || !verifier.verify(tenantId, ack)
                || runtime.acknowledgedAt().isBefore(now.minus(ACK_FRESHNESS))
                || runtime.acknowledgedAt().isAfter(now.plus(CLOCK_SKEW))
                || runtime.acknowledgedAt().isBefore(bundle.createdAt().minus(CLOCK_SKEW))
                || runtime.capacity() > maximumCapacity || (!allowedBuild.isEmpty() && !allowedBuild.equals(runtime.buildDigest())))
            return false;
        var member = bundle.members().stream().filter(item -> item.manifestId().equals(runtime.manifestId())).findFirst();
        if (member.isEmpty()) return false;
        var manifest = member.orElseThrow();
        return manifest.signature().equals(ack.memberSignature())
                && manifest.generation() == runtime.generation() && manifest.cell().equals(runtime.cell())
                && runtime.acknowledgedAt().isBefore(manifest.expiresAt())
                && runtime.supportedAbis().containsAll(manifest.artifacts().stream().map(ArtifactReference::abi).collect(Collectors.toSet()))
                && runtime.warmedArtifactIds().equals(manifest.artifacts().stream().map(ArtifactReference::artifactId).collect(Collectors.toSet()));
    }

    private boolean current(String tenantId, ReleaseManifest manifest, Instant now) {
        return tenantId.equals(manifest.tenantId().value()) && manifest.expiresAt().isAfter(now)
                && releaseKeys.publicKeys().values().stream().anyMatch(key -> manifestSigner.verify(manifest, key));
    }

    private BundleReadinessView evaluate(String tenantId, BundleView bundle,
            List<ControlRepository.ReleaseBundleAckRow> evidence, Instant now) {
        var members = new ArrayList<MemberReadiness>();
        // 本次请求内只验证一次不变成员，避免副本数放大后重复计算同一组签名。
        boolean currentBundle = bundle.members().stream().allMatch(item -> current(tenantId, item, now));
        for (ReleaseManifest member : bundle.members()) {
            int replicas = 0, ignored = 0; long capacity = 0;
            for (var row : evidence) {
                if (!member.manifestId().equals(row.manifestId())) continue;
                try {
                    var ack = mapper.readValue(row.ackJson(), ReleaseBundleAck.class);
                    if (!row.runtimeId().equals(ack.runtimeAck().runtimeId()) || !row.manifestId().equals(ack.runtimeAck().manifestId())
                            || !row.signatureValue().equals(ack.signature()) || !row.acknowledgedAt().equals(format(ack.runtimeAck().acknowledgedAt()))
                            || !valid(tenantId, bundle, ack, now, currentBundle)) { ignored++; continue; }
                    replicas++; capacity = Math.addExact(capacity, ack.runtimeAck().capacity());
                } catch (JacksonException invalid) { ignored++; }
            }
            boolean ready = currentBundle && replicas >= minimumReplicas && capacity >= minimumCapacity;
            members.add(new MemberReadiness(member.manifestId(), member.runtime(), member.namespace(), replicas, capacity, ignored, ready));
        }
        Instant expiresAt = bundle.members().stream().map(ReleaseManifest::expiresAt).min(Instant::compareTo).orElseThrow();
        return new BundleReadinessView(bundle.bundleId(), bundle.version(), bundle.contentHash(),
                members.stream().allMatch(MemberReadiness::runtimeReady), now, expiresAt, members);
    }

    /** 只输出聚合准备状态，不泄露运行端完整证据或改变活动上线状态。 */
    public record BundleReadinessView(String bundleId, long version, String contentHash, boolean runtimeReady,
            Instant checkedAt, Instant expiresAt, List<MemberReadiness> members) {
        public BundleReadinessView { members = List.copyOf(members); }
    }
    /** 副本数和容量仅在同一成员内累加；损坏或过期证据可观察但不计入门槛。 */
    public record MemberReadiness(String manifestId, String runtime, String namespace, int readyReplicas,
            long readyCapacity, int ignoredEvidence, boolean runtimeReady) { }
}
