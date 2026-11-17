package com.acme.marketing.journeyservice.infrastructure;

import com.acme.marketing.journeyservice.application.JourneyProbeDeliveryService;
import com.acme.marketing.journeyservice.application.JourneyProbeDeliveryWorker;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.ObjectMapper;

/** 默认关闭；只有显式启用才创建独立发送资源，原KafkaTemplate Bean保留。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "marketing.journey.probe-delivery.enabled", havingValue = "true")
public class JourneyProbeDeliveryConfiguration {
    @Bean(destroyMethod = "close")
    public KafkaJourneyProbeSender journeyProbeSender(KafkaTemplate<String, String> existing, ObjectMapper mapper, ObservationRegistry observations) {
        return new KafkaJourneyProbeSender(existing.getProducerFactory().getConfigurationProperties(), mapper, observations);
    }
    @Bean
    public JourneyProbeDeliveryWorker journeyProbeDeliveryWorker(JourneyProbeDeliveryService service, KafkaJourneyProbeSender sender, Clock clock, MeterRegistry meters) {
        return new JourneyProbeDeliveryWorker(service, sender, clock, meters);
    }
    /** 可暂停后台领取而保留状态查询；生产启用仍须目标部署证据与独立授权。 */
    @Bean
    @ConditionalOnProperty(name = "marketing.journey.probe-delivery.relay-enabled", havingValue = "true", matchIfMissing = true)
    public JourneyProbeDeliveryRelay journeyProbeDeliveryRelay(JourneyProbeDeliveryWorker worker, ObservationRegistry observations, MeterRegistry meters) {
        return new JourneyProbeDeliveryRelay(worker, observations, meters);
    }
}
