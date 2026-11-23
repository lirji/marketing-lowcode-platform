package com.acme.marketing.control.application;

import static com.acme.marketing.platform.time.SqlTime.format;

import com.acme.marketing.control.application.BundleScheduleStore.Row;
import com.acme.marketing.platform.error.ConflictException;
import com.acme.marketing.platform.error.DomainException;
import com.acme.marketing.platform.error.ForbiddenException;
import com.acme.marketing.platform.identity.TenantScope;
import com.acme.marketing.platform.web.TenantContextHolder;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** 持久化定时意图。人工修改和任务接管都在数据库按代次竞争。 */
@Service
public class BundleSchedules {
    static final String ACTIVATE = "release:activate";
    static final String EXPIRED_REASON = "RELEASE_SCHEDULE_WINDOW_EXPIRED";
    private static final int TX_TIMEOUT_SECONDS = 10;
    private static final Duration CLAIM_LEASE = Duration.ofSeconds(60);
    private final BundleScheduleStore store;
    private final ControlRepository repository;
    private final ObjectMapper json;
    private final Clock clock;
    private final Duration lateness;

    public BundleSchedules(BundleScheduleStore store, ControlRepository repository, ObjectMapper json, Clock clock,
            @Value("${marketing.release-schedule.max-lateness-seconds:300}") long seconds) {
        if (seconds < 1 || seconds > 3600) throw new IllegalArgumentException("发布调度容许延迟应为 1 至 3600 秒");
        this.store = store; this.repository = repository; this.json = json; this.clock = clock;
        this.lateness = Duration.ofSeconds(seconds);
    }

