package com.acme.marketing.journeyservice.application;

import static com.acme.marketing.platform.time.SqlTime.format;

import com.acme.marketing.journey.EnrollmentSnapshot;
import com.acme.marketing.journey.JourneyCommand;
import com.acme.marketing.journey.JourneyPlan;
import com.acme.marketing.journey.JourneyRuntime;
import com.acme.marketing.journey.JourneySignal;
import com.acme.marketing.journey.JourneyTransition;
import com.acme.marketing.platform.error.ConflictException;
import com.acme.marketing.platform.error.NotFoundException;
import com.acme.marketing.platform.web.TenantContextHolder;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Value;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class JourneyApplicationService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final boolean directRegistrationEnabled;
    private final ExecutionMode executionMode;
    private final JourneyKillSwitchRegistry killSwitch;
    private final JourneyRuntime runtime = new JourneyRuntime();

    public JourneyApplicationService(JdbcTemplate jdbc, ObjectMapper mapper, Clock clock,
            JourneyKillSwitchRegistry killSwitch,
            @Value("${marketing.security.mode:DEV}") String securityMode,
            @Value("${marketing.journey.execution-mode:DIRECT}") ExecutionMode executionMode) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.clock = clock;
        this.killSwitch = killSwitch;
        this.directRegistrationEnabled = !"OIDC".equalsIgnoreCase(securityMode);
        this.executionMode = executionMode;
        if ("OIDC".equalsIgnoreCase(securityMode) && executionMode != ExecutionMode.STREAM) {
            throw new IllegalStateException("OIDC deployments must use STREAM journey execution");
        }
    }

    @Transactional
    public JourneyPlan register(JourneyPlan plan) {
        var scope = TenantContextHolder.requireCurrent();
        scope.requirePermission("journey:write");
        if (!directRegistrationEnabled) {
            throw new ConflictException("DIRECT_JOURNEY_REGISTRATION_DISABLED",
                    "production journey plans must be installed through the signed release runtime");
        }
        jdbc.update("insert into mk_journey_definition(tenant_id,journey_id,version_no,plan_json,state_name,created_by,created_at) values(?,?,?,?,?,?,?)",
                scope.tenantId().value(), plan.journeyId(), plan.version(), json(plan), "ACTIVE", scope.actorId(),
                format(clock.instant()));
        return plan;
    }

    @Transactional
    public EnrollmentView enroll(EnrollRequest request) {
        var scope = TenantContextHolder.requireCurrent();
        scope.requirePermission("journey:enroll");
        requireDirectStateWriter();
        killSwitch.requireEnabled(scope.tenantId().value());
        JourneyPlan plan = plan(scope.tenantId().value(), request.journeyId(), request.journeyVersion(), true);
        List<EnrollmentView> duplicate = jdbc.query("select enrollment_id,snapshot_json,created_at from mk_enrollment where tenant_id=? and journey_id=? and journey_version=? and subject_token=? and trigger_event_id=?",
                (rs, rowNum) -> view(read(rs.getString(2), EnrollmentSnapshot.class), List.of(), Map.of(), true,
                        Instant.parse(rs.getString(3))), scope.tenantId().value(), request.journeyId(),
                request.journeyVersion(), request.subjectToken(), request.triggerEventId());
        if (!duplicate.isEmpty()) return duplicate.getFirst();
        Instant now = clock.instant();
        String enrollmentId = UUID.randomUUID().toString();
        EnrollmentSnapshot start = EnrollmentSnapshot.start(scope.tenantId().value(), enrollmentId,
                request.subjectToken(), plan, now);
        JourneyTransition transition = runtime.advance(plan, start,
                new JourneySignal.Start(request.triggerEventId(), request.occurredAt()));
        try {
            jdbc.update("insert into mk_enrollment(tenant_id,enrollment_id,journey_id,journey_version,subject_token,trigger_event_id,status_name,current_node_id,snapshot_json,created_at,updated_at) values(?,?,?,?,?,?,?,?,?,?,?)",
                    scope.tenantId().value(), enrollmentId, plan.journeyId(), plan.version(), request.subjectToken(),
                    request.triggerEventId(), transition.snapshot().status().name(), transition.snapshot().currentNodeId(),
                    json(transition.snapshot()), format(now), format(now));
        } catch (DuplicateKeyException race) {
            throw new ConflictException("ENROLLMENT_CONCURRENT_DUPLICATE", "trigger is already enrolling");
        }
        persistEffects(scope.tenantId().value(), enrollmentId, transition, now);
        return view(transition.snapshot(), transition.commands(), transition.timers(), false, now);
    }

    @Transactional
    public EnrollmentView signal(String enrollmentId, JourneySignal signal) {
        var scope = TenantContextHolder.requireCurrent();
        scope.requirePermission("journey:signal");
        requireDirectStateWriter();
        killSwitch.requireEnabled(scope.tenantId().value());
        EnrollmentSnapshot snapshot = lockEnrollment(scope.tenantId().value(), enrollmentId);
        JourneyPlan plan = plan(scope.tenantId().value(), snapshot.journeyId(), snapshot.journeyVersion());
        JourneyTransition transition = runtime.advance(plan, snapshot, signal);
        if (!transition.duplicate()) {
            jdbc.update("update mk_enrollment set status_name=?,current_node_id=?,snapshot_json=?,updated_at=? where tenant_id=? and enrollment_id=?",
                    transition.snapshot().status().name(), transition.snapshot().currentNodeId(),
                    json(transition.snapshot()), format(clock.instant()), scope.tenantId().value(), enrollmentId);
            persistEffects(scope.tenantId().value(), enrollmentId, transition, clock.instant());
        }
        return view(transition.snapshot(), transition.commands(), transition.timers(), transition.duplicate(), clock.instant());
    }

    @Transactional
    public MigrationReport migrate(String journeyId, MigrationRequest request) {
        var scope = TenantContextHolder.requireCurrent();
        scope.requirePermission("journey:migrate");
        requireDirectStateWriter();
        JourneyPlan target = plan(scope.tenantId().value(), journeyId, request.toVersion());
        if (!request.dryRun()) freezeSourceVersion(scope.tenantId().value(), journeyId, request.fromVersion());
        String query = "select snapshot_json from mk_enrollment where tenant_id=? and journey_id=? and journey_version=? and status_name in ('RUNNING','WAITING') and enrollment_id>? order by enrollment_id limit ?"
                + (request.dryRun() ? "" : " for update");
        List<EnrollmentSnapshot> snapshots = jdbc.query(query,
                (rs, rowNum) -> read(rs.getString(1), EnrollmentSnapshot.class), scope.tenantId().value(), journeyId,
                request.fromVersion(), request.afterEnrollmentId(), request.batchSize());
        List<MigrationIssue> issues = new ArrayList<>();
        for (EnrollmentSnapshot snapshot : snapshots) {
            String mapped = request.nodeMapping().getOrDefault(snapshot.currentNodeId(), snapshot.currentNodeId());
            if (!target.nodes().containsKey(mapped)) {
                issues.add(new MigrationIssue(snapshot.enrollmentId(), snapshot.currentNodeId(), "TARGET_NODE_MISSING"));
            }
        }
        boolean compatible = issues.isEmpty();
        if (!request.dryRun() && compatible) {
            Instant now = clock.instant();
            for (EnrollmentSnapshot snapshot : snapshots) {
                String mapped = request.nodeMapping().getOrDefault(snapshot.currentNodeId(), snapshot.currentNodeId());
                EnrollmentSnapshot migrated = new EnrollmentSnapshot(snapshot.tenantId(), snapshot.enrollmentId(),
                        snapshot.subjectToken(), snapshot.journeyId(), target.version(), mapped, snapshot.status(),
                        snapshot.variables(), snapshot.processedSignalIds(), snapshot.iterations(),
                        snapshot.nodeExecutions(), now);
                jdbc.update("insert into mk_journey_migration(tenant_id,migration_id,enrollment_id,from_version,to_version,previous_snapshot_json,state_name,created_by,created_at) values(?,?,?,?,?,?,?,?,?)",
                        scope.tenantId().value(), UUID.randomUUID().toString(), snapshot.enrollmentId(),
                        request.fromVersion(), request.toVersion(), json(snapshot), "APPLIED", scope.actorId(), format(now));
                jdbc.update("update mk_enrollment set journey_version=?,current_node_id=?,snapshot_json=?,updated_at=? where tenant_id=? and enrollment_id=?",
                        target.version(), mapped, json(migrated), format(now), scope.tenantId().value(), snapshot.enrollmentId());
            }
        }
        Integer remaining = jdbc.query("select count(*) from mk_enrollment where tenant_id=? and journey_id=? and journey_version=? and status_name in ('RUNNING','WAITING')",
                rs -> rs.next() ? rs.getInt(1) : 0, scope.tenantId().value(), journeyId,
                request.fromVersion());
        String nextCursor = snapshots.isEmpty() && remaining != null && remaining > 0 ? ""
                : snapshots.isEmpty() ? request.afterEnrollmentId() : snapshots.getLast().enrollmentId();
        if (!request.dryRun() && compatible && (remaining == null || remaining == 0)) {
            jdbc.update("update mk_journey_definition set state_name='MIGRATED' where tenant_id=? and journey_id=? and version_no=? and state_name='MIGRATING'",
                    scope.tenantId().value(), journeyId, request.fromVersion());
        }
        return new MigrationReport(journeyId, request.fromVersion(), request.toVersion(), request.dryRun(),
                compatible, snapshots.size(), nextCursor, remaining != null && remaining > 0, issues, clock.instant());
    }

    public EnrollmentView get(String enrollmentId) {
        var scope = TenantContextHolder.requireCurrent();
        scope.requirePermission("journey:read");
        EnrollmentSnapshot snapshot = enrollment(scope.tenantId().value(), enrollmentId, false);
        return view(snapshot, List.of(), Map.of(), false, snapshot.updatedAt());
    }

    public List<EnrollmentView> enrollments(String status, String journeyId, int limit) {
        var scope = TenantContextHolder.requireCurrent();
        scope.requirePermission("journey:read");
        if (limit < 1 || limit > 200) throw new IllegalArgumentException("limit must be in [1,200]");
        StringBuilder sql = new StringBuilder(
                "select snapshot_json,updated_at from mk_enrollment where tenant_id=?");
        List<Object> arguments = new ArrayList<>();
        arguments.add(scope.tenantId().value());
        if (status != null && !status.isBlank()) {
            EnrollmentSnapshot.Status parsed;
            try {
                parsed = EnrollmentSnapshot.Status.valueOf(status.toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException invalid) {
                throw new IllegalArgumentException("enrollment status is invalid", invalid);
            }
            sql.append(" and status_name=?");
            arguments.add(parsed.name());
        }
        if (journeyId != null && !journeyId.isBlank()) {
            sql.append(" and journey_id=?");
            arguments.add(journeyId);
        }
        sql.append(" order by updated_at desc,enrollment_id limit ?");
        arguments.add(limit);
        return jdbc.query(sql.toString(), (rs, rowNum) -> view(
                read(rs.getString(1), EnrollmentSnapshot.class), List.of(), Map.of(), false,
                Instant.parse(rs.getString(2))), arguments.toArray());
    }

    private JourneyPlan plan(String tenantId, String journeyId, long version) {
        return plan(tenantId, journeyId, version, false);
    }

    private JourneyPlan plan(String tenantId, String journeyId, long version, boolean lock) {
        List<JourneyPlan> plans = jdbc.query("select plan_json from mk_journey_definition where tenant_id=? and journey_id=? and version_no=? and state_name='ACTIVE'"
                        + (lock ? " for update" : ""),
                (rs, rowNum) -> read(rs.getString(1), JourneyPlan.class), tenantId, journeyId, version);
        if (plans.isEmpty()) throw new NotFoundException("JOURNEY_VERSION_NOT_FOUND", "journey version not found");
        return plans.getFirst();
    }

    private void freezeSourceVersion(String tenantId, String journeyId, long version) {
        List<String> states = jdbc.query("select state_name from mk_journey_definition where tenant_id=? and journey_id=? and version_no=? for update",
                (rs, rowNum) -> rs.getString(1), tenantId, journeyId, version);
        if (states.isEmpty()) throw new NotFoundException("JOURNEY_VERSION_NOT_FOUND", "source journey version not found");
        String state = states.getFirst();
        if (!"ACTIVE".equals(state) && !"MIGRATING".equals(state)) {
            throw new ConflictException("JOURNEY_VERSION_NOT_MIGRATABLE", "source journey version is not migratable");
        }
        if ("ACTIVE".equals(state)) {
            jdbc.update("update mk_journey_definition set state_name='MIGRATING' where tenant_id=? and journey_id=? and version_no=?",
                    tenantId, journeyId, version);
        }
    }

    private EnrollmentSnapshot lockEnrollment(String tenantId, String enrollmentId) {
        return enrollment(tenantId, enrollmentId, true);
    }

    private EnrollmentSnapshot enrollment(String tenantId, String enrollmentId, boolean lock) {
        List<EnrollmentSnapshot> rows = jdbc.query("select snapshot_json from mk_enrollment where tenant_id=? and enrollment_id=?"
                        + (lock ? " for update" : ""),
                (rs, rowNum) -> read(rs.getString(1), EnrollmentSnapshot.class), tenantId, enrollmentId);
        if (rows.isEmpty()) throw new NotFoundException("ENROLLMENT_NOT_FOUND", "journey enrollment not found");
        return rows.getFirst();
    }

    private void persistEffects(String tenantId, String enrollmentId, JourneyTransition transition, Instant now) {
        for (JourneyCommand command : transition.commands()) {
            jdbc.update("insert into mk_node_effect_intent(tenant_id,command_id,enrollment_id,node_id,effect_type,payload_json,state_name,created_at) values(?,?,?,?,?,?,?,?)",
                    tenantId, command.commandId(), enrollmentId, command.nodeId(), command.type().name(),
                    json(command.payload()), "PENDING", format(now));
        }
        transition.timers().forEach((timerKey, fireAt) -> {
            int updated = jdbc.update("update mk_journey_timer set fire_at=?,state_name='SCHEDULED' where tenant_id=? and timer_key=?",
                    format(fireAt), tenantId, timerKey);
            if (updated == 0) {
                jdbc.update("insert into mk_journey_timer(tenant_id,timer_key,enrollment_id,fire_at,state_name,created_at) values(?,?,?,?,?,?)",
                        tenantId, timerKey, enrollmentId, format(fireAt), "SCHEDULED", format(now));
            }
        });
    }

    private void requireDirectStateWriter() {
        if (executionMode != ExecutionMode.DIRECT) {
            throw new ConflictException("JOURNEY_STREAM_INGRESS_REQUIRED",
                    "production journey state is single-writer; submit JOURNEY_SIGNAL through /api/v1/events");
        }
    }

    private EnrollmentView view(EnrollmentSnapshot snapshot, List<JourneyCommand> commands,
            Map<String, Instant> timers, boolean duplicate, Instant projectedAt) {
        return new EnrollmentView(snapshot.enrollmentId(), snapshot.journeyId(), snapshot.journeyVersion(),
                snapshot.subjectToken(), snapshot.currentNodeId(), snapshot.status(), commands, timers,
                duplicate, projectedAt);
    }

    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (JacksonException failure) { throw new IllegalArgumentException("journey state cannot be serialized", failure); }
    }
    private <T> T read(String value, Class<T> type) {
        try { return mapper.readValue(value, type); }
        catch (JacksonException failure) { throw new IllegalStateException("stored journey state is invalid", failure); }
    }

    public record EnrollRequest(String journeyId, long journeyVersion, String subjectToken,
            String triggerEventId, Instant occurredAt) { }
    public record EnrollmentView(String enrollmentId, String journeyId, long journeyVersion, String subjectToken,
            String currentNodeId, EnrollmentSnapshot.Status status, List<JourneyCommand> commands,
            Map<String, Instant> timers, boolean duplicate, Instant projectedAt) {
        public EnrollmentView { commands = List.copyOf(commands); timers = Map.copyOf(timers); }
    }
    public record MigrationRequest(long fromVersion, long toVersion, boolean dryRun, Map<String, String> nodeMapping,
            String afterEnrollmentId, int batchSize) {
        public MigrationRequest {
            nodeMapping = Map.copyOf(nodeMapping == null ? Map.of() : nodeMapping);
            afterEnrollmentId = afterEnrollmentId == null ? "" : afterEnrollmentId;
            batchSize = batchSize == 0 ? 200 : batchSize;
            if (fromVersion == toVersion) throw new IllegalArgumentException("migration versions must differ");
            if (batchSize < 1 || batchSize > 500) throw new IllegalArgumentException("batchSize must be in [1,500]");
        }
    }
    public record MigrationIssue(String enrollmentId, String currentNodeId, String code) { }
    public record MigrationReport(String journeyId, long fromVersion, long toVersion, boolean dryRun,
            boolean compatible, int enrollmentCount, String nextCursor, boolean hasMore,
            List<MigrationIssue> issues, Instant checkedAt) {
        public MigrationReport { issues = List.copyOf(issues); }
    }

    public enum ExecutionMode { DIRECT, STREAM }
}
