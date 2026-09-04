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
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class MeasurementService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final MeasurementProjectionStore projection;
    private final ExperimentAssigner assigner = new ExperimentAssigner();

    public MeasurementService(JdbcTemplate jdbc, ObjectMapper mapper, Clock clock,
            MeasurementProjectionStore projection) {
        this.jdbc = jdbc;
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
        jdbc.update("insert into mk_experiment(tenant_id,experiment_id,version_no,layer_name,definition_json,state_name,created_at) values(?,?,?,?,?,?,?)",
                scope.tenantId().value(), request.experimentId(), request.version(), request.layer(), json(experiment),
                "ACTIVE", format(now));
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
        try {
            jdbc.update("insert into mk_experiment_assignment(tenant_id,experiment_id,version_no,layer_name,randomization_unit,variant_id,holdout_value,bucket_no,assigned_at) values(?,?,?,?,?,?,?,?,?)",
                    scope.tenantId().value(), experimentId, version, experiment.experiment().layer(), unit,
                    assignment.variantId(), assignment.holdout(), assignment.bucket(), format(now));
        } catch (DuplicateKeyException race) {
            List<AssignmentView> winner = assignment(scope.tenantId().value(), experimentId, version, unit);
            if (!winner.isEmpty()) return winner.getFirst();
            throw race;
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
        List<StoredFactReceipt> duplicate = storedReceipt(scope.tenantId().value(), fact.eventId());
        if (!duplicate.isEmpty() && !duplicate.getFirst().payloadHash().equals(payloadHash)) {
            throw new ConflictException("FACT_EVENT_ID_COLLISION", "event id was used with another payload");
        }
        if (!duplicate.isEmpty()) return duplicate.getFirst().receipt();
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
            List<CorrectionTarget> targets = jdbc.query("select correction_root_id from mk_fact where tenant_id=? and event_id=? for update",
                    (rs, rowNum) -> new CorrectionTarget(rs.getString(1)), scope.tenantId().value(), fact.correctionOf());
            if (targets.isEmpty()) throw new ConflictException("CORRECTION_TARGET_NOT_FOUND", fact.correctionOf());
            correctionRoot = targets.getFirst().rootEventId();
            CorrectionIdentity root = jdbc.query("select fact_type,business_key,subject_hash from mk_fact where tenant_id=? and event_id=? for update",
                    rs -> rs.next() ? new CorrectionIdentity(rs.getString(1), rs.getString(2), rs.getString(3)) : null,
                    scope.tenantId().value(), correctionRoot);
            List<StoredFactReceipt> concurrentDuplicate = storedReceipt(scope.tenantId().value(), fact.eventId());
            if (!concurrentDuplicate.isEmpty()) {
                if (!concurrentDuplicate.getFirst().payloadHash().equals(payloadHash)) {
                    throw new ConflictException("FACT_EVENT_ID_COLLISION", "event id was used with another payload");
                }
                return concurrentDuplicate.getFirst().receipt();
            }
            if (root == null || !root.type().equals(fact.type().name())
                    || !root.businessKey().equals(fact.businessKey())
                    || !root.subjectHash().equals(subjectHash(fact.subjectToken()))) {
                throw new ConflictException("CORRECTION_IDENTITY_MISMATCH",
                        "correction must preserve root fact type, business key and subject");
            }
            List<String> activeEvents = jdbc.query("select event_id from mk_fact where tenant_id=? and correction_root_id=? and corrected_value=false for update",
                    (rs, rowNum) -> rs.getString(1), scope.tenantId().value(), correctionRoot);
            if (activeEvents.size() != 1 || !activeEvents.getFirst().equals(fact.correctionOf())) {
                throw new ConflictException("CORRECTION_NOT_CURRENT",
                        "correctionOf must reference the single currently active fact");
            }
            Integer chainLength = jdbc.query("select count(*) from mk_fact where tenant_id=? and correction_root_id=?",
                    rs -> rs.next() ? rs.getInt(1) : 0, scope.tenantId().value(), correctionRoot);
            if (chainLength != null && chainLength >= 100) {
                throw new ConflictException("CORRECTION_CHAIN_LIMIT", "correction chain cannot exceed 100 facts");
            }
            revision = (chainLength == null ? 1 : chainLength) + 1L;
            previous = projectionContribution(scope.tenantId().value(), fact.correctionOf());
            int corrected = jdbc.update("update mk_fact set corrected_value=true where tenant_id=? and event_id=? and corrected_value=false",
                    scope.tenantId().value(), fact.correctionOf());
            if (corrected != 1) {
                throw new ConflictException("CORRECTION_CHAIN_INVALID", "correction chain has no single active value");
            }
        }
        long revenue = longAttribute(fact.attributes(), "revenueMinor");
        if (fact.type() == MarketingFact.Type.REFUND) revenue = Math.negateExact(revenue);
        long cost = longAttribute(fact.attributes(), "costMinor");
        try {
            jdbc.update("insert into mk_fact(tenant_id,event_id,fact_type,business_key,subject_hash,occurred_at,ingested_at,schema_version,payload_hash,attributes_json,correction_of,correction_root_id,corrected_value,revenue_minor,cost_minor,experiment_id,experiment_version,variant_id) values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    scope.tenantId().value(), fact.eventId(), fact.type().name(), fact.businessKey(),
                    subjectHash(fact.subjectToken()), format(fact.occurredAt()), format(fact.ingestedAt()),
                    fact.schemaVersion(), payloadHash, json(fact.attributes()), fact.correctionOf(), correctionRoot,
                    false, revenue, cost,
                    experimentId, experimentVersion, variantId);
        } catch (DuplicateKeyException race) {
            StoredFactReceipt winner = storedReceipt(scope.tenantId().value(), fact.eventId()).stream()
                    .findFirst().orElseThrow(() -> race);
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
        try {
            jdbc.update("insert into mk_projection_watermark_config(tenant_id,projection_name,partition_count,updated_at) values(?,?,?,?)",
                    tenantId, request.projectionName(), request.partitionCount(), format(clock.instant()));
        } catch (DuplicateKeyException exists) {
            // Locked and validated below.
        }
        Integer configuredPartitions = jdbc.query("select partition_count from mk_projection_watermark_config where tenant_id=? and projection_name=? for update",
                rs -> rs.next() ? rs.getInt(1) : null, tenantId, request.projectionName());
        if (configuredPartitions == null || configuredPartitions != request.partitionCount()) {
            throw new ConflictException("WATERMARK_PARTITION_COUNT_CONFLICT",
                    "projection partition count requires an explicit reset procedure");
        }
        List<PartitionWatermark> current = jdbc.query("select source_offset,complete_through_epoch_ms from mk_projection_partition_watermark where tenant_id=? and projection_name=? and partition_id=? for update",
                (rs, rowNum) -> new PartitionWatermark(rs.getLong(1), rs.getLong(2)), tenantId,
                request.projectionName(), request.partitionId());
        long completeMillis = request.completeThrough().toEpochMilli();
        if (current.isEmpty()) {
            jdbc.update("insert into mk_projection_partition_watermark(tenant_id,projection_name,partition_id,source_offset,complete_through_epoch_ms,updated_at) values(?,?,?,?,?,?)",
                    tenantId, request.projectionName(), request.partitionId(), request.sourceOffset(), completeMillis,
                    format(clock.instant()));
        } else {
            PartitionWatermark previous = current.getFirst();
            if (request.sourceOffset() < previous.sourceOffset()
                    || completeMillis < previous.completeThroughEpochMillis()
                    || (request.sourceOffset() == previous.sourceOffset()
                        && completeMillis != previous.completeThroughEpochMillis())) {
                throw new ConflictException("WATERMARK_REGRESSION", "partition watermark must advance monotonically");
            }
            if (request.sourceOffset() > previous.sourceOffset()) {
                jdbc.update("update mk_projection_partition_watermark set source_offset=?,complete_through_epoch_ms=?,updated_at=? where tenant_id=? and projection_name=? and partition_id=?",
                        request.sourceOffset(), completeMillis, format(clock.instant()), tenantId,
                        request.projectionName(), request.partitionId());
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
        List<Fact> touches = jdbc.query("select event_id,fact_type,business_key,occurred_at,revenue_minor,cost_minor from mk_fact where tenant_id=? and subject_hash=? and corrected_value=false and fact_type in ('EXPOSURE','CONTACT_SENT','CLICK','OFFER_SHOWN') and occurred_at>=? and occurred_at<=? order by occurred_at",
                (rs, rowNum) -> new Fact(rs.getString(1), rs.getString(2), rs.getString(3),
                        conversion.subjectHash(), Instant.parse(rs.getString(4)), rs.getLong(5), rs.getLong(6)),
                tenantId, conversion.subjectHash(), format(start), format(conversion.occurredAt()));
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
        jdbc.update("insert into mk_attribution_recompute_lock(tenant_id,updated_at) values(?,?) on duplicate key update tenant_id=tenant_id",
                tenantId, format(clock.instant()));
        jdbc.query("select tenant_id from mk_attribution_recompute_lock where tenant_id=? for update",
                rs -> rs.next() ? rs.getString(1) : null, tenantId);
        List<String> conversions = jdbc.query(
                "select event_id from mk_fact where tenant_id=? and fact_type='CONVERSION' and corrected_value=false order by occurred_at,event_id",
                (rs, rowNum) -> rs.getString(1), tenantId);
        jdbc.update("delete from mk_attribution_credit where tenant_id=?", tenantId);
        Instant calculatedAt = clock.instant();
        int credits = 0;
        long windowSeconds = 30L * 24 * 60 * 60;
        for (String conversionEventId : conversions) {
            for (AttributionPolicy policy : AttributionPolicy.values()) {
                AttributionResult result = attribute(tenantId, conversionEventId, policy, windowSeconds);
                for (Map.Entry<String, BigDecimal> credit : result.touchCredits().entrySet()) {
                    jdbc.update("insert into mk_attribution_credit(tenant_id,conversion_event_id,policy_name,touch_event_id,credit_value,revenue_minor,calculated_at) values(?,?,?,?,?,?,?)",
                            tenantId, conversionEventId, policy.name(), credit.getKey(), credit.getValue(),
                            result.revenueMinor(), format(calculatedAt));
                    credits++;
                }
            }
        }
        jdbc.update("update mk_attribution_recompute_lock set updated_at=? where tenant_id=?",
                format(calculatedAt), tenantId);
        return new AttributionRecomputeResult(conversions.size(), credits, calculatedAt);
    }

    @Transactional
    public SrmReport checkSrm(String experimentId, String version) {
        var scope = TenantContextHolder.requireCurrent();
        scope.requirePermission("experiment:monitor");
        ExperimentView experiment = experiment(scope.tenantId().value(), experimentId, version);
        Map<String, Long> observed = new LinkedHashMap<>();
        jdbc.query("select variant_id,count(*) from mk_fact where tenant_id=? and fact_type='EXPOSURE' and corrected_value=false and experiment_id=? and experiment_version=? group by variant_id",
                rs -> { observed.put(rs.getString(1), rs.getLong(2)); }, scope.tenantId().value(),
                experimentId, version);
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
        if (alert) jdbc.update("update mk_experiment set state_name='PAUSED_SRM' where tenant_id=? and experiment_id=? and version_no=?",
                scope.tenantId().value(), experimentId, version);
        return new SrmReport(experimentId, version, total, observed, chiSquare, alert,
                alert ? "PAUSED_SRM" : experiment.state(), clock.instant());
    }

    @Transactional
    public TraceView recordTrace(TraceRequest request) {
        var scope = TenantContextHolder.requireCurrent();
        scope.requirePermission("trace:write");
        Instant now = clock.instant();
        jdbc.update("insert into mk_decision_trace(tenant_id,trace_id,request_id,order_id,subject_hash,generation_no,duration_micros,candidates_json,pricing_json,terms_version,expires_at,legal_hold,created_at) values(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                scope.tenantId().value(), request.traceId(), request.requestId(), request.orderId(),
                subjectHash(request.subjectToken()), request.generation(), request.durationMicros(),
                json(request.candidates()), json(request.pricing()), request.termsVersion(),
                format(request.expiresAt()), request.legalHold(), format(now));
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
        String field = requestId != null ? "request_id" : "order_id";
        String value = requestId != null ? requestId : orderId;
        List<TraceView> rows = jdbc.query("select trace_id,request_id,order_id,subject_hash,generation_no,duration_micros,candidates_json,pricing_json,terms_version,expires_at,legal_hold,created_at from mk_decision_trace where tenant_id=? and " + field + "=? and (expires_at>? or legal_hold=true)",
                (rs, rowNum) -> new TraceView(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4).substring(0, 12) + "…", rs.getLong(5), rs.getLong(6),
                        read(rs.getString(7), Map.class), read(rs.getString(8), Map.class), rs.getString(9),
                        Instant.parse(rs.getString(10)), rs.getBoolean(11), Instant.parse(rs.getString(12)), List.of()),
                tenantId, value, format(clock.instant()));
        if (rows.isEmpty()) throw new NotFoundException("TRACE_NOT_FOUND", "decision trace not found or expired");
        return rows.getFirst();
    }

    private ExperimentView experiment(String tenantId, String id, String version) {
        List<ExperimentView> rows = jdbc.query("select definition_json,state_name,created_at from mk_experiment where tenant_id=? and experiment_id=? and version_no=?",
                (rs, rowNum) -> new ExperimentView(read(rs.getString(1), ExperimentAssigner.Experiment.class),
                        rs.getString(2), Instant.parse(rs.getString(3))), tenantId, id, version);
        if (rows.isEmpty()) throw new NotFoundException("EXPERIMENT_NOT_FOUND", "experiment version not found");
        return rows.getFirst();
    }

    private List<AssignmentView> assignment(String tenantId, String experimentId, String version, String unit) {
        return jdbc.query("select variant_id,holdout_value,bucket_no,assigned_at from mk_experiment_assignment where tenant_id=? and experiment_id=? and version_no=? and randomization_unit=?",
                (rs, rowNum) -> new AssignmentView(experimentId, version, rs.getString(1), rs.getBoolean(2),
                        rs.getInt(3), false, Instant.parse(rs.getString(4))), tenantId, experimentId, version, unit);
    }

    private boolean claimLayer(String tenantId, String layer, String unit, String experimentId) {
        try {
            jdbc.update("insert into mk_experiment_layer_assignment(tenant_id,layer_name,randomization_unit,experiment_id,assigned_at) values(?,?,?,?,?)",
                    tenantId, layer, unit, experimentId, format(clock.instant()));
        } catch (DuplicateKeyException occupied) {
            // The row is read under the same transaction after the competing assignment commits.
        }
        String owner = jdbc.query("select experiment_id from mk_experiment_layer_assignment where tenant_id=? and layer_name=? and randomization_unit=? for update",
                rs -> rs.next() ? rs.getString(1) : null, tenantId, layer, unit);
        return experimentId.equals(owner);
    }

    private List<StoredFactReceipt> storedReceipt(String tenantId, String eventId) {
        return jdbc.query("select ingested_at,payload_hash from mk_fact where tenant_id=? and event_id=?",
                (rs, rowNum) -> new StoredFactReceipt(
                        new FactReceipt(eventId, true, Instant.parse(rs.getString(1))), rs.getString(2)),
                tenantId, eventId);
    }

    private String factPayloadHash(MarketingFact fact) {
        return Digests.sha256Hex(String.join("|", fact.type().name(), fact.businessKey(), fact.subjectToken(),
                fact.occurredAt().toString(), fact.schemaVersion(), fact.correctionOf(),
                json(new TreeMap<>(fact.attributes()))));
    }

    private ProjectionWatermark projectionWatermark(String tenantId, String projectionName) {
        Integer configured = jdbc.query("select partition_count from mk_projection_watermark_config where tenant_id=? and projection_name=?",
                rs -> rs.next() ? rs.getInt(1) : null, tenantId, projectionName);
        if (configured == null) return new ProjectionWatermark(projectionName, 0, 0, Instant.EPOCH);
        WatermarkAggregate aggregate = jdbc.query("select count(*),coalesce(min(complete_through_epoch_ms),0) from mk_projection_partition_watermark where tenant_id=? and projection_name=?",
                rs -> rs.next() ? new WatermarkAggregate(rs.getInt(1), rs.getLong(2))
                        : new WatermarkAggregate(0, 0), tenantId, projectionName);
        Instant complete = aggregate.partitionCount() == configured && aggregate.minimumEpochMillis() > 0
                ? Instant.ofEpochMilli(aggregate.minimumEpochMillis()) : Instant.EPOCH;
        return new ProjectionWatermark(projectionName, configured, aggregate.partitionCount(), complete);
    }

    private Fact fact(String tenantId, String eventId) {
        List<Fact> rows = jdbc.query("select fact_type,business_key,subject_hash,occurred_at,revenue_minor,cost_minor from mk_fact where tenant_id=? and event_id=? and corrected_value=false",
                (rs, rowNum) -> new Fact(eventId, rs.getString(1), rs.getString(2), rs.getString(3),
                        Instant.parse(rs.getString(4)), rs.getLong(5), rs.getLong(6)), tenantId, eventId);
        if (rows.isEmpty()) throw new NotFoundException("FACT_NOT_FOUND", "active fact not found");
        return rows.getFirst();
    }

    private ProjectionContribution projectionContribution(String tenantId, String eventId) {
        List<ProjectionContribution> rows = jdbc.query("select fact_type,business_key,subject_hash,occurred_at,ingested_at,revenue_minor,cost_minor,attributes_json,experiment_id,variant_id from mk_fact where tenant_id=? and event_id=?",
                (rs, rowNum) -> {
                    @SuppressWarnings("unchecked")
                    Map<String, String> attributes = read(rs.getString(8), Map.class);
                    return new ProjectionContribution(MarketingFact.Type.valueOf(rs.getString(1)),
                            rs.getString(2), attributes.getOrDefault("campaignId", ""), rs.getString(9),
                            rs.getString(10), rs.getString(3), rs.getLong(6), rs.getLong(7),
                            Instant.parse(rs.getString(4)), Instant.parse(rs.getString(5)));
                }, tenantId, eventId);
        if (rows.isEmpty()) {
            throw new ConflictException("CORRECTION_TARGET_NOT_FOUND", eventId);
        }
        return rows.getFirst();
    }

    private <T> T command(String tenantId, String commandId, String payloadHash, Class<T> type,
            Supplier<T> action) {
        if (commandId == null || !commandId.matches("[a-zA-Z0-9_.:-]{8,128}")) {
            throw new IllegalArgumentException("command id is invalid");
        }
        String now = format(clock.instant());
        boolean owner = false;
        String expiresAt = format(clock.instant().plusSeconds(604_800));
        try {
            jdbc.update("insert into mk_measurement_command(tenant_id,command_id,payload_hash,state_name,response_json,created_at,expires_at) values(?,?,?,?,?,?,?)",
                    tenantId, commandId, payloadHash, "PROCESSING", null, now, expiresAt);
            owner = true;
        } catch (DuplicateKeyException duplicate) {
            // The locking read below waits for the winning transaction.
        }
        List<StoredCommand> rows = jdbc.query("select payload_hash,state_name,response_json,expires_at from mk_measurement_command where tenant_id=? and command_id=? for update",
                (rs, rowNum) -> new StoredCommand(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)),
                tenantId, commandId);
        if (rows.isEmpty()) throw new ConflictException("MEASUREMENT_COMMAND_LOST", "command record disappeared");
        StoredCommand stored = rows.getFirst();
        if (!owner && stored.expiresAt().compareTo(now) <= 0) {
            jdbc.update("delete from mk_measurement_command where tenant_id=? and command_id=?", tenantId, commandId);
            jdbc.update("insert into mk_measurement_command(tenant_id,command_id,payload_hash,state_name,response_json,created_at,expires_at) values(?,?,?,?,?,?,?)",
                    tenantId, commandId, payloadHash, "PROCESSING", null, now, expiresAt);
            owner = true;
            stored = new StoredCommand(payloadHash, "PROCESSING", null, expiresAt);
        }
        if (!stored.payloadHash().equals(payloadHash)) {
            throw new ConflictException("IDEMPOTENCY_PAYLOAD_CONFLICT", "command id was used with another payload");
        }
        if (!owner) {
            if (!"COMPLETED".equals(stored.state()) || stored.responseJson() == null) {
                throw new ConflictException("COMMAND_IN_PROGRESS", "original command is still in progress");
            }
            return read(stored.responseJson(), type);
        }
        T response = action.get();
        jdbc.update("update mk_measurement_command set state_name='COMPLETED',response_json=? where tenant_id=? and command_id=?",
                json(response), tenantId, commandId);
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
    private record CorrectionTarget(String rootEventId) { }
    private record CorrectionIdentity(String type, String businessKey, String subjectHash) { }
    private record PartitionWatermark(long sourceOffset, long completeThroughEpochMillis) { }
    private record WatermarkAggregate(int partitionCount, long minimumEpochMillis) { }
    private record Fact(String eventId, String type, String businessKey, String subjectHash,
            Instant occurredAt, long revenue, long cost) { }
    private record ProjectionContribution(MarketingFact.Type type, String businessKey, String campaignId,
            String experimentId, String variantId, String subjectHash, long revenueMinor, long costMinor,
            Instant occurredAt, Instant ingestedAt) { }
    private record StoredCommand(String payloadHash, String state, String responseJson, String expiresAt) { }
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
