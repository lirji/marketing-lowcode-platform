package com.acme.marketing.measurement.application;

import static com.acme.marketing.platform.time.SqlTime.format;

import com.acme.marketing.contracts.event.MarketingFact;
import com.acme.marketing.contracts.event.MeasurementProjectionDelta;
import com.acme.marketing.decision.experiment.ExperimentAssigner;
import com.acme.marketing.platform.crypto.Digests;
import com.acme.marketing.platform.error.ConflictException;
import com.acme.marketing.platform.error.NotFoundException;
import com.acme.marketing.platform.web.TenantContextHolder;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class MeasurementService {
    private final MeasurementRepository repository;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final MeasurementProjectionStore projection;
    private final ExperimentAssigner assigner = new ExperimentAssigner();

    public MeasurementService(MeasurementRepository repository, ObjectMapper mapper, Clock clock,
            MeasurementProjectionStore projection) {
        this.repository = repository;
        this.mapper = mapper;
        this.clock = clock;
        this.projection = projection;
    }

    @Transactional
    public ExperimentView createExperiment(ExperimentRequest request) {
        var scope = TenantContextHolder.requireCurrent();
        scope.requirePermission("experiment:write");
        ExperimentAssigner.Experiment experiment = new ExperimentAssigner.Experiment(request.experimentId(),
                request.version(), request.layer(), request.salt(), request.variants());
        Instant now = clock.instant();
        repository.saveExperiment(new MeasurementRepository.ExperimentWrite(scope.tenantId().value(),
                request.experimentId(), request.version(), request.layer(), json(experiment), "ACTIVE", format(now)));
        return new ExperimentView(experiment, "ACTIVE", now);
    }

    @Transactional
    public AssignmentView assign(String experimentId, String version, String unit) {
        var scope = TenantContextHolder.requireCurrent();
        scope.requirePermission("experiment:assign");
        if (unit == null || unit.isBlank() || unit.length() > 256) {
            throw new IllegalArgumentException("randomization unit is invalid");
        }
        ExperimentView experiment = experiment(scope.tenantId().value(), experimentId, version);
        if (!"ACTIVE".equals(experiment.state())) throw new ConflictException("EXPERIMENT_NOT_ACTIVE", experimentId);
        List<AssignmentView> existing = assignment(scope.tenantId().value(), experimentId, version, unit);
        if (!existing.isEmpty()) return existing.getFirst();
        if (!claimLayer(scope.tenantId().value(), experiment.experiment().layer(), unit, experimentId)) {
            return new AssignmentView(experimentId, version, "EXCLUDED_BY_LAYER", true, -1, true, clock.instant());
        }
        ExperimentAssigner.Assignment assignment = assigner.assign(experiment.experiment(), unit);
        Instant now = clock.instant();
        boolean saved = repository.trySaveAssignment(new MeasurementRepository.AssignmentWrite(
                scope.tenantId().value(), experimentId, version, experiment.experiment().layer(), unit,
                assignment.variantId(), assignment.holdout(), assignment.bucket(), format(now)));
        if (!saved) {
            List<AssignmentView> winner = assignment(scope.tenantId().value(), experimentId, version, unit);
            if (!winner.isEmpty()) return winner.getFirst();
            throw new ConflictException("EXPERIMENT_ASSIGNMENT_RACE", "winning assignment is not visible");
        }
        return new AssignmentView(experimentId, version, assignment.variantId(), assignment.holdout(),
                assignment.bucket(), false, now);
    }

    @Transactional
    public FactReceipt ingest(MarketingFact fact) {
        var scope = TenantContextHolder.requireCurrent();
        scope.requirePermission("measurement:ingest");
        if (!scope.tenantId().equals(fact.tenantId())) {
            throw new ConflictException("FACT_TENANT_MISMATCH", "fact tenant must match service identity");
        }
        String payloadHash = factPayloadHash(fact);
        var duplicate = storedReceipt(scope.tenantId().value(), fact.eventId());
        if (duplicate.isPresent() && !duplicate.orElseThrow().payloadHash().equals(payloadHash)) {
            throw new ConflictException("FACT_EVENT_ID_COLLISION", "event id was used with another payload");
        }
        if (duplicate.isPresent()) return duplicate.orElseThrow().receipt();
        String experimentId = fact.attributes().getOrDefault("experimentId", "");
        String experimentVersion = fact.attributes().getOrDefault("experimentVersion", "");
        String variantId = fact.attributes().getOrDefault("variantId", "");
        if (fact.type() == MarketingFact.Type.EXPOSURE) {
            if (!"true".equalsIgnoreCase(fact.attributes().get("actualAction"))) {
                throw new ConflictException("EXPOSURE_NOT_ACTUAL", "assignment alone must not create exposure");
            }
            if (experimentId.isBlank() || experimentVersion.isBlank() || variantId.isBlank()) {
                throw new ConflictException("EXPOSURE_EXPERIMENT_MISSING",
                        "actual exposure must identify experiment, version and variant");
            }
        }
        String correctionRoot = fact.eventId();
        ProjectionContribution previous = null;
        long revision = 1;
        if (!fact.correctionOf().isBlank()) {
            correctionRoot = repository.findCorrectionRootForUpdate(scope.tenantId().value(), fact.correctionOf())
                    .orElseThrow(() -> new ConflictException("CORRECTION_TARGET_NOT_FOUND", fact.correctionOf()));
            MeasurementRepository.CorrectionIdentity root = repository.findCorrectionIdentityForUpdate(
                    scope.tenantId().value(), correctionRoot).orElse(null);
            var concurrentDuplicate = storedReceipt(scope.tenantId().value(), fact.eventId());
            if (concurrentDuplicate.isPresent()) {
                if (!concurrentDuplicate.orElseThrow().payloadHash().equals(payloadHash)) {
                    throw new ConflictException("FACT_EVENT_ID_COLLISION", "event id was used with another payload");
                }
                return concurrentDuplicate.orElseThrow().receipt();
            }
            if (root == null || !root.type().equals(fact.type().name())
                    || !root.businessKey().equals(fact.businessKey())
                    || !root.subjectHash().equals(subjectHash(fact.subjectToken()))) {
                throw new ConflictException("CORRECTION_IDENTITY_MISMATCH",
                        "correction must preserve root fact type, business key and subject");
            }
            List<String> activeEvents = repository.findActiveCorrectionEventsForUpdate(
                    scope.tenantId().value(), correctionRoot);
            if (activeEvents.size() != 1 || !activeEvents.getFirst().equals(fact.correctionOf())) {
                throw new ConflictException("CORRECTION_NOT_CURRENT",
                        "correctionOf must reference the single currently active fact");
            }
            int chainLength = repository.countCorrectionChain(scope.tenantId().value(), correctionRoot);
            if (chainLength >= 100) {
                throw new ConflictException("CORRECTION_CHAIN_LIMIT", "correction chain cannot exceed 100 facts");
            }
            revision = chainLength + 1L;
            previous = projectionContribution(scope.tenantId().value(), fact.correctionOf());
            int corrected = repository.markFactCorrected(scope.tenantId().value(), fact.correctionOf());
            if (corrected != 1) {
                throw new ConflictException("CORRECTION_CHAIN_INVALID", "correction chain has no single active value");
            }
        }
        long revenue = longAttribute(fact.attributes(), "revenueMinor");
        if (fact.type() == MarketingFact.Type.REFUND) revenue = Math.negateExact(revenue);
        long cost = longAttribute(fact.attributes(), "costMinor");
        boolean saved = repository.trySaveFact(new MeasurementRepository.FactWrite(scope.tenantId().value(),
                fact.eventId(), fact.type().name(), fact.businessKey(), subjectHash(fact.subjectToken()),
                format(fact.occurredAt()), format(fact.ingestedAt()), fact.schemaVersion(), payloadHash,
                json(fact.attributes()), fact.correctionOf(), correctionRoot, false, revenue, cost,
                experimentId, experimentVersion, variantId));
        if (!saved) {
            StoredFactReceipt winner = storedReceipt(scope.tenantId().value(), fact.eventId())
                    .orElseThrow(() -> new ConflictException("FACT_INSERT_RACE", "winning fact is not visible"));
            if (!winner.payloadHash().equals(payloadHash)) {
                throw new ConflictException("FACT_EVENT_ID_COLLISION", "event id was used with another payload");
            }
            return winner.receipt();
        }
        if (previous != null) {
            projection.apply(delta(scope.tenantId().value(), correctionRoot, fact.eventId(), revision,
                    MeasurementProjectionDelta.Operation.REVERSE, previous),
                    MeasurementProjectionStore.ProjectionSource.direct());
        }
        ProjectionContribution current = new ProjectionContribution(fact.type(), fact.businessKey(),
                fact.attributes().getOrDefault("campaignId", ""), experimentId, variantId,
                subjectHash(fact.subjectToken()), revenue, cost, fact.occurredAt(), fact.ingestedAt());
        projection.apply(delta(scope.tenantId().value(), correctionRoot, fact.eventId(), revision,
                MeasurementProjectionDelta.Operation.APPLY, current),
                MeasurementProjectionStore.ProjectionSource.direct());
        return new FactReceipt(fact.eventId(), false, fact.ingestedAt());
    }

    @Transactional
    public ProjectionWatermark advanceWatermark(WatermarkRequest request) {
        var scope = TenantContextHolder.requireCurrent();
        scope.requirePermission("measurement:watermark");
        if (!"dashboard".equals(request.projectionName()) || request.partitionCount() < 1
                || request.partitionCount() > 10_000 || request.partitionId() < 0
                || request.partitionId() >= request.partitionCount() || request.sourceOffset() < 0
                || request.completeThrough() == null
                || request.completeThrough().isAfter(clock.instant().plusSeconds(300))) {
            throw new IllegalArgumentException("projection watermark is invalid");
        }
        String tenantId = scope.tenantId().value();
        repository.tryCreateWatermarkConfig(new MeasurementRepository.WatermarkConfigWrite(tenantId,
                request.projectionName(), request.partitionCount(), format(clock.instant())));
        Integer configuredPartitions = repository.findWatermarkPartitionCountForUpdate(
                tenantId, request.projectionName()).orElse(null);
        if (configuredPartitions == null || configuredPartitions != request.partitionCount()) {
            throw new ConflictException("WATERMARK_PARTITION_COUNT_CONFLICT",
                    "projection partition count requires an explicit reset procedure");
        }
        var current = repository.findPartitionWatermarkForUpdate(
                tenantId, request.projectionName(), request.partitionId());
        long completeMillis = request.completeThrough().toEpochMilli();
        MeasurementRepository.PartitionWatermarkWrite write = new MeasurementRepository.PartitionWatermarkWrite(
                tenantId, request.projectionName(), request.partitionId(), request.sourceOffset(), completeMillis,
                format(clock.instant()));
        if (current.isEmpty()) {
            repository.savePartitionWatermark(write);
        } else {
            MeasurementRepository.PartitionWatermark previous = current.orElseThrow();
            if (request.sourceOffset() < previous.sourceOffset()
                    || completeMillis < previous.completeThroughEpochMillis()
                    || (request.sourceOffset() == previous.sourceOffset()
                        && completeMillis != previous.completeThroughEpochMillis())) {
                throw new ConflictException("WATERMARK_REGRESSION", "partition watermark must advance monotonically");
            }
            if (request.sourceOffset() > previous.sourceOffset()) {
                repository.updatePartitionWatermark(write);
            }
        }
        return projectionWatermark(tenantId, request.projectionName());
    }

    public Dashboard dashboard(Instant from, Instant to) {
        var scope = TenantContextHolder.requireCurrent();
        scope.requirePermission("measurement:read");
        if (from == null || to == null || !from.isBefore(to)) {
            throw new IllegalArgumentException("dashboard time range is invalid");
        }
        MeasurementProjectionStore.DashboardTotals totals = projection.totals(
                scope.tenantId().value(), from, to);
        Instant watermark = projectionWatermark(scope.tenantId().value(), "dashboard").completeThrough();
        BigDecimal roi = totals.costMinor() == 0 ? BigDecimal.ZERO
                : BigDecimal.valueOf(totals.revenueMinor())
                    .subtract(BigDecimal.valueOf(totals.costMinor()))
                    .divide(BigDecimal.valueOf(totals.costMinor()), 4, RoundingMode.HALF_UP);
        return new Dashboard(totals.counts(), totals.revenueMinor(), totals.costMinor(), roi, watermark,
                DurationView.of(clock.instant(), watermark));
    }

    public Series series(Instant from, Instant to, String granularity) {
        var scope = TenantContextHolder.requireCurrent();
        scope.requirePermission("measurement:read");
        if (from == null || to == null || !from.isBefore(to)
                || Duration.between(from, to).compareTo(Duration.ofDays(366)) > 0) {
            throw new IllegalArgumentException("measurement series time range is invalid");
        }
        Granularity parsed;
        try {
            parsed = Granularity.valueOf(granularity == null ? "" : granularity.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("granularity must be hour or day", invalid);
        }
        boolean hourly = parsed == Granularity.HOUR;
        Map<Instant, MeasurementProjectionStore.SeriesAmounts> totals = projection.series(
                scope.tenantId().value(), from, to, hourly);
        Instant cursor = hourly ? from.truncatedTo(ChronoUnit.HOURS)
                : from.atZone(ZoneOffset.UTC).toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant();
        List<SeriesPoint> points = new ArrayList<>();
        while (cursor.isBefore(to)) {
            MeasurementProjectionStore.SeriesAmounts amounts = totals.getOrDefault(cursor,
                    new MeasurementProjectionStore.SeriesAmounts(0, 0, 0));
            points.add(new SeriesPoint(cursor, amounts.revenueMinor(), amounts.costMinor(), amounts.conversions()));
            cursor = hourly ? cursor.plus(1, ChronoUnit.HOURS) : cursor.plus(1, ChronoUnit.DAYS);
        }
        return new Series(points);
    }

    public AttributionResult attribute(String conversionEventId, AttributionPolicy policy, long windowSeconds) {
        var scope = TenantContextHolder.requireCurrent();
        scope.requirePermission("measurement:read");
        return attribute(scope.tenantId().value(), conversionEventId, policy, windowSeconds);
    }

    private AttributionResult attribute(String tenantId, String conversionEventId, AttributionPolicy policy,
            long windowSeconds) {
        if (windowSeconds <= 0 || windowSeconds > 365L * 24 * 60 * 60) {
            throw new IllegalArgumentException("attribution window is invalid");
        }
        Fact conversion = fact(tenantId, conversionEventId);
        Instant start = conversion.occurredAt().minusSeconds(windowSeconds);
        List<Fact> touches = repository.findAttributionTouches(tenantId, conversion.subjectHash(),
                        format(start), format(conversion.occurredAt())).stream()
                .map(this::fact).toList();
        if (touches.isEmpty()) return new AttributionResult(conversionEventId, policy, Map.of(), conversion.revenue(), clock.instant());
        Map<String, BigDecimal> credits = new LinkedHashMap<>();
        switch (policy) {
            case FIRST_TOUCH -> credits.put(touches.getFirst().eventId(), BigDecimal.ONE);
            case LAST_TOUCH -> credits.put(touches.getLast().eventId(), BigDecimal.ONE);
            case LINEAR -> {
                BigDecimal credit = BigDecimal.ONE.divide(BigDecimal.valueOf(touches.size()), 8, RoundingMode.HALF_UP);
                touches.forEach(touch -> credits.put(touch.eventId(), credit));
            }
        }
        return new AttributionResult(conversionEventId, policy, credits, conversion.revenue(), clock.instant());
    }

    @Transactional
    public AttributionRecomputeResult recomputeAttribution(String commandId) {
        var scope = TenantContextHolder.requireCurrent();
        scope.requirePermission("measurement:ingest");
        return command(scope.tenantId().value(), commandId, Digests.sha256Hex("ATTRIBUTION_RECOMPUTE_ALL"),
                AttributionRecomputeResult.class, () -> recomputeAttributionNow(scope.tenantId().value()));
    }

    private AttributionRecomputeResult recomputeAttributionNow(String tenantId) {
        repository.ensureAttributionLock(tenantId, format(clock.instant()));
        repository.lockAttribution(tenantId)
                .orElseThrow(() -> new IllegalStateException("attribution recompute lock disappeared"));
        List<String> conversions = repository.findActiveConversionIds(tenantId);
        repository.deleteAttributionCredits(tenantId);
        Instant calculatedAt = clock.instant();
        int credits = 0;
        long windowSeconds = 30L * 24 * 60 * 60;
        for (String conversionEventId : conversions) {
            for (AttributionPolicy policy : AttributionPolicy.values()) {
                AttributionResult result = attribute(tenantId, conversionEventId, policy, windowSeconds);
                for (Map.Entry<String, BigDecimal> credit : result.touchCredits().entrySet()) {
                    repository.saveAttributionCredit(new MeasurementRepository.AttributionCreditWrite(
                            tenantId, conversionEventId, policy.name(), credit.getKey(), credit.getValue(),
                            result.revenueMinor(), format(calculatedAt)));
                    credits++;
                }
            }
        }
        repository.updateAttributionLock(tenantId, format(calculatedAt));
        return new AttributionRecomputeResult(conversions.size(), credits, calculatedAt);
    }

    @Transactional
    public SrmReport checkSrm(String experimentId, String version) {
        var scope = TenantContextHolder.requireCurrent();
        scope.requirePermission("experiment:monitor");
        ExperimentView experiment = experiment(scope.tenantId().value(), experimentId, version);
        Map<String, Long> observed = new LinkedHashMap<>();
        repository.countExposureByVariant(scope.tenantId().value(), experimentId, version)
                .forEach(row -> observed.put(row.variantId(), row.countValue()));
        long total = observed.values().stream().mapToLong(Long::longValue).sum();
        double chiSquare = 0;
        for (ExperimentAssigner.Variant variant : experiment.experiment().variants()) {
            double expected = total * (variant.basisPoints() / 10_000.0);
            if (expected > 0) {
                double delta = observed.getOrDefault(variant.variantId(), 0L) - expected;
                chiSquare += delta * delta / expected;
            }
        }
        boolean alert = total >= 100 && chiSquare > 16.27;
        if (alert) repository.pauseExperimentForSrm(scope.tenantId().value(), experimentId, version);
        return new SrmReport(experimentId, version, total, observed, chiSquare, alert,
                alert ? "PAUSED_SRM" : experiment.state(), clock.instant());
    }

    @Transactional
    public TraceView recordTrace(TraceRequest request) {
        var scope = TenantContextHolder.requireCurrent();
        scope.requirePermission("trace:write");
        Instant now = clock.instant();
        repository.saveTrace(new MeasurementRepository.TraceWrite(scope.tenantId().value(), request.traceId(),
                request.requestId(), request.orderId(), subjectHash(request.subjectToken()), request.generation(),
                request.durationMicros(), json(request.candidates()), json(request.pricing()), request.termsVersion(),
                format(request.expiresAt()), request.legalHold(), format(now)));
        return trace(scope.tenantId().value(), request.requestId(), null);
    }

    public TraceView traceByRequest(String requestId) {
        var scope = TenantContextHolder.requireCurrent();
        scope.requirePermission("trace:read");
        return trace(scope.tenantId().value(), requestId, null);
    }

    public TraceView traceByOrder(String orderId) {
        var scope = TenantContextHolder.requireCurrent();
        scope.requirePermission("trace:read");
        return trace(scope.tenantId().value(), null, orderId);
    }

    private TraceView trace(String tenantId, String requestId, String orderId) {
        var row = requestId != null
                ? repository.findTraceByRequest(tenantId, requestId, format(clock.instant()))
                : repository.findTraceByOrder(tenantId, orderId, format(clock.instant()));
        MeasurementRepository.TraceRow stored = row.orElseThrow(
                () -> new NotFoundException("TRACE_NOT_FOUND", "decision trace not found or expired"));
        return new TraceView(stored.traceId(), stored.requestId(), stored.orderId(),
                stored.subjectHash().substring(0, 12) + "…", stored.generationNo(), stored.durationMicros(),
                read(stored.candidatesJson(), Map.class), read(stored.pricingJson(), Map.class),
                stored.termsVersion(), Instant.parse(stored.expiresAt()), stored.legalHold(),
                Instant.parse(stored.createdAt()), List.of());
    }

    private ExperimentView experiment(String tenantId, String id, String version) {
        MeasurementRepository.ExperimentRow row = repository.findExperiment(tenantId, id, version)
                .orElseThrow(() -> new NotFoundException("EXPERIMENT_NOT_FOUND", "experiment version not found"));
        return new ExperimentView(read(row.definitionJson(), ExperimentAssigner.Experiment.class), row.stateName(),
                Instant.parse(row.createdAt()));
    }

    private List<AssignmentView> assignment(String tenantId, String experimentId, String version, String unit) {
        return repository.findAssignment(tenantId, experimentId, version, unit).stream()
                .map(row -> new AssignmentView(experimentId, version, row.variantId(), row.holdoutValue(),
                        row.bucketNo(), false, Instant.parse(row.assignedAt())))
                .toList();
    }

    private boolean claimLayer(String tenantId, String layer, String unit, String experimentId) {
        repository.tryClaimLayer(new MeasurementRepository.LayerAssignmentWrite(tenantId, layer, unit,
                experimentId, format(clock.instant())));
        String owner = repository.findLayerOwnerForUpdate(tenantId, layer, unit).orElse(null);
        return experimentId.equals(owner);
    }

    private java.util.Optional<StoredFactReceipt> storedReceipt(String tenantId, String eventId) {
        return repository.findStoredFactReceipt(tenantId, eventId)
                .map(row -> new StoredFactReceipt(
                        new FactReceipt(eventId, true, Instant.parse(row.ingestedAt())), row.payloadHash()));
    }

    private String factPayloadHash(MarketingFact fact) {
        return Digests.sha256Hex(String.join("|", fact.type().name(), fact.businessKey(), fact.subjectToken(),
                fact.occurredAt().toString(), fact.schemaVersion(), fact.correctionOf(),
                json(new TreeMap<>(fact.attributes()))));
    }

    private ProjectionWatermark projectionWatermark(String tenantId, String projectionName) {
        Integer configured = repository.findWatermarkPartitionCount(tenantId, projectionName).orElse(null);
        if (configured == null) return new ProjectionWatermark(projectionName, 0, 0, Instant.EPOCH);
        MeasurementRepository.WatermarkAggregate aggregate = repository.aggregateWatermark(tenantId, projectionName);
        Instant complete = aggregate.partitionCount() == configured && aggregate.minimumEpochMillis() > 0
                ? Instant.ofEpochMilli(aggregate.minimumEpochMillis()) : Instant.EPOCH;
        return new ProjectionWatermark(projectionName, configured, aggregate.partitionCount(), complete);
    }

    private Fact fact(String tenantId, String eventId) {
        return repository.findActiveFact(tenantId, eventId).map(this::fact)
                .orElseThrow(() -> new NotFoundException("FACT_NOT_FOUND", "active fact not found"));
    }

    private ProjectionContribution projectionContribution(String tenantId, String eventId) {
        MeasurementRepository.ProjectionContributionRow row = repository.findProjectionContribution(tenantId, eventId)
                .orElseThrow(() -> new ConflictException("CORRECTION_TARGET_NOT_FOUND", eventId));
        @SuppressWarnings("unchecked")
        Map<String, String> attributes = read(row.attributesJson(), Map.class);
        return new ProjectionContribution(MarketingFact.Type.valueOf(row.factType()), row.businessKey(),
                attributes.getOrDefault("campaignId", ""), row.experimentId(), row.variantId(), row.subjectHash(),
                row.revenueMinor(), row.costMinor(), Instant.parse(row.occurredAt()), Instant.parse(row.ingestedAt()));
    }

    private Fact fact(MeasurementRepository.FactRow row) {
        return new Fact(row.eventId(), row.factType(), row.businessKey(), row.subjectHash(),
                Instant.parse(row.occurredAt()), row.revenueMinor(), row.costMinor());
    }

    private <T> T command(String tenantId, String commandId, String payloadHash, Class<T> type,
            Supplier<T> action) {
        if (commandId == null || !commandId.matches("[a-zA-Z0-9_.:-]{8,128}")) {
            throw new IllegalArgumentException("command id is invalid");
        }
        String now = format(clock.instant());
        String expiresAt = format(clock.instant().plusSeconds(604_800));
        boolean owner = repository.tryBeginCommand(new MeasurementRepository.CommandWrite(
                tenantId, commandId, payloadHash, "PROCESSING", null, now, expiresAt));
        MeasurementRepository.StoredCommand stored = repository.findCommandForUpdate(tenantId, commandId)
                .orElseThrow(() -> new ConflictException("MEASUREMENT_COMMAND_LOST",
                        "command record disappeared"));
        if (!owner && stored.expiresAt().compareTo(now) <= 0) {
            repository.deleteCommand(tenantId, commandId);
            if (!repository.tryBeginCommand(new MeasurementRepository.CommandWrite(
                    tenantId, commandId, payloadHash, "PROCESSING", null, now, expiresAt))) {
                throw new ConflictException("MEASUREMENT_COMMAND_RACE",
                        "expired command was concurrently reclaimed");
            }
            owner = true;
            stored = new MeasurementRepository.StoredCommand(payloadHash, "PROCESSING", null, expiresAt);
        }
        if (!stored.payloadHash().equals(payloadHash)) {
            throw new ConflictException("IDEMPOTENCY_PAYLOAD_CONFLICT", "command id was used with another payload");
        }
        if (!owner) {
            if (!"COMPLETED".equals(stored.stateName()) || stored.responseJson() == null) {
                throw new ConflictException("COMMAND_IN_PROGRESS", "original command is still in progress");
            }
            return read(stored.responseJson(), type);
        }
        T response = action.get();
        repository.completeCommand(tenantId, commandId, json(response));
        return response;
    }

    private static MeasurementProjectionDelta delta(String tenantId, String rootEventId,
            String sourceEventId, long revision, MeasurementProjectionDelta.Operation operation,
            ProjectionContribution contribution) {
        long factor = operation == MeasurementProjectionDelta.Operation.APPLY ? 1 : -1;
        String deltaId = Digests.sha256Hex(tenantId + ':' + rootEventId + ':' + sourceEventId
                + ':' + revision + ':' + operation);
        return new MeasurementProjectionDelta("MEASUREMENT_PROJECTION_DELTA", deltaId, tenantId,
                rootEventId, sourceEventId, revision, operation, contribution.type(),
                contribution.businessKey(), contribution.campaignId(), contribution.experimentId(),
                contribution.variantId(), contribution.subjectHash(), factor,
                Math.multiplyExact(factor, contribution.revenueMinor()),
                Math.multiplyExact(factor, contribution.costMinor()), contribution.occurredAt().toEpochMilli(),
                contribution.ingestedAt().toEpochMilli());
    }

    private static long longAttribute(Map<String, String> attributes, String key) {
        String value = attributes.get(key);
        return value == null || value.isBlank() ? 0 : Long.parseLong(value);
    }
    private static String subjectHash(String token) { return Digests.sha256Hex(token == null ? "" : token); }
    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (JacksonException failure) { throw new IllegalArgumentException("measurement payload cannot be serialized", failure); }
    }
    @SuppressWarnings("unchecked")
    private <T> T read(String value, Class<T> type) {
        try { return mapper.readValue(value, type); }
        catch (JacksonException failure) { throw new IllegalStateException("stored measurement payload is invalid", failure); }
    }

    public enum AttributionPolicy { FIRST_TOUCH, LAST_TOUCH, LINEAR }
    public enum Granularity { HOUR, DAY }
    private record StoredFactReceipt(FactReceipt receipt, String payloadHash) { }
    private record Fact(String eventId, String type, String businessKey, String subjectHash,
            Instant occurredAt, long revenue, long cost) { }
    private record ProjectionContribution(MarketingFact.Type type, String businessKey, String campaignId,
            String experimentId, String variantId, String subjectHash, long revenueMinor, long costMinor,
            Instant occurredAt, Instant ingestedAt) { }
    public record ExperimentRequest(String experimentId, String version, String layer, String salt,
            List<ExperimentAssigner.Variant> variants) { public ExperimentRequest { variants = List.copyOf(variants); } }
    public record ExperimentView(ExperimentAssigner.Experiment experiment, String state, Instant createdAt) { }
    public record AssignmentView(String experimentId, String version, String variantId, boolean holdout,
            int bucket, boolean excludedByLayer, Instant assignedAt) { }
    public record FactReceipt(String eventId, boolean duplicate, Instant ingestedAt) { }
    public record WatermarkRequest(String projectionName, int partitionId, int partitionCount,
            long sourceOffset, Instant completeThrough) { }
    public record ProjectionWatermark(String projectionName, int expectedPartitions, int reportedPartitions,
            Instant completeThrough) { }
    public record Dashboard(Map<String, Long> counts, long attributedRevenueMinor, long fundingCostMinor,
            BigDecimal roi, Instant completeThrough, DurationView lag) { public Dashboard { counts = Map.copyOf(counts); } }
    public record DurationView(long seconds) {
        static DurationView of(Instant now, Instant watermark) { return new DurationView(Math.max(0, now.getEpochSecond() - watermark.getEpochSecond())); }
    }
    public record AttributionResult(String conversionEventId, AttributionPolicy policy,
            Map<String, BigDecimal> touchCredits, long revenueMinor, Instant calculatedAt) {
        public AttributionResult { touchCredits = Map.copyOf(touchCredits); }
    }
    public record Series(List<SeriesPoint> points) { public Series { points = List.copyOf(points); } }
    public record SeriesPoint(Instant at, long revenueMinor, long costMinor, long conversions) { }
    public record AttributionRecomputeResult(int conversions, int credits, Instant completedAt) { }
    public record SrmReport(String experimentId, String version, long sampleSize, Map<String, Long> observed,
            double chiSquare, boolean alert, String state, Instant checkedAt) { public SrmReport { observed = Map.copyOf(observed); } }
    public record TraceRequest(String traceId, String requestId, String orderId, String subjectToken,
            long generation, long durationMicros, Map<String, Object> candidates, Map<String, Object> pricing,
            String termsVersion, Instant expiresAt, boolean legalHold) { }
    public record TraceView(String traceId, String requestId, String orderId, String maskedSubject,
            long generation, long durationMicros, Map<?, ?> candidates, Map<?, ?> pricing,
            String termsVersion, Instant expiresAt, boolean legalHold, Instant createdAt, List<TraceEvent> events) {
        public TraceView { events = List.copyOf(events == null ? List.of() : events); }
    }
    public record TraceEvent(Instant at, String type, String title, String detail) { }
}
