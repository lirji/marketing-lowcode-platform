package com.acme.marketing.journeyservice.infrastructure;

import com.acme.marketing.contracts.release.ActivationDirective;
import com.acme.marketing.journeyservice.application.JourneyRuntimeReleaseService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
public final class JourneyReleaseActivationConsumer {
    private final JourneyRuntimeReleaseService releases;
    private final ObjectMapper mapper;

    public JourneyReleaseActivationConsumer(JourneyRuntimeReleaseService releases, ObjectMapper mapper) {
        this.releases = releases;
        this.mapper = mapper;
    }

    @KafkaListener(topics = "${marketing.release.activation-topic:mk.release.activation.v1}",
            groupId = "${marketing.release.activation-consumer-group:mk-journey-release-v1}",
            autoStartup = "${marketing.kafka.consumers-enabled:false}")
    public void activate(String payload) {
        ActivationDirective directive;
        try {
            directive = mapper.readValue(payload, ActivationDirective.class);
        } catch (JacksonException malformed) {
            throw new IllegalArgumentException("journey activation message is invalid", malformed);
        }
        if (!"journey".equals(directive.runtime())) return;
        releases.activateFromCoordinator(directive);
    }
}
