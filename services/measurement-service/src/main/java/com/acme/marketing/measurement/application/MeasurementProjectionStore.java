package com.acme.marketing.measurement.application;

import static com.acme.marketing.platform.time.SqlTime.format;

import com.acme.marketing.contracts.event.MeasurementProjectionDelta;
import com.acme.marketing.platform.crypto.Digests;
import com.acme.marketing.platform.error.ConflictException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/** Idempotent materialized view shared by direct ingestion and the Flink projection consumer. */
@Service
public class MeasurementProjectionStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Clock clock;

    public MeasurementProjectionStore(JdbcTemplate jdbc, ObjectMapper mapper, Clock clock) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Transactional
    public ApplyResult apply(MeasurementProjectionDelta delta, ProjectionSource source) {
        ProjectionSource checked = source == null ? ProjectionSource.direct() : source;
        String payloadHash = hash(delta);
        List<StoredDelta> existing = stored(delta.tenantId(), delta.deltaId());
        if (!existing.isEmpty()) return duplicateOrConflict(existing.getFirst(), payloadHash, checked);
        if (checked.kafkaRecord()) {
            List<String> coordinate = jdbc.query(
                    "select payload_hash from mk_dashboard_projection_delta where source_topic=? and source_partition=? and source_offset=?",
                    (rs, rowNum) -> rs.getString(1), checked.topic(), checked.partition(), checked.offset());
            if (!coordinate.isEmpty()) {
                if (coordinate.getFirst().equals(payloadHash)) return new ApplyResult(false, true);
                throw new ConflictException("PROJECTION_OFFSET_COLLISION",
                        "Kafka projection coordinate was reused with another payload");
            }
        }
        try {
            jdbc.update("insert into mk_dashboard_projection_delta(tenant_id,delta_id,root_event_id,source_event_id,revision_no,operation_name,fact_type,business_key,campaign_id,experiment_id,variant_id,subject_hash,count_delta,revenue_delta_minor,cost_delta_minor,occurred_at,ingested_at,payload_hash,source_topic,source_partition,source_offset,projected_at) values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    delta.tenantId(), delta.deltaId(), delta.rootEventId(), delta.sourceEventId(),
                    delta.revision(), delta.operation().name(), delta.factType().name(), delta.businessKey(),
                    delta.campaignId(), delta.experimentId(), delta.variantId(), delta.subjectHash(),
                    delta.countDelta(), delta.revenueDeltaMinor(), delta.costDeltaMinor(),
                    format(Instant.ofEpochMilli(delta.occurredAtEpochMillis())),
                    format(Instant.ofEpochMilli(delta.ingestedAtEpochMillis())), payloadHash,
                    checked.topic(), checked.partition(), checked.offset(), format(clock.instant()));
            return new ApplyResult(true, false);
        } catch (DuplicateKeyException race) {
            List<StoredDelta> winner = stored(delta.tenantId(), delta.deltaId());
            if (winner.isEmpty()) {
                throw new ConflictException("PROJECTION_OFFSET_COLLISION",
                        "Kafka projection coordinate was concurrently reused");
            }
            return duplicateOrConflict(winner.getFirst(), payloadHash, checked);
        }
    }

    public DashboardTotals totals(String tenantId, Instant from, Instant to) {
        Map<String, Long> counts = new LinkedHashMap<>();
        jdbc.query("select fact_type,sum(count_delta) from mk_dashboard_projection_delta where tenant_id=? and occurred_at>=? and occurred_at<? group by fact_type having sum(count_delta)<>0",
                rs -> { counts.put(rs.getString(1), rs.getBigDecimal(2).longValueExact()); },
                tenantId, format(from), format(to));
        Amounts amounts = jdbc.query("select coalesce(sum(cast(revenue_delta_minor as decimal(65,0))),0),coalesce(sum(cast(cost_delta_minor as decimal(65,0))),0) from mk_dashboard_projection_delta where tenant_id=? and occurred_at>=? and occurred_at<?",
                rs -> rs.next() ? new Amounts(rs.getBigDecimal(1), rs.getBigDecimal(2))
                        : new Amounts(BigDecimal.ZERO, BigDecimal.ZERO),
                tenantId, format(from), format(to));
        return new DashboardTotals(counts, amounts.revenue().longValueExact(), amounts.cost().longValueExact());
    }

    public Map<Instant, SeriesAmounts> series(String tenantId, Instant from, Instant to, boolean hourly) {
        int prefixLength = hourly ? 13 : 10;
        Map<Instant, SeriesAmounts> points = new TreeMap<>();
        jdbc.query("select substring(occurred_at,1," + prefixLength + "),"
                        + "coalesce(sum(cast(revenue_delta_minor as decimal(65,0))),0),"
                        + "coalesce(sum(cast(cost_delta_minor as decimal(65,0))),0),"
                        + "coalesce(sum(case when fact_type='CONVERSION' then count_delta else 0 end),0) "
                        + "from mk_dashboard_projection_delta where tenant_id=? and occurred_at>=? and occurred_at<? "
                        + "group by substring(occurred_at,1," + prefixLength + ") order by 1",
                rs -> {
                    String bucket = rs.getString(1);
                    Instant at = Instant.parse(hourly
                            ? bucket + ":00:00.000000000Z" : bucket + "T00:00:00.000000000Z");
                    points.put(at, new SeriesAmounts(rs.getBigDecimal(2).longValueExact(),
                            rs.getBigDecimal(3).longValueExact(), rs.getBigDecimal(4).longValueExact()));
                }, tenantId, format(from), format(to));
        return Map.copyOf(points);
    }

    private ApplyResult duplicateOrConflict(StoredDelta existing, String payloadHash, ProjectionSource source) {
        if (!existing.payloadHash().equals(payloadHash)) {
            throw new ConflictException("PROJECTION_DELTA_COLLISION",
                    "projection delta id was reused with another payload");
        }
        if (source.kafkaRecord() && existing.kafkaRecord()
                && (!existing.topic().equals(source.topic())
                    || !existing.partition().equals(source.partition())
                    || !existing.offset().equals(source.offset()))) {
            // The same exactly-once Flink delta may be copied to a recovery topic. Its content identity wins.
            return new ApplyResult(false, true);
        }
        return new ApplyResult(false, true);
    }

    private List<StoredDelta> stored(String tenantId, String deltaId) {
        return jdbc.query("select payload_hash,source_topic,source_partition,source_offset from mk_dashboard_projection_delta where tenant_id=? and delta_id=?",
                (rs, rowNum) -> new StoredDelta(rs.getString(1), rs.getString(2),
                        (Integer) rs.getObject(3), (Long) rs.getObject(4)), tenantId, deltaId);
    }

    private String hash(MeasurementProjectionDelta delta) {
        try {
            return Digests.sha256Hex(mapper.writeValueAsString(delta));
        } catch (JacksonException failure) {
            throw new IllegalArgumentException("measurement projection cannot be serialized", failure);
        }
    }

    public record ProjectionSource(String topic, Integer partition, Long offset) {
        public ProjectionSource {
            topic = topic == null ? "direct-api" : topic;
            if (topic.isBlank() || (partition == null) != (offset == null)
                    || partition != null && (partition < 0 || offset < 0)) {
                throw new IllegalArgumentException("projection source coordinate is invalid");
            }
        }

        public static ProjectionSource direct() {
            return new ProjectionSource("direct-api", null, null);
        }

        boolean kafkaRecord() {
            return partition != null;
        }
    }

    public record ApplyResult(boolean applied, boolean duplicate) { }
    public record DashboardTotals(Map<String, Long> counts, long revenueMinor, long costMinor) {
        public DashboardTotals { counts = Map.copyOf(counts); }
    }
    public record SeriesAmounts(long revenueMinor, long costMinor, long conversions) { }
    private record Amounts(BigDecimal revenue, BigDecimal cost) { }
    private record StoredDelta(String payloadHash, String topic, Integer partition, Long offset) {
        boolean kafkaRecord() { return partition != null; }
    }
}
