package com.acme.marketing.journeyservice.application;

import static com.acme.marketing.platform.time.SqlTime.format;

import com.acme.marketing.contracts.event.JourneyEffectCommand;
import com.acme.marketing.journey.EnrollmentSnapshot;
import com.acme.marketing.journey.event.JourneyStateChangedEvent;
import com.acme.marketing.platform.crypto.Digests;
import com.acme.marketing.platform.error.ConflictException;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 通过 Kafka 坐标幂等和本地 dispatch outbox 投影 Flink 旅程输出。 */
@Service
public class JourneyOutputProjector {
    private final JourneyRepository repository;
    private final ObjectMapper mapper;
    private final Clock clock;

    public JourneyOutputProjector(JourneyRepository repository, ObjectMapper mapper, Clock clock) {
        this.repository = repository;
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
        var received = repository.findOutputReceipt(topic, partition, offset);
        if (received.isPresent()) {
            JourneyRepository.OutputReceiptRow prior = received.orElseThrow();
            if (!prior.payloadHash().equals(payloadHash) || !prior.eventType().equals(eventType)) {
                throw new ConflictException("JOURNEY_OUTPUT_OFFSET_COLLISION",
                        "journey output coordinate was reused with another payload");
            }
            return new ProjectionResult(eventType, false, true);
        }
        boolean saved = repository.trySaveOutputReceipt(new JourneyRepository.OutputReceiptWrite(topic,
                partition, offset, tenantId, enrollmentId, eventType, payloadHash, format(clock.instant())));
        if (!saved) {
            var winner = repository.findOutputReceipt(topic, partition, offset);
            if (winner.isPresent() && winner.orElseThrow().payloadHash().equals(payloadHash)
                    && winner.orElseThrow().eventType().equals(eventType)) {
                return new ProjectionResult(eventType, false, true);
            }
            throw new ConflictException("JOURNEY_OUTPUT_OFFSET_COLLISION",
                    "journey output coordinate was concurrently reused");
        }
        switch (eventType) {
            case "JOURNEY_STATE_CHANGED" -> applyState(value(payload, JourneyStateChangedEvent.class),
                    topic, partition, offset);
            case "JOURNEY_EFFECT_COMMAND" -> applyEffect(value(payload, JourneyEffectCommand.class));
            case "JOURNEY_STATE_EXPIRED" -> repository.markEnrollmentExpired(
                    tenantId, enrollmentId, format(clock.instant()));
            case "JOURNEY_TIMER_RETRIES_EXHAUSTED" -> repository.markEnrollmentFailed(
                    tenantId, enrollmentId, format(clock.instant()));
            case "JOURNEY_EXECUTION_PAUSED" -> { /* receipt is the operational audit trail */ }
            default -> throw new IllegalArgumentException("unsupported journey output event: " + eventType);
        }
        return new ProjectionResult(eventType, true, false);
    }

    private void applyState(JourneyStateChangedEvent event, String topic, int partition, long offset) {
        EnrollmentSnapshot snapshot = event.snapshot();
        var currentRow = repository.findProjectedEnrollmentForUpdate(snapshot.tenantId(), snapshot.enrollmentId());
        if (currentRow.isEmpty()) {
            int plan = repository.countDefinition(
                    snapshot.tenantId(), snapshot.journeyId(), snapshot.journeyVersion());
            if (plan == 0) {
                throw new ConflictException("JOURNEY_PLAN_NOT_INSTALLED",
                        "state arrived before its signed journey plan was installed");
            }
            repository.saveProjectedEnrollment(new JourneyRepository.ProjectedEnrollmentWrite(snapshot.tenantId(),
                    snapshot.enrollmentId(), snapshot.journeyId(), snapshot.journeyVersion(), snapshot.subjectToken(),
                    event.sourceSignalId(), snapshot.status().name(), snapshot.currentNodeId(), json(snapshot),
                    topic, partition, offset, format(Instant.ofEpochMilli(event.projectedAtEpochMillis())),
                    format(snapshot.updatedAt())));
            return;
        }
        JourneyRepository.ProjectedEnrollmentRow current = currentRow.orElseThrow();
        if (!current.journeyId().equals(snapshot.journeyId())
                || current.journeyVersion() != snapshot.journeyVersion()
                || !current.subjectToken().equals(snapshot.subjectToken())) {
            throw new ConflictException("JOURNEY_OUTPUT_IDENTITY_CONFLICT",
                    "projected journey state changed an immutable enrollment identity");
        }
        if (!current.projectionTopic().isBlank()
                && (!current.projectionTopic().equals(topic) || current.projectionPartition() != partition)) {
            throw new ConflictException("JOURNEY_OUTPUT_PARTITION_CHANGED",
                    "journey output topic partition count requires a controlled state migration");
        }
        if (current.projectionPartition() == partition && current.projectionOffset() >= offset) return;
        repository.updateProjectedEnrollment(new JourneyRepository.ProjectedEnrollmentUpdate(snapshot.tenantId(),
                snapshot.enrollmentId(), snapshot.status().name(), snapshot.currentNodeId(), json(snapshot),
                topic, partition, offset, format(snapshot.updatedAt())));
    }

    private void applyEffect(JourneyEffectCommand event) {
        JourneyRepository.EnrollmentIdentityRow identity = repository.findEnrollmentIdentity(
                        event.tenantId(), event.enrollmentId())
                .orElseThrow(() -> new ConflictException("JOURNEY_STATE_NOT_PROJECTED",
                        "effect cannot be dispatched before enrollment state"));
        if (!identity.journeyId().equals(event.journeyId())
                || identity.journeyVersion() != event.journeyVersion()
                || !identity.subjectToken().equals(event.subjectToken())) {
            throw new ConflictException("JOURNEY_EFFECT_IDENTITY_CONFLICT",
                    "effect does not match its enrollment identity");
        }
        String commandPayload = json(event.payload());
        var existing = repository.findEffect(event.tenantId(), event.commandId());
        if (existing.isPresent()) {
            JourneyRepository.EffectRow current = existing.orElseThrow();
            if (!current.enrollmentId().equals(event.enrollmentId()) || !current.nodeId().equals(event.nodeId())
                    || !current.effectType().equals(event.effectType())
                    || !current.payloadJson().equals(commandPayload)) {
                throw new ConflictException("JOURNEY_COMMAND_COLLISION",
                        "journey command id was reused with another effect");
            }
            return;
        }
        Instant now = clock.instant();
        repository.saveEffectIntent(new JourneyRepository.EffectWrite(event.tenantId(), event.commandId(),
                event.enrollmentId(), event.nodeId(), event.effectType(), commandPayload, "PENDING", format(now)));
        long sequence = nextSequence(event.tenantId(), event.enrollmentId(), now);
        repository.saveDispatchOutbox(new JourneyRepository.DispatchOutboxWrite(event.tenantId(),
                UUID.randomUUID().toString(), event.commandId(), event.enrollmentId(),
                destination(event.effectType()), event.tenantId() + ':' + event.enrollmentId(), sequence,
                json(event), format(now), format(now)));
    }

    private long nextSequence(String tenantId, String enrollmentId, Instant now) {
        int updated = repository.incrementDispatchPosition(tenantId, enrollmentId, format(now));
        if (updated == 0) {
            if (!repository.tryCreateDispatchPosition(tenantId, enrollmentId, format(now))) {
                repository.incrementDispatchPosition(tenantId, enrollmentId, format(now));
            }
        }
        return repository.findDispatchSequence(tenantId, enrollmentId)
                .orElseThrow(() -> new IllegalStateException("journey dispatch sequence allocation failed"));
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
}
