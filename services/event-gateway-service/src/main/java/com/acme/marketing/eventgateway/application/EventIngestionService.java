package com.acme.marketing.eventgateway.application;

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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class EventIngestionService {
    private static final int MAX_EVENT_BYTES = 262_144;
    private final EventIngestionRepository repository;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final TenantRateLimiter rateLimiter;
    private final EventPayloadRouter payloadRouter;
    private final EventAdmissionControl admissionControl;
    private final EventOutboxDepthRepository outboxDepth;

    public EventIngestionService(EventIngestionRepository repository, ObjectMapper mapper, Clock clock,
            EventPayloadRouter payloadRouter, EventAdmissionControl admissionControl,
            EventOutboxDepthRepository outboxDepth) {
        this.repository = repository;
        this.mapper = mapper;
        this.clock = clock;
        this.rateLimiter = new TenantRateLimiter(clock, 10_000);
        this.payloadRouter = payloadRouter;
        this.admissionControl = admissionControl;
        this.outboxDepth = outboxDepth;
    }

    @Transactional
    public SourceView registerSource(RegisterSourceRequest request) {
        var scope = TenantContextHolder.requireCurrent();
        scope.requirePermission("event-source:write");
        URI.create(request.sourceUri());
        repository.insertSource(new EventIngestionRepository.SourceWrite(scope.tenantId().value(),
                request.sourceId(), request.sourceUri(), Digests.sha256Hex(request.sourceUri()),
                String.join(",", request.allowedTypes()), String.join(",", request.schemaVersions()),
                request.maxLatenessSeconds(), true, clock.instant()));
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
        StoredReceipt stored = storedReceipt(tenantId, event.sourceId(), event.eventId());
        if (stored != null) {
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
        if (reason == null) {
            // 只有会进入 outbox 的候选事件需要 Kafka backlog 门禁；已落库重复请求已在上方重放。
            admissionControl.assertWritable(tenantId);
        }
        if (reason == null && event.aggregateVersion() != null) {
            reason = validateSequence(tenantId, event);
        }
        Status status = reason == null ? Status.ACCEPTED : Status.QUARANTINED;
        String receiptId = UUID.randomUUID().toString();
        String eventJson = json(event);
        boolean inserted = repository.insertReceipt(new EventIngestionRepository.ReceiptWrite(tenantId,
                receiptId, event.sourceId(), event.eventId(), event.eventType(), event.businessKey(),
                event.aggregateVersion(), status.name(), reason == null ? "" : reason, payloadHash,
                eventJson, event.occurredAt(), now));
        if (!inserted) {
            StoredReceipt winner = storedReceipt(tenantId, event.sourceId(), event.eventId());
            if (winner == null) throw new IllegalStateException("concurrent event receipt cannot be loaded");
            if (!winner.payloadHash().equals(payloadHash)) {
                throw new ConflictException("EVENT_ID_COLLISION", "event id was used with another payload");
            }
            return new Receipt(winner.receipt().receiptId(), event.eventId(), Status.DUPLICATE,
                    "DUPLICATE_EVENT", winner.receipt().ingestedAt());
        }
        if (status == Status.QUARANTINED) {
            repository.insertQuarantine(new EventIngestionRepository.QuarantineWrite(tenantId,
                    UUID.randomUUID().toString(), receiptId, reason, eventJson, "OPEN", now));
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
        EventIngestionRepository.QuarantineRecord row = repository.lockQuarantine(tenantId, quarantineId)
                .orElseThrow(() -> new NotFoundException("QUARANTINE_NOT_FOUND", "quarantine item not found"));
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
        admissionControl.assertWritable(tenantId);
        accept(tenantId, event, row.receiptId(), now);
        repository.markQuarantineReplayed(tenantId, quarantineId, now);
        repository.markReceiptReplayed(tenantId, row.receiptId());
        return new Receipt(row.receiptId(), event.eventId(), Status.REPLAYED, "", now);
    }

    public List<QuarantineView> quarantine() {
        var scope = TenantContextHolder.requireCurrent();
        scope.requirePermission("event:read");
        return repository.findQuarantine(scope.tenantId().value()).stream()
                .map(row -> new QuarantineView(row.quarantineId(), row.receiptId(), row.reasonCode(),
                        row.state(), row.createdAt(), row.replayedAt()))
                .toList();
    }

    private void accept(String tenantId, InboundEvent event, String receiptId, Instant now) {
        if (event.aggregateVersion() != null) {
            boolean updated = repository.updateAggregateVersion(tenantId, event.sourceId(), event.businessKey(),
                    event.aggregateVersion(), now);
            if (!updated) {
                repository.insertAggregateVersion(tenantId, event.sourceId(), event.businessKey(),
                        event.aggregateVersion(), now);
            }
        }
        EventPayloadRouter.OutboundEvent outbound = payloadRouter.route(tenantId, receiptId, event, now);
        long streamSequence = nextStreamSequence(tenantId, outbound.topic(), outbound.partitionKey(), now);
        String outboxId = UUID.randomUUID().toString();
        int depthBucketId = outboxDepth.bucketId(outboxId);
        repository.insertOutbox(new EventIngestionRepository.OutboxWrite(tenantId, outboxId, receiptId,
                event.eventType(), outbound.topic(), outbound.partitionKey(), streamSequence,
                outbound.payload(), now, depthBucketId));
        outboxDepth.increment(tenantId, depthBucketId, now);
    }

    private long nextStreamSequence(String tenantId, String topic, String partitionKey, Instant now) {
        boolean updated = repository.incrementStreamSequence(tenantId, topic, partitionKey, now);
        if (!updated && !repository.insertInitialStreamSequence(tenantId, topic, partitionKey, now)) {
            repository.incrementStreamSequence(tenantId, topic, partitionKey, now);
        }
        return repository.findStreamSequence(tenantId, topic, partitionKey)
                .orElseThrow(() -> new IllegalStateException("event stream sequence allocation failed"));
    }

    private String validateSequence(String tenantId, InboundEvent event) {
        Long last = repository.lockLastAggregateVersion(tenantId, event.sourceId(), event.businessKey())
                .orElse(null);
        if (last == null && event.aggregateVersion() > 1) return "AGGREGATE_VERSION_GAP";
        if (last != null && event.aggregateVersion() <= last) return "OUT_OF_ORDER_VERSION";
        if (last != null && event.aggregateVersion() > last + 1) return "AGGREGATE_VERSION_GAP";
        return null;
    }

    private Source source(String tenantId, String sourceId) {
        EventIngestionRepository.SourceRecord source = repository.findSource(tenantId, sourceId)
                .orElseThrow(() -> new NotFoundException("EVENT_SOURCE_NOT_FOUND", "event source is not registered"));
        return new Source(source.sourceUri(), csv(source.allowedTypes()), csv(source.schemaVersions()),
                source.maxLatenessSeconds(), source.enabled());
    }

    private StoredReceipt storedReceipt(String tenantId, String sourceId, String eventId) {
        return repository.findReceipt(tenantId, sourceId, eventId)
                .map(row -> new StoredReceipt(new Receipt(row.receiptId(), eventId,
                        Status.valueOf(row.status()), row.reasonCode(), row.ingestedAt()), row.payloadHash()))
                .orElse(null);
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
