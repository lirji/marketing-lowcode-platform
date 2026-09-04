package com.acme.marketing.journeyservice.infrastructure;

import com.acme.marketing.contracts.release.KillSwitchDirective;
import com.acme.marketing.journeyservice.application.JourneyKillSwitchRegistry;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
public final class JourneyKillSwitchConsumer {
    private final JourneyKillSwitchRegistry switches;
    private final ObjectMapper mapper;

    public JourneyKillSwitchConsumer(JourneyKillSwitchRegistry switches, ObjectMapper mapper) {
        this.switches = switches;
        this.mapper = mapper;
    }

    @KafkaListener(topics = "${marketing.release.kill-switch-topic:mk.release.kill-switch.v1}",
            groupId = "${marketing.release.kill-switch-consumer-group:mk-journey-kill-switch-v1}",
            autoStartup = "${marketing.kafka.consumers-enabled:false}")
    public void apply(String payload) {
        try {
            switches.apply(mapper.readValue(payload, KillSwitchDirective.class));
        } catch (JacksonException malformed) {
            throw new IllegalArgumentException("journey kill switch message is invalid", malformed);
        }
    }
}
