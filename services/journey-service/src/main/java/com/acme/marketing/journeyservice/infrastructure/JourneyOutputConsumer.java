package com.acme.marketing.journeyservice.infrastructure;

import com.acme.marketing.journeyservice.application.JourneyOutputProjector;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "marketing.kafka.consumers-enabled", havingValue = "true")
public final class JourneyOutputConsumer {
    private final JourneyOutputProjector projector;

    public JourneyOutputConsumer(JourneyOutputProjector projector) {
        this.projector = projector;
    }

    @KafkaListener(topics = "${marketing.journey.output-topic:mk.journey.output.v1}",
            groupId = "${marketing.journey.output-consumer-group:mk-journey-materializer-v1}")
    public void consume(ConsumerRecord<String, String> record) {
        projector.project(record.value(), record.topic(), record.partition(), record.offset());
    }
}
