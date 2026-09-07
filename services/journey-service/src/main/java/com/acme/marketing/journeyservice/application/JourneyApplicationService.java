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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Value;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class JourneyApplicationService {
    private final JourneyRepository repository;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final boolean directRegistrationEnabled;
    private final ExecutionMode executionMode;
    private final JourneyKillSwitchRegistry killSwitch;
    private final JourneyRuntime runtime = new JourneyRuntime();

    public JourneyApplicationService(JourneyRepository repository, ObjectMapper mapper, Clock clock,
            JourneyKillSwitchRegistry killSwitch,
            @Value("${marketing.security.mode:DEV}") String securityMode,
            @Value("${marketing.journey.execution-mode:DIRECT}") ExecutionMode executionMode) {
        this.repository = repository;
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
        repository.saveDefinition(new JourneyRepository.DefinitionWrite(scope.tenantId().value(), plan.journeyId(),
                plan.version(), json(plan), "ACTIVE", scope.actorId(), format(clock.instant())));
        return plan;
    }

    @Transactional
    public EnrollmentView enroll(EnrollRequest request) {
        var scope = TenantContextHolder.requireCurrent();
        scope.requirePermission("journey:enroll");
        requireDirectStateWriter();
        killSwitch.requireEnabled(scope.tenantId().value());
        JourneyPlan plan = plan(scope.tenantId().value(), request.journeyId(), request.journeyVersion(), true);
        var duplicate = repository.findDuplicateEnrollment(scope.tenantId().value(), request.journeyId(),
                request.journeyVersion(), request.subjectToken(), request.triggerEventId());
        if (duplicate.isPresent()) {
            var row = duplicate.orElseThrow();
            return view(read(row.snapshotJson(), EnrollmentSnapshot.class), List.of(), Map.of(), true,
                    Instant.parse(row.createdAt()));
        }
        Instant now = clock.instant();
        String enrollmentId = UUID.randomUUID().toString();
        EnrollmentSnapshot start = EnrollmentSnapshot.start(scope.tenantId().value(), enrollmentId,
                request.subjectToken(), plan, now);
        JourneyTransition transition = runtime.advance(plan, start,
                new JourneySignal.Start(request.triggerEventId(), request.occurredAt()));
        boolean saved = repository.trySaveEnrollment(new JourneyRepository.EnrollmentWrite(
                scope.tenantId().value(), enrollmentId, plan.journeyId(), plan.version(), request.subjectToken(),
                request.triggerEventId(), transition.snapshot().status().name(), transition.snapshot().currentNodeId(),
                json(transition.snapshot()), "", -1, -1L, format(now), format(now)));
        if (!saved) {
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
            repository.updateEnrollmentState(new JourneyRepository.EnrollmentStateWrite(
                    scope.tenantId().value(), enrollmentId, transition.snapshot().status().name(),
                    transition.snapshot().currentNodeId(), json(transition.snapshot()), format(clock.instant())));
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
        List<EnrollmentSnapshot> snapshots = repository.findMigrationSnapshots(scope.tenantId().value(), journeyId,
                        request.fromVersion(), request.afterEnrollmentId(), request.batchSize(), !request.dryRun())
                .stream().map(value -> read(value, EnrollmentSnapshot.class)).toList();
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
                repository.saveMigration(new JourneyRepository.MigrationWrite(scope.tenantId().value(),
                        UUID.randomUUID().toString(), snapshot.enrollmentId(), request.fromVersion(),
                        request.toVersion(), json(snapshot), "APPLIED", scope.actorId(), format(now)));
                repository.updateEnrollmentMigration(new JourneyRepository.EnrollmentMigrationWrite(
                        scope.tenantId().value(), snapshot.enrollmentId(), target.version(), mapped,
                        json(migrated), format(now)));
            }
        }
        int remaining = repository.countActiveEnrollments(
                scope.tenantId().value(), journeyId, request.fromVersion());
        String nextCursor = snapshots.isEmpty() && remaining > 0 ? ""
                : snapshots.isEmpty() ? request.afterEnrollmentId() : snapshots.getLast().enrollmentId();
        if (!request.dryRun() && compatible && remaining == 0) {
            repository.markDefinitionMigrated(scope.tenantId().value(), journeyId, request.fromVersion());
        }
        return new MigrationReport(journeyId, request.fromVersion(), request.toVersion(), request.dryRun(),
                compatible, snapshots.size(), nextCursor, remaining > 0, issues, clock.instant());
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
        String statusName = null;
        if (status != null && !status.isBlank()) {
            EnrollmentSnapshot.Status parsed;
            try {
                parsed = EnrollmentSnapshot.Status.valueOf(status.toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException invalid) {
                throw new IllegalArgumentException("enrollment status is invalid", invalid);
            }
            statusName = parsed.name();
        }
        return repository.findEnrollments(scope.tenantId().value(), statusName, journeyId, limit).stream()
                .map(row -> view(read(row.snapshotJson(), EnrollmentSnapshot.class), List.of(), Map.of(), false,
                        Instant.parse(row.updatedAt())))
                .toList();
    }

    private JourneyPlan plan(String tenantId, String journeyId, long version) {
        return plan(tenantId, journeyId, version, false);
    }

    private JourneyPlan plan(String tenantId, String journeyId, long version, boolean lock) {
        String encoded = repository.findActivePlanJson(tenantId, journeyId, version, lock)
                .orElseThrow(() -> new NotFoundException("JOURNEY_VERSION_NOT_FOUND", "journey version not found"));
        return read(encoded, JourneyPlan.class);
    }

    private void freezeSourceVersion(String tenantId, String journeyId, long version) {
        String state = repository.findDefinitionStateForUpdate(tenantId, journeyId, version)
                .orElseThrow(() -> new NotFoundException("JOURNEY_VERSION_NOT_FOUND",
                        "source journey version not found"));
        if (!"ACTIVE".equals(state) && !"MIGRATING".equals(state)) {
            throw new ConflictException("JOURNEY_VERSION_NOT_MIGRATABLE", "source journey version is not migratable");
        }
        if ("ACTIVE".equals(state)) {
            repository.markDefinitionMigrating(tenantId, journeyId, version);
        }
    }

    private EnrollmentSnapshot lockEnrollment(String tenantId, String enrollmentId) {
        return enrollment(tenantId, enrollmentId, true);
    }

    private EnrollmentSnapshot enrollment(String tenantId, String enrollmentId, boolean lock) {
        String encoded = repository.findEnrollmentSnapshot(tenantId, enrollmentId, lock)
                .orElseThrow(() -> new NotFoundException("ENROLLMENT_NOT_FOUND", "journey enrollment not found"));
        return read(encoded, EnrollmentSnapshot.class);
    }

    private void persistEffects(String tenantId, String enrollmentId, JourneyTransition transition, Instant now) {
        for (JourneyCommand command : transition.commands()) {
            repository.saveEffectIntent(new JourneyRepository.EffectWrite(tenantId, command.commandId(),
                    enrollmentId, command.nodeId(), command.type().name(), json(command.payload()),
                    "PENDING", format(now)));
        }
        transition.timers().forEach((timerKey, fireAt) -> {
            JourneyRepository.TimerWrite write = new JourneyRepository.TimerWrite(tenantId, timerKey,
                    enrollmentId, format(fireAt), "SCHEDULED", format(now));
            int updated = repository.updateTimer(write);
            if (updated == 0) {
                repository.saveTimer(write);
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
