package com.acme.marketing.journeyservice.application;

import static com.acme.marketing.platform.time.SqlTime.format;

import com.acme.marketing.contracts.event.JourneyEffectCommand;
import com.acme.marketing.journey.EnrollmentSnapshot;
import com.acme.marketing.journey.event.JourneyStateChangedEvent;
import com.acme.marketing.platform.crypto.Digests;
import com.acme.marketing.platform.error.ConflictException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Materializes exactly-once Flink output through idempotent Kafka coordinates and a local dispatch outbox. */
@Service
public class JourneyOutputProjector {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Clock clock;

    public JourneyOutputProjector(JdbcTemplate jdbc, ObjectMapper mapper, Clock clock) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Transactional
    public ProjectionResult project(String payload, String topic, int partition, long offset) {
        if (payload == null || payload.isBlank() || topic == null || topic.isBlank()
                || partition < 0 || offset < 0) {
            throw new IllegalArgumentException("journey output coordinate is invalid");
        }
        JsonNode root = tree(payload);
        String eventType = root.path("eventType").asString();
        if (eventType.isBlank()) throw new IllegalArgumentException("journey output eventType is required");
        String tenantId = tenant(root, eventType);
        String enrollmentId = enrollment(root, eventType);
        String payloadHash = Digests.sha256Hex(payload);
        List<Receipt> received = jdbc.query("select payload_hash,event_type from mk_journey_output_receipt where source_topic=? and source_partition=? and source_offset=?",
                (rs, rowNum) -> new Receipt(rs.getString(1), rs.getString(2)), topic, partition, offset);
        if (!received.isEmpty()) {
            Receipt prior = received.getFirst();
            if (!prior.payloadHash().equals(payloadHash) || !prior.eventType().equals(eventType)) {
                throw new ConflictException("JOURNEY_OUTPUT_OFFSET_COLLISION",
                        "journey output coordinate was reused with another payload");
            }
            return new ProjectionResult(eventType, false, true);
        }
        try {
            jdbc.update("insert into mk_journey_output_receipt(source_topic,source_partition,source_offset,tenant_id,enrollment_id,event_type,payload_hash,consumed_at) values(?,?,?,?,?,?,?,?)",
                    topic, partition, offset, tenantId, enrollmentId, eventType, payloadHash,
                    format(clock.instant()));
        } catch (DuplicateKeyException race) {
            List<Receipt> winner = jdbc.query("select payload_hash,event_type from mk_journey_output_receipt where source_topic=? and source_partition=? and source_offset=?",
                    (rs, rowNum) -> new Receipt(rs.getString(1), rs.getString(2)), topic, partition, offset);
            if (!winner.isEmpty() && winner.getFirst().payloadHash().equals(payloadHash)
                    && winner.getFirst().eventType().equals(eventType)) {
                return new ProjectionResult(eventType, false, true);
            }
            throw new ConflictException("JOURNEY_OUTPUT_OFFSET_COLLISION",
                    "journey output coordinate was concurrently reused");
        }
        switch (eventType) {
            case "JOURNEY_STATE_CHANGED" -> applyState(value(payload, JourneyStateChangedEvent.class),
                    topic, partition, offset);
            case "JOURNEY_EFFECT_COMMAND" -> applyEffect(value(payload, JourneyEffectCommand.class));
            case "JOURNEY_STATE_EXPIRED" -> jdbc.update(
                    "update mk_enrollment set status_name='EXPIRED',updated_at=? where tenant_id=? and enrollment_id=?",
                    format(clock.instant()), tenantId, enrollmentId);
            case "JOURNEY_TIMER_RETRIES_EXHAUSTED" -> jdbc.update(
                    "update mk_enrollment set status_name='FAILED',updated_at=? where tenant_id=? and enrollment_id=?",
                    format(clock.instant()), tenantId, enrollmentId);
            case "JOURNEY_EXECUTION_PAUSED" -> { /* receipt is the operational audit trail */ }
            default -> throw new IllegalArgumentException("unsupported journey output event: " + eventType);
        }
        return new ProjectionResult(eventType, true, false);
    }

