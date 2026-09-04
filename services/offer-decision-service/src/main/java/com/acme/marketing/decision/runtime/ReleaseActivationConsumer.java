package com.acme.marketing.decision.runtime;

import com.acme.marketing.contracts.release.ActivationDirective;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/** Applies durable control-plane activation notifications to the shared runtime state store. */
@Component
public final class ReleaseActivationConsumer {
    private final RuntimeManifestRegistry manifests;
    private final ObjectMapper mapper;

    public ReleaseActivationConsumer(RuntimeManifestRegistry manifests, ObjectMapper mapper) {
        this.manifests = manifests;
        this.mapper = mapper;
    }

    @KafkaListener(topics = "${marketing.release.activation-topic:mk.release.activation.v1}",
            groupId = "${marketing.release.activation-consumer-group:mk-decision-release-v1}",
            autoStartup = "${marketing.kafka.consumers-enabled:false}")
    public void activate(String payload) {
        ActivationDirective directive;
        try {
            directive = mapper.readValue(payload, ActivationDirective.class);
        } catch (JacksonException malformed) {
            throw new IllegalArgumentException("release activation message is invalid", malformed);
        }
        if (!"decision".equals(directive.runtime())) return;
        manifests.applyActivationNotification(directive.tenantId().value(), directive);
    }
}
