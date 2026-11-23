package com.acme.marketing.decision.application;

import static com.acme.marketing.platform.time.SqlTime.format;

import com.acme.marketing.contracts.release.*;
import com.acme.marketing.decision.application.BundleAdmissionRepository.Key;
import com.acme.marketing.decision.application.DecisionInventoryRepository.Slot;
import com.acme.marketing.decision.deployment.DecisionInventoryJson;
import com.acme.marketing.decision.runtime.DecisionKillSwitchRegistry;
import com.acme.marketing.platform.identity.TenantScope;
import com.acme.marketing.platform.web.TenantContextHolder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Decision 作为 cell 唯一准入写入者，完整覆盖校验、历史及共享指针在同一事务内提交。 */
@Service
public class BundleApplicationService {
    private final BundleApplicationRepository applications;
    private final BundleAdmissionRepository admissions;
    private final BundleDirectiveRepository directives;
    private final DecisionBundleDirectiveService sources;
    private final PinnedReleaseBundleActivationVerifier verifier;
    private final BundleParticipationSigningService signing;
    private final DecisionExecutionTrust trust;
    private final DecisionApplicationService quotes;
    private final DecisionKillSwitchRegistry kills;
    private final Slot local;
    private final Clock clock;
    private final ObjectMapper mapper;

    public BundleApplicationService(BundleApplicationRepository applications, BundleAdmissionRepository admissions,
            BundleDirectiveRepository directives, DecisionBundleDirectiveService sources,
            PinnedReleaseBundleActivationVerifier verifier, BundleParticipationSigningService signing,
            DecisionExecutionTrust trust, DecisionApplicationService quotes, DecisionKillSwitchRegistry kills, Clock clock, ObjectMapper mapper,
            @Value("${marketing.runtime.environment:local}") String environment, @Value("${marketing.runtime.cell:cell-a}") String cell,
            @Value("${marketing.runtime.namespace:main}") String namespace) {
        this.applications = applications; this.admissions = admissions; this.directives = directives; this.sources = sources;
        this.verifier = verifier; this.signing = signing; this.trust = trust; this.quotes = quotes; this.kills = kills;
        this.clock = clock; this.mapper = mapper; this.local = new Slot(environment, cell, namespace);
    }

    public record ApplyRequest(Long expectedAppliedSequence, List<JourneyBundleExecutionAck> journeyAcks, Instant applyBefore) {
        public ApplyRequest {
            if (expectedAppliedSequence == null || expectedAppliedSequence < 0 || journeyAcks == null
                    || journeyAcks.size() > ReleaseBundleManifest.MAX_MEMBERS * JourneyBundleExecutionProbe.MAX_PARALLELISM)
                throw ReleaseBundleProtocolError.EXECUTION_REQUEST_INVALID.invalid();
            journeyAcks = List.copyOf(journeyAcks);
        }
    }

    /** 完整保留当时验证的签名证据，后续实例替换和回执刷新仍能审计原应用。 */
    private record Evidence(List<DecisionExecutionReceipt> decision, List<JourneyBundleExecutionAck> journey) {}

    public record Applied(String state, ReleaseBundleActivationDirective directive, Instant appliedAt, long currentSequence) {}
    private static final String APPLIED = "APPLIED";
    public static final String APPLY_PERMISSION = "bundle:apply";