    private void applyState(JourneyStateChangedEvent event, String topic, int partition, long offset) {
        EnrollmentSnapshot snapshot = event.snapshot();
        List<EnrollmentRow> rows = jdbc.query("select journey_id,journey_version,subject_token,projection_topic,projection_partition,projection_offset from mk_enrollment where tenant_id=? and enrollment_id=? for update",
                (rs, rowNum) -> new EnrollmentRow(rs.getString(1), rs.getLong(2), rs.getString(3),
                        rs.getString(4), rs.getInt(5), rs.getLong(6)),
                snapshot.tenantId(), snapshot.enrollmentId());
        if (rows.isEmpty()) {
            Integer plan = jdbc.query("select count(*) from mk_journey_definition where tenant_id=? and journey_id=? and version_no=?",
                    rs -> rs.next() ? rs.getInt(1) : 0, snapshot.tenantId(), snapshot.journeyId(),
                    snapshot.journeyVersion());
            if (plan == null || plan == 0) {
                throw new ConflictException("JOURNEY_PLAN_NOT_INSTALLED",
                        "state arrived before its signed journey plan was installed");
            }
            jdbc.update("insert into mk_enrollment(tenant_id,enrollment_id,journey_id,journey_version,subject_token,trigger_event_id,status_name,current_node_id,snapshot_json,projection_topic,projection_partition,projection_offset,created_at,updated_at) values(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    snapshot.tenantId(), snapshot.enrollmentId(), snapshot.journeyId(), snapshot.journeyVersion(),
                    snapshot.subjectToken(), event.sourceSignalId(), snapshot.status().name(),
                    snapshot.currentNodeId(), json(snapshot), topic, partition, offset,
                    format(Instant.ofEpochMilli(event.projectedAtEpochMillis())), format(snapshot.updatedAt()));
            return;
        }
        EnrollmentRow current = rows.getFirst();
        if (!current.journeyId().equals(snapshot.journeyId())
                || current.journeyVersion() != snapshot.journeyVersion()
                || !current.subjectToken().equals(snapshot.subjectToken())) {
            throw new ConflictException("JOURNEY_OUTPUT_IDENTITY_CONFLICT",
                    "projected journey state changed an immutable enrollment identity");
        }
        if (!current.topic().isBlank()
                && (!current.topic().equals(topic) || current.partition() != partition)) {
            throw new ConflictException("JOURNEY_OUTPUT_PARTITION_CHANGED",
                    "journey output topic partition count requires a controlled state migration");
        }
        if (current.partition() == partition && current.offset() >= offset) return;
        jdbc.update("update mk_enrollment set status_name=?,current_node_id=?,snapshot_json=?,projection_topic=?,projection_partition=?,projection_offset=?,updated_at=? where tenant_id=? and enrollment_id=?",
                snapshot.status().name(), snapshot.currentNodeId(), json(snapshot), topic, partition, offset,
                format(snapshot.updatedAt()), snapshot.tenantId(), snapshot.enrollmentId());
    }

