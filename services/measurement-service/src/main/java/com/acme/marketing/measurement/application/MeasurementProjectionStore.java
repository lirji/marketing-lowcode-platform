package com.acme.marketing.measurement.application;

import static com.acme.marketing.platform.time.SqlTime.format;

import com.acme.marketing.contracts.event.MeasurementProjectionDelta;
import com.acme.marketing.platform.crypto.Digests;
import com.acme.marketing.platform.error.ConflictException;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * 看板物化视图应用服务，供同步摄取和 Flink 投影消费者共同复用。
 *
 * <p>该类负责内容幂等与 Kafka 坐标语义，实际读写通过投影仓储端口完成。
 */
@Service
public class MeasurementProjectionStore {
    private final MeasurementProjectionRepository repository;
    private final ObjectMapper mapper;
    private final Clock clock;

    public MeasurementProjectionStore(MeasurementProjectionRepository repository, ObjectMapper mapper, Clock clock) {
        this.repository = repository;
        this.mapper = mapper;
        this.clock = clock;
    }

    /** 幂等应用一条投影增量，并识别增量标识或 Kafka 坐标冲突。 */
    @Transactional
    public ApplyResult apply(MeasurementProjectionDelta delta, ProjectionSource source) {
        ProjectionSource checked = source == null ? ProjectionSource.direct() : source;
        String payloadHash = hash(delta);
        var existing = repository.findDelta(delta.tenantId(), delta.deltaId());
        if (existing.isPresent()) return duplicateOrConflict(existing.orElseThrow(), payloadHash, checked);
        if (checked.kafkaRecord()) {
            var coordinate = repository.findPayloadHashBySource(checked.topic(), checked.partition(), checked.offset());
            if (coordinate.isPresent()) {
                if (coordinate.orElseThrow().equals(payloadHash)) return new ApplyResult(false, true);
                throw new ConflictException("PROJECTION_OFFSET_COLLISION",
                        "Kafka projection coordinate was reused with another payload");
            }
        }
        MeasurementProjectionRepository.DeltaWrite write = new MeasurementProjectionRepository.DeltaWrite(
                delta.tenantId(), delta.deltaId(), delta.rootEventId(), delta.sourceEventId(), delta.revision(),
                delta.operation().name(), delta.factType().name(), delta.businessKey(), delta.campaignId(),
                delta.experimentId(), delta.variantId(), delta.subjectHash(), delta.countDelta(),
                delta.revenueDeltaMinor(), delta.costDeltaMinor(),
                format(Instant.ofEpochMilli(delta.occurredAtEpochMillis())),
                format(Instant.ofEpochMilli(delta.ingestedAtEpochMillis())), payloadHash,
                checked.topic(), checked.partition(), checked.offset(), format(clock.instant()));
        if (repository.trySaveDelta(write)) return new ApplyResult(true, false);

        // 唯一键竞争可能来自 deltaId，也可能来自 Kafka 坐标；重新读取可给出稳定的业务结果。
        var winner = repository.findDelta(delta.tenantId(), delta.deltaId());
        if (winner.isEmpty()) {
            throw new ConflictException("PROJECTION_OFFSET_COLLISION",
                    "Kafka projection coordinate was concurrently reused");
        }
        return duplicateOrConflict(winner.orElseThrow(), payloadHash, checked);
    }

    /** 汇总指定时间范围的看板指标。 */
    public DashboardTotals totals(String tenantId, Instant from, Instant to) {
        Map<String, Long> counts = new LinkedHashMap<>();
        repository.sumCounts(tenantId, format(from), format(to)).forEach(row ->
                counts.put(row.factType(), row.countValue().longValueExact()));
        MeasurementProjectionRepository.Amounts amounts = repository.sumAmounts(
                tenantId, format(from), format(to));
        return new DashboardTotals(counts, amounts.revenue().longValueExact(), amounts.cost().longValueExact());
    }

    /** 按小时或自然日汇总看板时序指标。 */
    public Map<Instant, SeriesAmounts> series(String tenantId, Instant from, Instant to, boolean hourly) {
        Map<Instant, SeriesAmounts> points = new TreeMap<>();
        repository.sumSeries(tenantId, format(from), format(to), hourly).forEach(row -> {
            Instant at = Instant.parse(hourly
                    ? row.bucketValue() + ":00:00.000000000Z"
                    : row.bucketValue() + "T00:00:00.000000000Z");
            points.put(at, new SeriesAmounts(row.revenue().longValueExact(), row.cost().longValueExact(),
                    row.conversions().longValueExact()));
        });
        return Map.copyOf(points);
    }

    private ApplyResult duplicateOrConflict(MeasurementProjectionRepository.StoredDelta existing,
            String payloadHash, ProjectionSource source) {
        if (!existing.payloadHash().equals(payloadHash)) {
            throw new ConflictException("PROJECTION_DELTA_COLLISION",
                    "projection delta id was reused with another payload");
        }
        if (source.kafkaRecord() && existing.kafkaRecord()
                && (!existing.topic().equals(source.topic())
                    || !existing.partition().equals(source.partition())
                    || !existing.offset().equals(source.offset()))) {
            // 恢复主题可能复制同一条 exactly-once 增量，此时内容身份优先于传输坐标。
            return new ApplyResult(false, true);
        }
        return new ApplyResult(false, true);
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
}
