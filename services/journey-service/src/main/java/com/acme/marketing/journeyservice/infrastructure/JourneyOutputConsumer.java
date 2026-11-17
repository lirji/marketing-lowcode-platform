package com.acme.marketing.journeyservice.infrastructure;

import com.acme.marketing.journeyservice.application.JourneyOutputProjector;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** 真实执行回执独立入库；普通旅程输出沿原事务投影。 */
@Component
@ConditionalOnProperty(name = "marketing.kafka.consumers-enabled", havingValue = "true")
public final class JourneyOutputConsumer {
    private final JourneyOutputProjector projector;
    private final com.acme.marketing.journeyservice.application.JourneyExecutionProbeService probes;

    /** 两个业务事务由各自应用服务负责，不跨Kafka提交位点。 */
    public JourneyOutputConsumer(JourneyOutputProjector projector, com.acme.marketing.journeyservice.application.JourneyExecutionProbeService probes) {
        this.projector = projector; this.probes = probes;
    }

    /** 仅在对应本地事务成功后返回，失败沿既有重试/DLT机制处理。 */
    @KafkaListener(topics = "${marketing.journey.output-topic:mk.journey.output.v1}",
            groupId = "${marketing.journey.output-consumer-group:mk-journey-materializer-v1}")
    public void consume(ConsumerRecord<String, String> record) {
        if (probes.isExecutionAck(record.value())) { probes.collect(record.value()); return; }
        projector.project(record.value(), record.topic(), record.partition(), record.offset());
    }
}
