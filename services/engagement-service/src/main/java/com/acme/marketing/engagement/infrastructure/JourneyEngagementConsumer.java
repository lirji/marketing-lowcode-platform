package com.acme.marketing.engagement.infrastructure;

import com.acme.marketing.contracts.event.JourneyEffectCommand;
import com.acme.marketing.engagement.application.EngagementService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
@ConditionalOnProperty(name = "marketing.kafka.consumers-enabled", havingValue = "true")
public final class JourneyEngagementConsumer {
    private final EngagementService engagement;
    private final ObjectMapper mapper;

    public JourneyEngagementConsumer(EngagementService engagement, ObjectMapper mapper) {
        this.engagement = engagement;
        this.mapper = mapper;
    }

    @KafkaListener(topics = "${marketing.engagement.command-topic:mk.engagement.command.v1}",
            groupId = "${marketing.engagement.command-consumer-group:mk-engagement-command-v1}")
    public void consume(String payload) {
        try {
            engagement.handleJourneyCommand(mapper.readValue(payload, JourneyEffectCommand.class));
        } catch (JacksonException malformed) {
            throw new IllegalArgumentException("journey engagement command is invalid", malformed);
        }
    }
}