    /** 所有副本均独立验证当前授权与真实制品。回执在返回前随本地事务一起持久提交。 */
    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 15)
    public DecisionExecutionReceipt attest(String campaign, long sequence) {
        TenantScope scope = authenticated(); Key key = key(scope, campaign, sequence);
        var location = directiveKey(key, local.namespace());
        requireLatest(location, sequence);
        var source = source(scope, location, sequence);
        var inventory = inventory(local);
        kills.requireEnabledForApplication(key.tenantId(), local.namespace());
        var member = sources.localMember(source);
        var id = UUID.randomUUID().toString(); var subject = UUID.randomUUID().toString();
        var bundle = source.bundle();
        var proof = signing.sign(new ReleaseBundleParticipation(id, scope.tenantId(), campaign, local.environment(), local.cell(),
                id, ReleaseBundleParticipation.subjectHash(scope.tenantId(), subject), source.directiveId(), sequence, source.signature(),
                bundle.bundleId(), bundle.version(), bundle.contentHash(), clock.instant(), signing.keyId(), ""));
        trust.requireAdmission(proof);
        if (member != null) quotes.verifyExecution(scope, source, proof, subject, member.generation());
        var ack = trust.sign(inventory, source, signing.keyId(), clock.instant());
        String original = applications.receipt(key, local.namespace(), sequence, inventory.sequence(), ack.replica().instanceId());
        if (original != null) {
            var existing = mapper.readValue(original, DecisionExecutionReceipt.class);
            trust.requireLocal(inventory, existing, source, signing.keyId(), clock.instant());
            return existing;
        }
        applications.attest(key, local.namespace(), sequence, inventory.sequence(), ack.replica().instanceId(), mapper.writeValueAsString(ack));
        return ack;
    }

    /** 全成员证据齐备后推进；超时重试先恢复原序号的提交结果，已应用的旧参与不改写。 */
    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 30)
    public Applied apply(String campaign, long sequence, ApplyRequest request) {
        var scope = authenticated(); Key key = key(scope, campaign, sequence);
        scope.requirePermission(APPLY_PERMISSION);
        applications.serialize(key);
        var original = admissions.applied(key, sequence);
        if (original.isPresent()) return applied(scope, key, original.orElseThrow());
        long current = current(key);
        if (sequence <= current || request.expectedAppliedSequence() != current)
            throw ReleaseBundleProtocolError.ACTIVATION_SEQUENCE_CONFLICT.conflict();
        var source = source(scope, directiveKey(key, local.namespace()), sequence);
        var bundle = source.bundle();
        if (ReleaseBundleActivationDirective.Action.ROLLBACK.code().equals(source.action())
                && !applications.previouslyApplied(key, bundle.bundleId(), bundle.version(), bundle.contentHash()))
            throw ReleaseBundleProtocolError.ROLLBACK_NOT_APPLIED.conflict();

        var namespaces = bundle.members().stream().filter(member -> "decision".equals(member.runtime()))
                .map(ReleaseManifest::namespace).sorted().toList();
        // 纯 Journey 也必须证明 Decision 准入能力；它不因此接管旧单清单报价。
        var positions = namespaces.isEmpty() ? List.of(local.namespace()) : namespaces;
        var coverage = coverage(key, sequence, source, positions, request.journeyAcks());
        for (String namespace : namespaces) {
            if (applications.protectSlot(key, namespace) != 1 && !Boolean.TRUE.equals(applications.protectedSlot(key, namespace)))
                throw ReleaseBundleProtocolError.ACTIVATION_STORED_INVALID.conflict();
        }
        String evidence = mapper.writeValueAsString(coverage.evidence());
        // 等待在途新准入释放共享锁后，再检查整组签名和截止时间，防止排队越过窗口。
        applications.lockPointer(key);
        for (String namespace : positions) kills.requireEnabledForApplication(key.tenantId(), namespace);
        verifier.requireValid(key.tenantId(), source, clock.instant());
        String document = mapper.writeValueAsString(source);
        Instant now = clock.instant();
        requireWindow(coverage.deadline(), request.applyBefore(), now);
        applications.append(key, sequence, source.directiveId(), document, evidence, format(now));
        requireWindow(coverage.deadline(), request.applyBefore(), clock.instant());
        int changed = current == 0 ? applications.first(key, sequence, format(now)) : applications.advance(key, current, sequence, format(now));
        if (changed != 1) throw ReleaseBundleProtocolError.ACTIVATION_SEQUENCE_CONFLICT.conflict();
        return new Applied(APPLIED, source, now, sequence);
    }

    private record VerifiedCoverage(Evidence evidence, Instant deadline) {}

    /** 固定顺序取得各位置的接收与部署锁；覆盖快照保留最早截止时间供最终指针写入复核。 */
    private VerifiedCoverage coverage(Key key, long sequence, ReleaseBundleActivationDirective source,
            List<String> positions, List<JourneyBundleExecutionAck> journeyAcks) {
        var decisionEvidence = new ArrayList<DecisionExecutionReceipt>();
        var executionKeys = new HashSet<String>();
        var instances = new HashSet<String>();
        var evidenceDeadline = source.bundle().expiresAt();
        for (String namespace : positions) {
            var location = directiveKey(key, namespace);
            requireLatest(location, sequence);
            var stored = directives.history(location, sequence).orElseThrow(ReleaseBundleProtocolError.ACTIVATION_NOT_RECORDED::conflict);
            if (!source.equals(mapper.readValue(stored.directiveJson(), ReleaseBundleActivationDirective.class)))
                throw ReleaseBundleProtocolError.ACTIVATION_SEQUENCE_CONFLICT.conflict();
            var inventory = inventory(new Slot(local.environment(), local.cell(), namespace));
            var receipts = applications.receipts(key, namespace, sequence, inventory.sequence()).stream()
                    .map(json -> mapper.readValue(json, DecisionExecutionReceipt.class)).toList();
            trust.requireDecision(inventory, receipts, source, signing.keyId(), clock.instant());
            // 不同 namespace 的流量清单也不能把同一实例或同一私钥算作两个独立执行者。
            for (var ack : receipts) {
                if (!executionKeys.add(ack.replica().executionKeyId()) || !instances.add(ack.replica().instanceId()))
                    throw ReleaseBundleProtocolError.DECISION_INVENTORY_INVALID.conflict();
                decisionEvidence.add(ack);
            }
            if (inventory.expiresAt().isBefore(evidenceDeadline)) evidenceDeadline = inventory.expiresAt();
        }
        trust.requireJourney(source, journeyAcks, signing.keyId(), clock.instant());
        for (var ack : journeyAcks)
            if (ack.probe().expiresAt().isBefore(evidenceDeadline)) evidenceDeadline = ack.probe().expiresAt();
        return new VerifiedCoverage(new Evidence(decisionEvidence, journeyAcks), evidenceDeadline);
    }

    private static void requireWindow(Instant evidenceDeadline, Instant applyBefore, Instant now) {
        if (!now.isBefore(evidenceDeadline) || (applyBefore != null && !now.isBefore(applyBefore)))
            throw ReleaseBundleProtocolError.APPLICATION_DEADLINE.conflict();
    }

    /** 精确查询原历史，后来的发布只改变 currentSequence。 */
    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 30)
    public Applied get(String campaign, long sequence, Instant confirmAbsentAfter) {
        var scope = authenticated(); var key = key(scope, campaign, sequence);
        applications.lockApplication(key);
        var history = admissions.applied(key, sequence);
        if (history.isPresent()) return applied(scope, key, history.orElseThrow());
        // 由执行方自己的时钟确认迟到写入已不可能，避免控制面时钟提前造成假失败。
        if (confirmAbsentAfter != null && clock.instant().isBefore(confirmAbsentAfter))
            throw ReleaseBundleProtocolError.EXECUTION_COVERAGE_INCOMPLETE.conflict();
        throw ReleaseBundleProtocolError.ADMISSION_NOT_APPLIED.notFound();
    }

    private Applied applied(TenantScope scope, Key key, BundleAdmissionRepository.AppliedAuthorization stored) {
        var value = mapper.readValue(stored.directiveJson(), ReleaseBundleActivationDirective.class);
        Instant at = Instant.parse(stored.appliedAt());
        if (!value.directiveId().equals(stored.directiveId()) || value.activationSequence() != stored.activationSequence()
                || !key.campaignId().equals(value.bundle().campaignId()) || !key.environment().equals(value.bundle().environment())
                || !key.cell().equals(value.bundle().cell())) throw ReleaseBundleProtocolError.ACTIVATION_STORED_INVALID.conflict();
        verifier.requireValid(key.tenantId(), value, at);
        requireScope(scope, value);
        return new Applied(APPLIED, value, at, current(key));
    }

    private long current(Key key) { return Objects.requireNonNullElse(applications.current(key), 0L); }

    private DecisionDeploymentInventory inventory(Slot slot) {
        var row = applications.inventory(slot);
        if (row == null) throw ReleaseBundleProtocolError.EXECUTION_COVERAGE_INCOMPLETE.conflict();
        var inventory = DecisionInventoryJson.read(row.document().getBytes(StandardCharsets.UTF_8));
        trust.requireInventory(slot, row, inventory, clock.instant());
        return inventory;
    }

    private ReleaseBundleActivationDirective source(TenantScope scope, BundleDirectiveRepository.Key key, long sequence) {
        var row = directives.history(key, sequence).orElseThrow(ReleaseBundleProtocolError.ACTIVATION_NOT_RECORDED::notFound);
        var source = sources.read(key, row);
        verifier.requireValid(key.tenantId(), source, clock.instant());
        requireScope(scope, source);
        return source;
    }

    private void requireLatest(BundleDirectiveRepository.Key key, long sequence) {
        Long latest = directives.lockHead(key, format(clock.instant()));
        if (latest == null || latest != sequence) throw ReleaseBundleProtocolError.ACTIVATION_SEQUENCE_STALE.conflict();
    }

    private Key key(TenantScope scope, String campaign, long sequence) {
        if (campaign == null || !campaign.matches("[A-Za-z0-9_.:-]{1,64}") || sequence < 1)
            throw ReleaseBundleProtocolError.ACTIVATION_REQUEST_INVALID.invalid();
        return new Key(scope.tenantId().value(), local.environment(), local.cell(), campaign);
    }

    private static BundleDirectiveRepository.Key directiveKey(Key key, String namespace) {
        return new BundleDirectiveRepository.Key(key.tenantId(), key.environment(), key.cell(), namespace, key.campaignId());
    }

    private static TenantScope authenticated() {
        var scope = TenantContextHolder.requireCurrent(); scope.requirePermission(DecisionBundleDirectiveService.RECEIVE_PERMISSION); return scope;
    }

    private static void requireScope(TenantScope scope, ReleaseBundleActivationDirective source) {
        scope.requireOrganization(source.organizationId()); if (!source.shopId().isEmpty()) scope.requireShop(source.shopId());
    }
}
