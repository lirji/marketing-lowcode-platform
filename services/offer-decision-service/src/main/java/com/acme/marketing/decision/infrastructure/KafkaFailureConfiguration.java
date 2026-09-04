package com.acme.marketing.decision.infrastructure;

import org.apache.kafka.common.TopicPartition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
@ConditionalOnProperty(name = "marketing.kafka.consumers-enabled", havingValue = "true")
public class KafkaFailureConfiguration {
    @Bean
    public CommonErrorHandler decisionKafkaErrorHandler(KafkaTemplate<String, String> kafka) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafka,
                (record, failure) -> new TopicPartition(record.topic() + ".dlt", -1));
        return new DefaultErrorHandler(recoverer, new FixedBackOff(1_000, 5));
    }
}