    /** 同一创建键可以在客户端超时甚至排期已过期后恢复原计划。 */
    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = TX_TIMEOUT_SECONDS)
    public View create(String campaignId, String key, Request request) {
        TenantScope scope = authorized(campaignId, ACTIVATE, true);
        if (request == null || request.version() < 1 || request.expectedAppliedSequence() < 0
                || request.dueAt() == null || key == null || !key.matches("[A-Za-z0-9_.:-]{8,128}"))
            throw invalid();
        String tenant = scope.tenantId().value();
        Row previous = store.byCommand(tenant, campaignId, key);
        if (previous != null) {
            if (previous.version() != request.version() || previous.expectedAppliedSequence() != request.expectedAppliedSequence()
                    || !previous.dueAt().equals(format(request.dueAt())) || !previous.createdBy().equals(scope.actorId()))
                throw ReleaseBundleError.SCHEDULE_COMMAND_CONFLICT.conflict();
            return view(previous);
        }
        var stored = repository.findReleaseBundle(tenant, campaignId, request.version())
                .orElseThrow(ReleaseBundleError.NOT_FOUND::notFound);
        var bundle = json.readValue(stored.contentJson(), ReleaseBundleService.BundleView.class);
        Instant expiresAt = bundle.members().stream().map(m -> m.expiresAt()).min(Instant::compareTo)
                .orElseThrow(ReleaseBundleError.MEMBER_UNAVAILABLE::conflict);
        Instant now = clock.instant();
        if (!request.dueAt().isAfter(now) || !request.dueAt().isBefore(expiresAt)) throw invalid();
        Instant cutoff = request.dueAt().plus(lateness);
        String due = format(request.dueAt());
        Row row = new Row(tenant, campaignId, UUID.randomUUID().toString(), key, request.version(),
                request.expectedAppliedSequence(), due, format(cutoff.isBefore(expiresAt) ? cutoff : expiresAt),
                State.SCHEDULED.code, 1, due, null, null, scope.actorId(), format(now), format(now));
        store.insert(row);
        audit(row, State.SCHEDULED);
        return view(row);
    }

    /** 最新二十条按排期创建时间返回，包含暂停及终态。 */
    public List<View> recent(String campaignId, long version) {
        var scope = authorized(campaignId, "release:read", false);
        if (version < 1) throw invalid();
        return store.recent(scope.tenantId().value(), campaignId, version).stream().map(BundleSchedules::view).toList();
    }

    /** 领取之后不能暂停。过期计划不能靠恢复获得新的生效窗口。 */
    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = TX_TIMEOUT_SECONDS)
    public View change(String campaignId, String scheduleId, long revision, boolean resume) {
        var scope = authorized(campaignId, ACTIVATE, true);
        Row row = store.byId(scope.tenantId().value(), campaignId, scheduleId);
        if (row == null) throw ReleaseBundleError.SCHEDULE_MISSING.notFound();
        if (row.revision() != revision || !(resume ? State.PAUSED : State.SCHEDULED).is(row.state())) throw stale();
        State next = resume ? State.SCHEDULED : State.PAUSED;
        if (!clock.instant().isBefore(Instant.parse(row.applyBefore()))) next = State.EXPIRED;
        update(row, next, row.dueAt(), null, next == State.EXPIRED ? EXPIRED_REASON : null);
        audit(row, next);
        return view(store.byId(row.tenant(), campaignId, scheduleId));
    }

    /** 单个短事务领取；在事务提交后才允许进行跨服务调用。 */
    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = TX_TIMEOUT_SECONDS)
    public Row claim() {
        TenantScope scope = TenantContextHolder.requireCurrent();
        scope.requirePermission("release:schedule:execute");
        scope.requirePermission("release:read");
        scope.requirePermission(ACTIVATE);
        if (scope.organizations().isEmpty()) return null;
        Instant now = clock.instant();
        Row row = store.claimable(scope.tenantId().value(), scope.organizations(), scope.shops(), format(now));
        if (row == null) return null;
        update(row, State.RUNNING, format(now.plus(CLAIM_LEASE)), row.operationId(), row.reasonCode());
        return store.byId(row.tenant(), row.campaignId(), row.scheduleId());
    }

    /** 仅本代执行者可以写回；迟到结果由持有新租约的执行者按原键查询。 */
    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = TX_TIMEOUT_SECONDS)
    public boolean finish(Row row, State state, String operationId, String reason) {
        var scope = authorized(row.campaignId(), ACTIVATE, false);
        if (!row.tenant().equals(scope.tenantId().value())) throw new ForbiddenException(ReleaseBundleError.SCHEDULE_INVALID.code(), "租户不匹配");
        Instant now = clock.instant();
        // 错开同批未就绪任务的下次取证，避免单个不可用活动占满扫描配额。
        long delay = Math.min(30, 2 + row.revision()) + Math.floorMod(row.scheduleId().hashCode(), 4);
        var change = new BundleScheduleStore.Change(row.tenant(), row.campaignId(), row.scheduleId(), row.revision(),
                state.code, format(now.plusSeconds(delay)), operationId, reason, format(now));
        if (store.advance(change) != 1) return false;
        if (state != State.RUNNING) audit(row, state);
        return true;
    }

    private void update(Row row, State next, String eligibleAt, String operationId, String reason) {
        if (store.advance(new BundleScheduleStore.Change(row.tenant(), row.campaignId(), row.scheduleId(),
                row.revision(), next.code, eligibleAt, operationId, reason, format(clock.instant()))) != 1) throw stale();
    }
    private TenantScope authorized(String campaign, String permission, boolean lock) {
        var scope = TenantContextHolder.requireCurrent();
        scope.requirePermission(permission);
        ControlCampaignScope.require(repository, scope, campaign, lock);
        return scope;
    }
    private void audit(Row row, State state) {
        ControlAuditChain.append(repository, row.tenant(), TenantContextHolder.requireCurrent().actorId(),
                "BUNDLE_SCHEDULE_" + state.code, row.scheduleId(), clock.instant());
    }
    private static View view(Row row) {
        return new View(row.scheduleId(), row.campaignId(), row.version(), row.expectedAppliedSequence(),
                Instant.parse(row.dueAt()), Instant.parse(row.applyBefore()), row.state(), row.revision(), row.operationId(),
                row.reasonCode(), Instant.parse(row.createdAt()), Instant.parse(row.updatedAt()));
    }
    private static ConflictException stale() {
        return ReleaseBundleError.SCHEDULE_CHANGED.conflict();
    }
    private static DomainException invalid() {
        return ReleaseBundleError.SCHEDULE_INVALID.invalid();
    }

    public record Request(@com.fasterxml.jackson.annotation.JsonProperty(required = true) long version,
                          @com.fasterxml.jackson.annotation.JsonProperty(required = true) long expectedAppliedSequence,
                          @com.fasterxml.jackson.annotation.JsonProperty(required = true) Instant dueAt) { }
    public record View(String scheduleId, String campaignId, long version, long expectedAppliedSequence, Instant dueAt,
                       Instant applyBefore, String state, long revision, String operationId, String reasonCode,
                       Instant createdAt, Instant updatedAt) { }
    public enum State {
        SCHEDULED("SCHEDULED"), PAUSED("PAUSED"), RUNNING("RUNNING"), APPLIED("APPLIED"),
        EXPIRED("EXPIRED"), REJECTED("REJECTED");
        private final String code;
        State(String code) { this.code = code; }
        boolean is(String value) { return Objects.equals(code, value); }
    }
}
