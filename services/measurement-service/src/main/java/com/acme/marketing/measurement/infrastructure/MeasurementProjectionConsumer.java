package com.acme.marketing.measurement.infrastructure;

import com.acme.marketing.contracts.event.MeasurementProjectionDelta;
import com.acme.marketing.measurement.application.MeasurementProjectionStore;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
@ConditionalOnProperty(name = "marketing.kafka.consumers-enabled", havingValue = "true")
public final class MeasurementProjectionConsumer {
    private final MeasurementProjectionStore projection;
    private final ObjectMapper mapper;

    public MeasurementProjectionConsumer(MeasurementProjectionStore projection, ObjectMapper mapper) {
        this.projection = projection;
        this.mapper = mapper;
    }

    @KafkaListener(topics = "${marketing.measurement.projection-topic:mk.measurement.projection.v1}",
            groupId = "${marketing.measurement.projection-consumer-group:mk-measurement-materializer-v1}")
    public void consume(ConsumerRecord<String, String> record) {
        try {
            JsonNode root = mapper.readTree(record.value());
            if (!"MEASUREMENT_PROJECTION_DELTA".equals(root.path("eventType").asString())) return;
            MeasurementProjectionDelta delta = mapper.treeToValue(root, MeasurementProjectionDelta.class);
            projection.apply(delta, new MeasurementProjectionStore.ProjectionSource(
                    record.topic(), record.partition(), record.offset()));
        } catch (JacksonException malformed) {
            throw new IllegalArgumentException("measurement projection message is invalid", malformed);
        }
    }
}