    private void applyEffect(JourneyEffectCommand event) {
        List<EnrollmentIdentity> enrollments = jdbc.query("select journey_id,journey_version,subject_token from mk_enrollment where tenant_id=? and enrollment_id=?",
                (rs, rowNum) -> new EnrollmentIdentity(rs.getString(1), rs.getLong(2), rs.getString(3)),
                event.tenantId(), event.enrollmentId());
        if (enrollments.isEmpty()) {
            throw new ConflictException("JOURNEY_STATE_NOT_PROJECTED",
                    "effect cannot be dispatched before enrollment state");
        }
        EnrollmentIdentity identity = enrollments.getFirst();
        if (!identity.journeyId().equals(event.journeyId())
                || identity.journeyVersion() != event.journeyVersion()
                || !identity.subjectToken().equals(event.subjectToken())) {
            throw new ConflictException("JOURNEY_EFFECT_IDENTITY_CONFLICT",
                    "effect does not match its enrollment identity");
        }
        String commandPayload = json(event.payload());
        List<EffectRow> existing = jdbc.query("select enrollment_id,node_id,effect_type,payload_json from mk_node_effect_intent where tenant_id=? and command_id=?",
                (rs, rowNum) -> new EffectRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)),
                event.tenantId(), event.commandId());
        if (!existing.isEmpty()) {
            EffectRow current = existing.getFirst();
            if (!current.enrollmentId().equals(event.enrollmentId()) || !current.nodeId().equals(event.nodeId())
                    || !current.effectType().equals(event.effectType()) || !current.payload().equals(commandPayload)) {
                throw new ConflictException("JOURNEY_COMMAND_COLLISION",
                        "journey command id was reused with another effect");
            }
            return;
        }
        Instant now = clock.instant();
        jdbc.update("insert into mk_node_effect_intent(tenant_id,command_id,enrollment_id,node_id,effect_type,payload_json,state_name,created_at) values(?,?,?,?,?,?,?,?)",
                event.tenantId(), event.commandId(), event.enrollmentId(), event.nodeId(), event.effectType(),
                commandPayload, "PENDING", format(now));
        long sequence = nextSequence(event.tenantId(), event.enrollmentId(), now);
        jdbc.update("insert into mk_journey_dispatch_outbox(tenant_id,outbox_id,command_id,enrollment_id,destination_topic,partition_key,stream_sequence,payload_json,next_attempt_at,created_at) values(?,?,?,?,?,?,?,?,?,?)",
                event.tenantId(), UUID.randomUUID().toString(), event.commandId(), event.enrollmentId(),
                destination(event.effectType()), event.tenantId() + ':' + event.enrollmentId(), sequence,
                json(event), format(now), format(now));
    }

    private long nextSequence(String tenantId, String enrollmentId, Instant now) {
        int updated = jdbc.update("update mk_journey_dispatch_position set last_sequence=last_sequence+1,updated_at=? where tenant_id=? and enrollment_id=?",
                format(now), tenantId, enrollmentId);
        if (updated == 0) {
            try {
                jdbc.update("insert into mk_journey_dispatch_position(tenant_id,enrollment_id,last_sequence,updated_at) values(?,?,?,?)",
                        tenantId, enrollmentId, 1, format(now));
            } catch (DuplicateKeyException race) {
                jdbc.update("update mk_journey_dispatch_position set last_sequence=last_sequence+1,updated_at=? where tenant_id=? and enrollment_id=?",
                        format(now), tenantId, enrollmentId);
            }
        }
        Long sequence = jdbc.query("select last_sequence from mk_journey_dispatch_position where tenant_id=? and enrollment_id=?",
                rs -> rs.next() ? rs.getLong(1) : null, tenantId, enrollmentId);
        if (sequence == null) throw new IllegalStateException("journey dispatch sequence allocation failed");
        return sequence;
    }

    private static String destination(String effectType) {
        return switch (effectType) {
            case "SEND", "WEBHOOK" -> "mk.engagement.command.v1";
            case "GRANT" -> "mk.benefit.command.v1";
            default -> throw new IllegalArgumentException("unsupported journey effect type");
        };
    }

    private String tenant(JsonNode root, String eventType) {
        if ("JOURNEY_STATE_CHANGED".equals(eventType)) {
            return required(root.path("snapshot").path("tenantId").asString(), "snapshot.tenantId");
        }
        return required(root.path("tenantId").asString(), "tenantId");
    }

    private String enrollment(JsonNode root, String eventType) {
        if ("JOURNEY_STATE_CHANGED".equals(eventType)) {
            return required(root.path("snapshot").path("enrollmentId").asString(), "snapshot.enrollmentId");
        }
        return required(root.path("enrollmentId").asString(), "enrollmentId");
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value;
    }

    private JsonNode tree(String payload) {
        try { return mapper.readTree(payload); }
        catch (JacksonException malformed) { throw new IllegalArgumentException("journey output is invalid", malformed); }
    }

    private <T> T value(String payload, Class<T> type) {
        try { return mapper.readValue(payload, type); }
        catch (JacksonException malformed) { throw new IllegalArgumentException("journey output is invalid", malformed); }
    }

    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (JacksonException failure) { throw new IllegalArgumentException("journey output cannot be serialized", failure); }
    }

    public record ProjectionResult(String eventType, boolean applied, boolean duplicate) { }
    private record Receipt(String payloadHash, String eventType) { }
    private record EnrollmentRow(String journeyId, long journeyVersion, String subjectToken,
            String topic, int partition, long offset) { }
    private record EnrollmentIdentity(String journeyId, long journeyVersion, String subjectToken) { }
    private record EffectRow(String enrollmentId, String nodeId, String effectType, String payload) { }
}
