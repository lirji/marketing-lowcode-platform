package com.acme.marketing.eventgateway.application;

import static com.acme.marketing.platform.time.SqlTime.format;

import com.acme.marketing.platform.crypto.Digests;
import com.acme.marketing.platform.error.ConflictException;
import com.acme.marketing.platform.error.NotFoundException;
import com.acme.marketing.platform.identity.TenantId;
import com.acme.marketing.platform.isolation.TenantRateLimiter;
import com.acme.marketing.platform.web.TenantContextHolder;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.TreeMap;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class EventIngestionService {
    private static final int MAX_EVENT_BYTES = 262_144;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final TenantRateLimiter rateLimiter;
    private final EventPayloadRouter payloadRouter;

    public EventIngestionService(JdbcTemplate jdbc, ObjectMapper mapper, Clock clock,
            EventPayloadRouter payloadRouter) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.clock = clock;
        this.rateLimiter = new TenantRateLimiter(clock, 10_000);
        this.payloadRouter = payloadRouter;
    }

    @Transactional
    public SourceView registerSource(RegisterSourceRequest request) {
        var scope = TenantContextHolder.requireCurrent();
        scope.requirePermission("event-source:write");
        URI.create(request.sourceUri());
        jdbc.update("insert into mk_source_registration(tenant_id,source_id,source_uri,source_uri_hash,allowed_types,schema_versions,max_lateness_seconds,enabled_value,created_at) values(?,?,?,?,?,?,?,?,?)",
                scope.tenantId().value(), request.sourceId(), request.sourceUri(), Digests.sha256Hex(request.sourceUri()),
                String.join(",", request.allowedTypes()),
                String.join(",", request.schemaVersions()), request.maxLatenessSeconds(), true, format(clock.instant()));
        return new SourceView(request.sourceId(), request.sourceUri(), request.allowedTypes(),
                request.schemaVersions(), request.maxLatenessSeconds(), true);
    }

    @Transactional
    public Receipt ingest(InboundEvent event) {
        var scope = TenantContextHolder.requireCurrent();
        scope.requirePermission("event:ingest");
        rateLimiter.acquire(scope.tenantId());
        validateShape(event);
        String tenantId = scope.tenantId().value();
        String payloadHash = eventPayloadHash(event);
        List<StoredReceipt> duplicate = storedReceipt(tenantId, event.sourceId(), event.eventId());
        if (!duplicate.isEmpty()) {
            StoredReceipt stored = duplicate.getFirst();
            if (!stored.payloadHash().equals(payloadHash)) {
                throw new ConflictException("EVENT_ID_COLLISION", "event id was used with another payload");
            }
            Receipt original = stored.receipt();
            return new Receipt(original.receiptId(), event.eventId(), Status.DUPLICATE,
                    "DUPLICATE_EVENT", original.ingestedAt());
        }
        Source source = source(tenantId, event.sourceId());
        Instant now = clock.instant();
        String reason = validateAgainstSource(source, event, now);
        if (reason == null && event.aggregateVersion() != null) {
            reason = validateSequence(tenantId, event);
        }
        Status status = reason == null ? Status.ACCEPTED : Status.QUARANTINED;
        String receiptId = UUID.randomUUID().toString();
        String eventJson = json(event);
        try {
            jdbc.update("insert into mk_event_receipt(tenant_id,receipt_id,source_id,event_id,event_type,business_key,aggregate_version,status_name,reason_code,payload_hash,event_json,occurred_at,ingested_at) values(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    tenantId, receiptId, event.sourceId(), event.eventId(), event.eventType(), event.businessKey(),
                    event.aggregateVersion(), status.name(), reason == null ? "" : reason, payloadHash, eventJson,
                    format(event.occurredAt()), format(now));
        } catch (DuplicateKeyException race) {
            StoredReceipt winner = storedReceipt(tenantId, event.sourceId(), event.eventId()).stream()
                    .findFirst().orElseThrow(() -> race);
            if (!winner.payloadHash().equals(payloadHash)) {
                throw new ConflictException("EVENT_ID_COLLISION", "event id was used with another payload");
            }
            return new Receipt(winner.receipt().receiptId(), event.eventId(), Status.DUPLICATE,
                    "DUPLICATE_EVENT", winner.receipt().ingestedAt());
        }
        if (status == Status.QUARANTINED) {
            jdbc.update("insert into mk_quarantine(tenant_id,quarantine_id,receipt_id,reason_code,event_json,state_name,created_at) values(?,?,?,?,?,?,?)",
                    tenantId, UUID.randomUUID().toString(), receiptId, reason, eventJson, "OPEN", format(now));
        } else {
            accept(tenantId, event, receiptId, now);
        }
        return new Receipt(receiptId, event.eventId(), status, reason == null ? "" : reason, now);
    }

    @Transactional
    public Receipt replay(String quarantineId) {
        var scope = TenantContextHolder.requireCurrent();
        scope.requirePermission("event:replay");
        String tenantId = scope.tenantId().value();
        List<Quarantine> rows = jdbc.query(
                "select q.receipt_id,q.event_json,q.state_name from mk_quarantine q where q.tenant_id=? and q.quarantine_id=? for update",
                (rs, rowNum) -> new Quarantine(rs.getString(1), rs.getString(2), rs.getString(3)),
                tenantId, quarantineId);
        if (rows.isEmpty()) throw new NotFoundException("QUARANTINE_NOT_FOUND", "quarantine item not found");
        Quarantine row = rows.getFirst();
        if (!"OPEN".equals(row.state())) throw new ConflictException("QUARANTINE_CLOSED", "item was already replayed");
        InboundEvent event = read(row.eventJson());
        Source source = source(tenantId, event.sourceId());
        String reason = validateAgainstSource(source, event, clock.instant());
        if (reason != null && !"EVENT_TOO_LATE".equals(reason)) {
            throw new ConflictException("REPLAY_STILL_INVALID", reason);
        }
        if (event.aggregateVersion() != null) {
            String sequenceReason = validateSequence(tenantId, event);
            if (sequenceReason != null) throw new ConflictException("REPLAY_STILL_INVALID", sequenceReason);
        }
        Instant now = clock.instant();
        accept(tenantId, event, row.receiptId(), now);
        jdbc.update("update mk_quarantine set state_name='REPLAYED',replayed_at=? where tenant_id=? and quarantine_id=?",
                format(now), tenantId, quarantineId);
        jdbc.update("update mk_event_receipt set status_name=?,reason_code='' where tenant_id=? and receipt_id=?",
                Status.REPLAYED.name(), tenantId, row.receiptId());
        return new Receipt(row.receiptId(), event.eventId(), Status.REPLAYED, "", now);
    }

    public List<QuarantineView> quarantine() {
        var scope = TenantContextHolder.requireCurrent();
        scope.requirePermission("event:read");
        return jdbc.query("select quarantine_id,receipt_id,reason_code,state_name,created_at,replayed_at from mk_quarantine where tenant_id=? order by created_at desc",
                (rs, rowNum) -> new QuarantineView(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), Instant.parse(rs.getString(5)),
                        rs.getString(6) == null ? null : Instant.parse(rs.getString(6))), scope.tenantId().value());
    }

    private void accept(String tenantId, InboundEvent event, String receiptId, Instant now) {
        if (event.aggregateVersion() != null) {
            int updated = jdbc.update("update mk_event_sequence set last_version=?,updated_at=? where tenant_id=? and source_id=? and business_key=?",
                    event.aggregateVersion(), format(now), tenantId, event.sourceId(), event.businessKey());
            if (updated == 0) {
                jdbc.update("insert into mk_event_sequence(tenant_id,source_id,business_key,last_version,updated_at) values(?,?,?,?,?)",
                        tenantId, event.sourceId(), event.businessKey(), event.aggregateVersion(), format(now));
            }
        }
        EventPayloadRouter.OutboundEvent outbound = payloadRouter.route(tenantId, receiptId, event, now);
        long streamSequence = nextStreamSequence(tenantId, outbound.topic(), outbound.partitionKey(), now);
        jdbc.update("insert into mk_event_outbox(tenant_id,outbox_id,receipt_id,event_type,destination_topic,partition_key,stream_sequence,payload_json,created_at,next_attempt_at) values(?,?,?,?,?,?,?,?,?,?)",
                tenantId, UUID.randomUUID().toString(), receiptId, event.eventType(), outbound.topic(),
                outbound.partitionKey(), streamSequence, outbound.payload(), format(now), format(now));
    }

    private long nextStreamSequence(String tenantId, String topic, String partitionKey, Instant now) {
        int updated = jdbc.update("update mk_event_stream_position set last_sequence=last_sequence+1,updated_at=? where tenant_id=? and destination_topic=? and partition_key=?",
                format(now), tenantId, topic, partitionKey);
        if (updated == 0) {
            try {
                jdbc.update("insert into mk_event_stream_position(tenant_id,destination_topic,partition_key,last_sequence,updated_at) values(?,?,?,?,?)",
                        tenantId, topic, partitionKey, 1, format(now));
            } catch (DuplicateKeyException concurrentCreator) {
                jdbc.update("update mk_event_stream_position set last_sequence=last_sequence+1,updated_at=? where tenant_id=? and destination_topic=? and partition_key=?",
                        format(now), tenantId, topic, partitionKey);
            }
        }
        Long sequence = jdbc.query("select last_sequence from mk_event_stream_position where tenant_id=? and destination_topic=? and partition_key=?",
                rs -> rs.next() ? rs.getLong(1) : null, tenantId, topic, partitionKey);
        if (sequence == null) throw new IllegalStateException("event stream sequence allocation failed");
        return sequence;
    }

    private String validateSequence(String tenantId, InboundEvent event) {
        Long last = jdbc.query("select last_version from mk_event_sequence where tenant_id=? and source_id=? and business_key=? for update",
                rs -> rs.next() ? rs.getLong(1) : null, tenantId, event.sourceId(), event.businessKey());
        if (last == null && event.aggregateVersion() > 1) return "AGGREGATE_VERSION_GAP";
        if (last != null && event.aggregateVersion() <= last) return "OUT_OF_ORDER_VERSION";
        if (last != null && event.aggregateVersion() > last + 1) return "AGGREGATE_VERSION_GAP";
        return null;
    }

    private Source source(String tenantId, String sourceId) {
        List<Source> sources = jdbc.query("select source_uri,allowed_types,schema_versions,max_lateness_seconds,enabled_value from mk_source_registration where tenant_id=? and source_id=?",
                (rs, rowNum) -> new Source(rs.getString(1), csv(rs.getString(2)), csv(rs.getString(3)),
                        rs.getLong(4), rs.getBoolean(5)), tenantId, sourceId);
        if (sources.isEmpty()) throw new NotFoundException("EVENT_SOURCE_NOT_FOUND", "event source is not registered");
        return sources.getFirst();
    }

    private List<StoredReceipt> storedReceipt(String tenantId, String sourceId, String eventId) {
        return jdbc.query("select receipt_id,status_name,reason_code,ingested_at,payload_hash from mk_event_receipt where tenant_id=? and source_id=? and event_id=?",
                (rs, rowNum) -> new StoredReceipt(new Receipt(rs.getString("receipt_id"), eventId,
                        Status.valueOf(rs.getString("status_name")), rs.getString("reason_code"),
                        Instant.parse(rs.getString("ingested_at"))), rs.getString("payload_hash")),
                tenantId, sourceId, eventId);
    }

    private String eventPayloadHash(InboundEvent event) {
        return Digests.sha256Hex(String.join("|", event.sourceId(), event.eventType(), event.businessKey(),
                event.subjectToken(), event.occurredAt().toString(), event.schemaVersion(),
                event.aggregateVersion() == null ? "" : event.aggregateVersion().toString(),
                json(canonical(event.data()))));
    }

    private static String validateAgainstSource(Source source, InboundEvent event, Instant now) {
        if (!source.enabled()) return "SOURCE_DISABLED";
        if (!source.allowedTypes().contains(event.eventType())) return "EVENT_TYPE_FORBIDDEN";
        if (!source.schemaVersions().contains(event.schemaVersion())) return "UNKNOWN_SCHEMA_VERSION";
        if (event.occurredAt().isAfter(now.plus(Duration.ofMinutes(5)))) return "EVENT_TIME_IN_FUTURE";
        if (event.occurredAt().isBefore(now.minusSeconds(source.maxLatenessSeconds()))) return "EVENT_TOO_LATE";
        return null;
    }

    private void validateShape(InboundEvent event) {
        if (event.eventId() == null || !event.eventId().matches("[a-zA-Z0-9_.:-]{1,128}")
                || event.sourceId() == null || event.eventType() == null || event.businessKey() == null
                || event.subjectToken() == null || event.occurredAt() == null || event.schemaVersion() == null) {
            throw new IllegalArgumentException("event envelope is incomplete");
        }
        if (json(event).getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_EVENT_BYTES) {
            throw new IllegalArgumentException("event exceeds 256 KiB");
        }
    }

    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (JacksonException failure) { throw new IllegalArgumentException("event cannot be serialized", failure); }
    }

    private static Object canonical(Object value) {
        if (value instanceof Map<?, ?> source) {
            TreeMap<String, Object> sorted = new TreeMap<>();
            source.forEach((key, item) -> sorted.put(String.valueOf(key), canonical(item)));
            return Collections.unmodifiableMap(sorted);
        }
        if (value instanceof Collection<?> source) {
            List<Object> ordered = new ArrayList<>(source.size());
            source.forEach(item -> ordered.add(canonical(item)));
            return List.copyOf(ordered);
        }
        return value;
    }

    private InboundEvent read(String value) {
        try { return mapper.readValue(value, InboundEvent.class); }
        catch (JacksonException failure) { throw new IllegalStateException("stored event is invalid", failure); }
    }

    private static Set<String> csv(String value) {
        return value == null || value.isBlank() ? Set.of() : Set.copyOf(Arrays.asList(value.split(",")));
    }

    public enum Status { ACCEPTED, DUPLICATE, QUARANTINED, REPLAYED }
    private record Source(String sourceUri, Set<String> allowedTypes, Set<String> schemaVersions,
            long maxLatenessSeconds, boolean enabled) { }
    private record StoredReceipt(Receipt receipt, String payloadHash) { }
    private record Quarantine(String receiptId, String eventJson, String state) { }
    public record InboundEvent(String eventId, String sourceId, String eventType, String businessKey,
            String subjectToken, Instant occurredAt, String schemaVersion, Long aggregateVersion,
            Map<String, Object> data) {
        public InboundEvent { data = Map.copyOf(data == null ? Map.of() : data); }
    }
    public record RegisterSourceRequest(String sourceId, String sourceUri, Set<String> allowedTypes,
            Set<String> schemaVersions, long maxLatenessSeconds) {
        public RegisterSourceRequest {
            if (sourceId == null || !sourceId.matches("[a-zA-Z0-9_.:-]{1,128}")
                    || sourceUri == null || sourceUri.isBlank() || sourceUri.length() > 1_000) {
                throw new IllegalArgumentException("event source identity is invalid");
            }
            allowedTypes = Set.copyOf(allowedTypes);
            schemaVersions = Set.copyOf(schemaVersions);
            if (maxLatenessSeconds < 0) throw new IllegalArgumentException("lateness cannot be negative");
        }
    }
    public record SourceView(String sourceId, String sourceUri, Set<String> allowedTypes,
            Set<String> schemaVersions, long maxLatenessSeconds, boolean enabled) { }
    public record Receipt(String receiptId, String eventId, Status status, String reasonCode, Instant ingestedAt) { }
    public record QuarantineView(String quarantineId, String receiptId, String reasonCode, String state,
            Instant createdAt, Instant replayedAt) { }
}
