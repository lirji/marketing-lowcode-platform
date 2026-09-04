package com.acme.marketing.benefit.infrastructure;

import com.acme.marketing.benefit.application.BenefitFundingService;
import com.acme.marketing.contracts.event.JourneyEffectCommand;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
@ConditionalOnProperty(name = "marketing.kafka.consumers-enabled", havingValue = "true")
public final class JourneyBenefitConsumer {
    private final BenefitFundingService benefits;
    private final ObjectMapper mapper;

    public JourneyBenefitConsumer(BenefitFundingService benefits, ObjectMapper mapper) {
        this.benefits = benefits;
        this.mapper = mapper;
    }

    @KafkaListener(topics = "${marketing.benefit.command-topic:mk.benefit.command.v1}",
            groupId = "${marketing.benefit.command-consumer-group:mk-benefit-command-v1}")
    public void consume(String payload) {
        try {
            benefits.grantFromJourney(mapper.readValue(payload, JourneyEffectCommand.class));
        } catch (JacksonException malformed) {
            throw new IllegalArgumentException("journey benefit command is invalid", malformed);
        }
    }
}
