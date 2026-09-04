package com.acme.marketing.decision.runtime;

import java.time.Duration;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
public final class AudienceMembershipConsumer {
    private final AudienceMembershipProjection projection;
    private final ObjectMapper mapper;
    private final Duration membershipTtl;

    public AudienceMembershipConsumer(AudienceMembershipProjection projection, ObjectMapper mapper,
            @Value("${marketing.audience-projection.ttl-seconds:2592000}") long ttlSeconds) {
        this.projection = projection;
        this.mapper = mapper;
        if (ttlSeconds < 60) throw new IllegalArgumentException("audience projection TTL is too small");
        this.membershipTtl = Duration.ofSeconds(ttlSeconds);
    }

    @KafkaListener(topics = "${marketing.audience-projection.topic:mk.audience.membership.v1}",
            autoStartup = "${marketing.kafka.consumers-enabled:false}")
    public void consume(String payload) {
        MembershipDelta delta;
        try {
            delta = mapper.readValue(payload, MembershipDelta.class);
        } catch (JacksonException failure) {
            throw new IllegalArgumentException("audience membership event is invalid", failure);
        }
        if (!"AUDIENCE_MEMBERSHIP_DELTA".equals(delta.eventType()) || delta.occurredAtEpochMillis() < 1) {
            throw new IllegalArgumentException("audience membership event contract is invalid");
        }
        Instant occurredAt = Instant.ofEpochMilli(delta.occurredAtEpochMillis());
        projection.update(delta.tenantId(), delta.segmentId(), delta.subjectToken(), delta.member(),
                delta.membershipVersion(), occurredAt.plus(membershipTtl));
    }

    public record MembershipDelta(String eventType, String tenantId, String subjectToken, String segmentId,
            boolean member, long membershipVersion, long sourceVersion, long occurredAtEpochMillis) { }
}
