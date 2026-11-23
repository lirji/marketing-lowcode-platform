package com.acme.marketing.control.application;

import static com.acme.marketing.platform.time.SqlTime.format;

import com.acme.marketing.contracts.release.ReleaseBundleActivationDirective;
import com.acme.marketing.contracts.release.ReleaseBundleActivationDirective.Action;
import com.acme.marketing.contracts.release.ReleaseBundleActivationSigner;
import com.acme.marketing.contracts.release.ReleaseBundleManifest;
import com.acme.marketing.contracts.release.PinnedReleaseBundleActivationVerifier;
import com.acme.marketing.control.application.BundleOperationStore.Id;
import com.acme.marketing.control.application.BundleOperationStore.Position;
import com.acme.marketing.control.application.BundleOperationStore.Row;
import com.acme.marketing.platform.crypto.SigningKeyRing;
import com.acme.marketing.platform.crypto.Digests;
import com.acme.marketing.platform.error.DomainException;
import com.acme.marketing.platform.identity.TenantScope;
import com.acme.marketing.platform.web.TenantContextHolder;
import java.time.Clock;
import java.time.Instant;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** 将永久命令、签名授权及已确认的实际应用分别落盘；进程中断可从任一步重放。 */
@Service
public class BundleReleaseOperations {
    private static final String READ = "release:read";
    private static final String ACTIVATE = "release:activate";
    private static final String ROLLBACK = "release:rollback";
    private static final String REQUEST_AUDIT = "RELEASE_BUNDLE_REQUESTED";
    private final ControlRepository control;
    private final BundleOperationStore store;
    private final ReleaseBundleService bundles;
    private final ReleaseBundleReadinessService readiness;
    private final ReleaseApplicationService releases;
    private final BundleReleaseRuntime runtime;
    private final SigningKeyRing keys;
    private final ObjectMapper json;
    private final Clock clock;
    private final Duration manualWindow;
    private final TransactionTemplate transaction;

    /** 只在本库序号分配、签名落盘及结果更新时开启短事务。 */
    public BundleReleaseOperations(ControlRepository control, BundleOperationStore store, ReleaseBundleService bundles,
            ReleaseBundleReadinessService readiness, ReleaseApplicationService releases, BundleReleaseRuntime runtime,
            SigningKeyRing keys, ObjectMapper json, Clock clock, PlatformTransactionManager manager,
            @org.springframework.beans.factory.annotation.Value("${marketing.bundle-runtime.operation-timeout:PT10M}") Duration manualWindow) {
        if (manualWindow.isZero() || manualWindow.isNegative() || manualWindow.compareTo(Duration.ofDays(1)) > 0)
            throw ReleaseBundleError.RUNTIME_CONFIG.invalid();
        this.manualWindow = manualWindow;
        this.control = control; this.store = store; this.bundles = bundles; this.readiness = readiness;
        this.releases = releases; this.runtime = runtime; this.keys = keys; this.json = json; this.clock = clock;
        transaction = new TransactionTemplate(manager);
        transaction.setTimeout(10);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    /** 操作完成前始终复用同一业务编号，包括进程重启及HTTP响应丢失。 */
    public View start(String campaignId, String key, Request request) {
        return start(campaignId, key, request, null);
    }

    /** 定时发布的截止时刻在首次提交后冻结，迟到请求仅允许查证原结果。 */
    public View start(String campaignId, String key, Request request, Instant applyBefore) {
        if (request == null || key == null || !key.matches("[A-Za-z0-9_.:-]{8,128}"))
            throw ReleaseBundleError.OPERATION_INVALID.invalid();
        var scope = authorize(campaignId, permission(request.action()));
        String commandKey = applyBefore == null ? key : scheduledKey(key);
        Row row;
        try {
            row = transaction.execute(status -> reserve(scope, campaignId, commandKey, request, applyBefore));
        } catch (org.springframework.dao.DuplicateKeyException raced) {
            // 不同活动锁互不冲突；相同租户跨活动误用同一键仍由唯一索引裁决。
            if (store.byKey(new Id(scope.tenantId().value(), commandKey)) != null) throw ReleaseBundleError.OPERATION_KEY_CONFLICT.conflict();
            throw raced;
        }
        return advance(row, true);
    }

    /** 操作恢复重新检查当前调用人对该发布/回滚动作的授权。 */
    public View retry(String campaignId, String operationId) {
        var scope = TenantContextHolder.requireCurrent();
        if (!scope.permits(ROLLBACK)) scope.requirePermission(ACTIVATE);
        Row row = requireOperation(campaignId, operationId);
        authorize(campaignId, permission(row.action()));
        return advance(row, true);
    }

    /** 纯查证可修复上次已提交但控制面未收到的运行回执，绝不重发应用。 */
    public View get(String campaignId, String operationId) {
        authorize(campaignId, READ);
        return advance(requireOperation(campaignId, operationId), false);
    }

    /** 调度器恢复未知命令时只核实结果，原键不存在时返回空。 */
    public Optional<View> recoverByKey(String campaignId, String key) {
        var scope = authorize(campaignId, READ);
        Row row = store.byKey(new Id(scope.tenantId().value(), scheduledKey(key)));
        return row == null || !row.campaignId().equals(campaignId) ? Optional.empty() : Optional.of(advance(row, false));
    }

    /** 在同一数据库快照中返回最后已确认的位置及最新操作，适合控制台重开后恢复。 */
    public Summary summary(String campaignId, String environment, String cell, Long version) {
        var scope = authorize(campaignId, READ);
        if (environment == null || environment.isBlank() || environment.length() > 32
                || cell == null || cell.isBlank() || cell.length() > 64 || (version != null && version < 1))
            throw ReleaseBundleError.OPERATION_INVALID.invalid();
        var position = new Position(scope.tenantId().value(), campaignId, environment, cell);
        Row latest = store.latest(position);
        if (latest != null && pending(latest)) advance(latest, false);
        return transaction.execute(status -> {
            ControlCampaignScope.require(control, scope, campaignId, true);
            Row applied = store.applied(position);
            Row last = store.latest(position);
            boolean eligible = version != null && applied != null && version < applied.version()
                    && control.findReleaseBundle(scope.tenantId().value(), campaignId, version)
                    .filter(b -> environment.equals(b.environmentName()) && cell.equals(b.cellId()))
                    .filter(b -> json.readValue(b.contentJson(), ReleaseBundleService.BundleView.class).members().stream()
                            .allMatch(m -> clock.instant().isBefore(m.expiresAt()) && !store.killed(new Id(scope.tenantId().value(), m.namespace()))))
                    .map(b -> store.previouslyApplied(new Id(scope.tenantId().value(), b.bundleId()))).orElse(false);
            return new Summary(applied == null ? 0 : applied.activationSequence(), applied == null ? null : applied.version(),
                    last == null ? null : view(last), eligible);
        });
    }

    /** 主动准备只触达服务端配置的运行实例；每个返回证据重新经过原有签名门禁。 */
    public ReleaseBundleReadinessService.BundleReadinessView prepare(String campaignId, long version) {
        authorize(campaignId, ACTIVATE);
        return prepare(bundles.manifestForOperation(campaignId, version));
    }

    private ReleaseBundleReadinessService.BundleReadinessView prepare(ReleaseBundleManifest bundle) {
        for (var ack : runtime.prepare(bundle)) readiness.collect(bundle.campaignId(), bundle.version(), ack);
        return readiness.evaluateOperation(bundle.campaignId(), bundle.version());
    }

    private Row reserve(TenantScope scope, String campaign, String key, Request request, Instant deadline) {
        String tenant = scope.tenantId().value();
        ControlCampaignScope.require(control, scope, campaign, true);
        var keyId = new Id(tenant, key);
        Row duplicate = store.byKey(keyId);
        String requested = deadline == null ? null : format(deadline);
        if (duplicate != null) {
            if (!campaign.equals(duplicate.campaignId()) || request.version() != duplicate.version()
                    || !request.action().equals(duplicate.action()) || request.expectedAppliedSequence() != duplicate.expectedSequence()
                    || (deadline == null && !scope.actorId().equals(duplicate.createdBy()))
                    || !Objects.equals(requested, duplicate.requestedDeadline()))
                throw ReleaseBundleError.OPERATION_KEY_CONFLICT.conflict();
            return duplicate;
        }
        var bundle = bundles.manifestForOperation(campaign, request.version());
        if (!campaign.equals(bundle.campaignId())) throw ReleaseBundleError.OPERATION_INVALID.invalid();
        var position = new Position(tenant, campaign, bundle.environment(), bundle.cell());
        Row applied = store.applied(position), latest = store.latest(position);
        if ((latest != null && pending(latest)) || request.expectedAppliedSequence() != (applied == null ? 0 : applied.activationSequence()))
            throw ReleaseBundleError.OPERATION_CONFLICT.conflict();
        if (request.action().equals(Action.ROLLBACK.code())) {
            if (applied == null || request.version() >= applied.version() || !store.previouslyApplied(new Id(tenant, bundle.bundleId())))
                throw ReleaseBundleError.OPERATION_TARGET_INVALID.conflict();
        } else if (applied != null && request.version() <= applied.version()) {
            throw ReleaseBundleError.OPERATION_TARGET_INVALID.conflict();
        }
        Instant now = clock.instant();
        Instant cutoff = deadline == null ? now.plus(manualWindow) : deadline;
        Instant end = cutoff.isAfter(bundle.expiresAt()) ? bundle.expiresAt() : cutoff;
        if (!now.isBefore(end)) throw ReleaseBundleError.OPERATION_EXPIRED.conflict();
        Row row = new Row(tenant, UUID.randomUUID().toString(), campaign, bundle.environment(), bundle.cell(),
                bundle.bundleId(), bundle.version(), request.action(), request.expectedAppliedSequence(),
                latest == null ? 1 : Math.incrementExact(latest.activationSequence()), key, requested, format(end), scope.actorId(),
                State.PENDING.code, null, null, format(now), format(now));
        store.insert(row);
        ControlAuditChain.append(control, tenant, scope.actorId(), REQUEST_AUDIT, row.operationId(), now);
        return row;
    }

    private View advance(Row row, boolean execute) {
        if (!pending(row)) return view(row);
        ReleaseBundleActivationDirective directive = directive(row);
        try {
            // 每次先查询原提交，即使本地审批/信任已变或截止时刻已过，也能还原实际结果。
            Instant deadline = Instant.parse(row.applyBefore());
            boolean expiredAtReadStart = !clock.instant().isBefore(deadline);
            if (directive != null) {
                var applied = runtime.findApplied(directive, expiredAtReadStart ? deadline : null);
                if (applied.isPresent()) return applied(row, directive, applied.get());
            }
            if (!clock.instant().isBefore(deadline)) {
                if (directive != null && !expiredAtReadStart) {
                    var finalResult = runtime.findApplied(directive, deadline);
                    if (finalResult.isPresent()) return applied(row, directive, finalResult.get());
                }
                // 未签名的过期命令可能正与另一请求竞争签发；锁内重读，发现已签则由下一次精确查询裁决。
                return transition(row, State.EXPIRED, ReleaseBundleError.OPERATION_EXPIRED.code(), directive == null);
            }
            if (!execute) return view(refresh(row));
            var bundle = bundles.manifestForOperation(row.campaignId(), row.version());
            if (!prepare(bundle).runtimeReady()) throw ReleaseBundleError.OPERATION_NOT_READY.conflict();
            var authorized = transaction.execute(s -> sign(row, bundle));
            // 读到终态说明其他请求已完成；网络调用不持有控制库锁。
            if (!pending(authorized)) return view(authorized);
            var signed = directive(authorized);
            var result = runtime.apply(signed, row.expectedSequence(), deadline, () -> {
                var scope = TenantContextHolder.requireCurrent();
                signed.bundle().members().forEach(member -> releases.assertBundleBenefitClosure(scope, member));
                gate(row, signed.bundle());
            });
            return applied(row, signed, result);
        } catch (DomainException unavailable) {
            // 下游超时、机器身份或证据不全保留原意图；不可凭某次重试错误覆盖已经提交的事实。
            String code = unavailable.code().matches("[A-Z][A-Z0-9_]{1,94}") ? unavailable.code() : ReleaseBundleError.RUNTIME_UNAVAILABLE.code();
            boolean rejected = directive == null && (code.equals(ReleaseBundleError.OPERATION_KILLED.code())
                    || code.equals(ReleaseBundleError.MEMBER_UNAVAILABLE.code()));
            return transition(row, rejected ? State.REJECTED : State.PENDING, code, rejected);
        }
    }

    private Row sign(Row row, ReleaseBundleManifest bundle) {
        Row current = locked(row);
        if (!pending(current)) return current;
        gate(row, bundle);
        if (current.directiveJson() == null) {
            var owner = control.findCampaignOwnership(row.tenantId(), row.campaignId(), false).orElseThrow();
            var unsigned = new ReleaseBundleActivationDirective(row.operationId(), bundle, row.activationSequence(),
                    row.action(), owner.organizationId(), owner.shopId() == null ? "" : owner.shopId(), clock.instant(),
                    row.createdBy(), keys.activeKeyId(), "");
            var signed = ReleaseBundleActivationSigner.sign(keys.activePrivateKey(), unsigned);
            if (store.sign(new BundleOperationStore.Sign(row.tenantId(), row.operationId(),
                    json.writeValueAsString(signed), format(clock.instant()))) != 1)
                throw ReleaseBundleError.OPERATION_CONFLICT.conflict();
        }
        return refresh(row);
    }

    private void gate(Row row, ReleaseBundleManifest bundle) {
        var scope = authorize(row.campaignId(), permission(row.action()));
        Instant now = clock.instant();
        if (!now.isBefore(Instant.parse(row.applyBefore()))) throw ReleaseBundleError.OPERATION_EXPIRED.conflict();
        if (!readiness.evaluateOperation(row.campaignId(), row.version()).runtimeReady())
            throw ReleaseBundleError.OPERATION_NOT_READY.conflict();
        for (var member : bundle.members()) {
            var stored = control.findManifest(row.tenantId(), member.manifestId(), false)
                    .orElseThrow(ReleaseBundleError.MEMBER_UNAVAILABLE::conflict);
            if (member.activationAt().isAfter(now) || !member.equals(json.readValue(stored.manifestJson(), member.getClass()))
                    || control.findReleaseBundleOwner(row.tenantId(), member.manifestId(), false).filter(row.bundleId()::equals).isEmpty())
                throw ReleaseBundleError.MEMBER_UNAVAILABLE.conflict();
            bundles.validateMember(scope, row.campaignId(), member.manifestId(), stored.stateName(), member, now);
            try {
                releases.requireExecutableOfferSources(row.tenantId(), member);
            } catch (IllegalArgumentException invalidSource) {
                throw ReleaseBundleError.MEMBER_UNAVAILABLE.conflict();
            }
            if (store.killed(new Id(row.tenantId(), member.namespace()))) throw ReleaseBundleError.OPERATION_KILLED.conflict();
        }
    }

    private View applied(Row row, ReleaseBundleActivationDirective directive, BundleReleaseRuntime.Applied result) {
        if (result == null || !State.APPLIED.code.equals(result.state()) || !directive.equals(result.directive())
                || result.appliedAt() == null || result.appliedAt().isBefore(directive.authorizedAt())
                || !result.appliedAt().isBefore(Instant.parse(row.applyBefore())) || result.currentSequence() < row.activationSequence())
            throw ReleaseBundleError.RUNTIME_EVIDENCE.conflict();
        return transition(row, State.APPLIED, null, false);
    }

    private View transition(Row row, State state, String reason, boolean unsignedOnly) {
        return transaction.execute(status -> {
            Row current = locked(row);
            if (!pending(current) || (unsignedOnly && current.directiveJson() != null)
                    || (current.state().equals(state.code) && Objects.equals(current.reasonCode(), reason))) return view(current);
            Instant now = clock.instant();
            if (store.update(new BundleOperationStore.Change(row.tenantId(), row.operationId(), state.code,
                    reason, format(now))) != 1) throw ReleaseBundleError.OPERATION_CONFLICT.conflict();
            if (state != State.PENDING) ControlAuditChain.append(control, row.tenantId(),
                    TenantContextHolder.requireCurrent().actorId(), "RELEASE_BUNDLE_" + state.code, row.operationId(), now);
            return view(refresh(row));
        });
    }

    private Row locked(Row row) {
        ControlCampaignScope.require(control, TenantContextHolder.requireCurrent(), row.campaignId(), true);
        return refresh(row);
    }
    private Row refresh(Row row) { return store.operation(new Id(row.tenantId(), row.operationId())); }
    private ReleaseBundleActivationDirective directive(Row row) {
        if (row.directiveJson() == null) return null;
        var source = json.readValue(row.directiveJson(), ReleaseBundleActivationDirective.class);
        var bundle = source.bundle();
        if (!row.operationId().equals(source.directiveId()) || row.activationSequence() != source.activationSequence()
                || !row.action().equals(source.action()) || !row.createdBy().equals(source.authorizedBy())
                || !row.bundleId().equals(bundle.bundleId()) || !row.campaignId().equals(bundle.campaignId())
                || !row.cell().equals(bundle.cell()) || !row.environment().equals(bundle.environment()) || row.version() != bundle.version())
            throw ReleaseBundleError.CONTENT_INVALID.conflict();
        new PinnedReleaseBundleActivationVerifier(keys.publicKeys()).requireValid(row.tenantId(), source, source.authorizedAt());
        return source;
    }
    private Row requireOperation(String campaign, String id) {
        var scope = TenantContextHolder.requireCurrent();
        ControlCampaignScope.require(control, scope, campaign, false);
        Row row = store.operation(new Id(scope.tenantId().value(), id));
        if (row == null || !campaign.equals(row.campaignId())) throw ReleaseBundleError.OPERATION_NOT_FOUND.notFound();
        return row;
    }
    private TenantScope authorize(String campaign, String permission) {
        var scope = TenantContextHolder.requireCurrent();
        scope.requirePermission(permission);
        ControlCampaignScope.require(control, scope, campaign, false);
        return scope;
    }
    /** 感叹号不在公开键字符集内，租户内的定时恢复永远无法匹配手工键。 */
    private static String scheduledKey(String key) { return "!" + Digests.sha256Hex(key); }
    private static String permission(String action) { return Action.ROLLBACK.code().equals(action) ? ROLLBACK : ACTIVATE; }
    private static boolean pending(Row row) { return State.PENDING.code.equals(row.state()); }
    private static View view(Row row) {
        return new View(row.operationId(), row.campaignId(), row.version(), row.action(), row.activationSequence(),
                row.state(), row.reasonCode(), Instant.parse(row.createdAt()), Instant.parse(row.updatedAt()));
    }
    private enum State {
        PENDING("PENDING"), APPLIED("APPLIED"), EXPIRED("EXPIRED"), REJECTED("REJECTED");
        private final String code;
        State(String code) { this.code = code; }
    }
    /** UI提交观察到的实际游标；回滚也创建新的递增授权。 */
    public record Request(long version, String action, long expectedAppliedSequence) {
        public Request {
            if (version <= 0 || expectedAppliedSequence < 0 || (!Action.ACTIVATE.code().equals(action) && !Action.ROLLBACK.code().equals(action)))
                throw ReleaseBundleError.OPERATION_INVALID.invalid();
        }
    }
    /** 控制台只把来自运行端已持久提交的结果展示为 APPLIED。 */
    public record View(String operationId, String campaignId, long version, String action, long activationSequence,
            String state, String reasonCode, Instant createdAt, Instant updatedAt) {}
    public record Summary(long appliedSequence, Long appliedVersion, View latestOperation, boolean rollbackEligible) {}
}
