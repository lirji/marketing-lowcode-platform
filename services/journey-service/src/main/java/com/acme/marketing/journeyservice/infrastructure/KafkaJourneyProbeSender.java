package com.acme.marketing.journeyservice.infrastructure;

import com.acme.marketing.contracts.event.JourneyBundleExecutionProbeMessage;
import com.acme.marketing.contracts.release.JourneyBundleExecutionProbe;
import com.acme.marketing.contracts.release.ReleaseBundleProtocolError;
import com.acme.marketing.journeyservice.application.JourneyProbeDeliverySettings;
import com.acme.marketing.journeyservice.application.JourneyProbeSender;
import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

/** 复制连接安全配置但使用独立producer预算；不改变既有业务producer或事务。 */
public final class KafkaJourneyProbeSender implements JourneyProbeSender, AutoCloseable {
    private static final long MAX_BLOCK_MILLIS = 100;
    private static final long CONFIRM_MILLIS = 2000;
    private static final int DELIVERY_MILLIS = 5000;
    private static final int REQUEST_MILLIS = 3000;
    private static final int CLOSE_SECONDS = 2;
    private static final long BUFFER_BYTES = 1024 * 1024;
    private final DefaultKafkaProducerFactory<String, String> factory;
    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper mapper;
    /** 工厂私有持有，避免新增KafkaTemplate Bean改变Boot原业务自动配置。 */
    public KafkaJourneyProbeSender(Map<String, Object> connection, ObjectMapper mapper, ObservationRegistry observations) {
        var config = new HashMap<>(connection);
        config.remove(ProducerConfig.TRANSACTIONAL_ID_CONFIG);
        config.put(ProducerConfig.CLIENT_ID_CONFIG, "journey-execution-probe");
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.ACKS_CONFIG, "all");
        config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        config.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 1);
        config.put(ProducerConfig.RETRIES_CONFIG, Integer.MAX_VALUE);
        config.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, MAX_BLOCK_MILLIS);
        config.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, DELIVERY_MILLIS);
        config.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, REQUEST_MILLIS);
        config.put(ProducerConfig.LINGER_MS_CONFIG, 0);
        config.put(ProducerConfig.BUFFER_MEMORY_CONFIG, BUFFER_BYTES);
        factory = new DefaultKafkaProducerFactory<>(config); factory.setPhysicalCloseTimeout(CLOSE_SECONDS);
        kafka = new KafkaTemplate<>(factory); kafka.setCloseTimeout(Duration.ofSeconds(CLOSE_SECONDS));
        kafka.setObservationRegistry(observations); kafka.setObservationEnabled(true);
        this.mapper = mapper;
    }
    /** 一批最多16次有界send加2秒确认；所有结果完整确认才允许数据库推进。 */
    @Override public boolean send(String topic, JourneyBundleExecutionProbe probe, int firstNonce, int endExclusive) {
        if (TransactionSynchronizationManager.isActualTransactionActive() || firstNonce < 0 || endExclusive <= firstNonce
                || endExclusive > JourneyBundleExecutionProbeMessage.MAX_ROUTING_NONCE + 1
                || endExclusive - firstNonce > JourneyProbeDeliverySettings.BATCH_SIZE)
            throw ReleaseBundleProtocolError.EXECUTION_DELIVERY_INVALID.conflict();
        var sends = new ArrayList<CompletableFuture<?>>();
        try {
            for (int nonce = firstNonce; nonce < endExclusive; nonce++) {
                if (Thread.currentThread().isInterrupted()) return false;
                var event = new JourneyBundleExecutionProbeMessage(JourneyBundleExecutionProbeMessage.EVENT_TYPE, probe, nonce);
                sends.add(kafka.send(topic, event.partitionKey(), mapper.writeValueAsString(event)));
            }
            CompletableFuture.allOf(sends.toArray(CompletableFuture<?>[]::new)).get(CONFIRM_MILLIS, TimeUnit.MILLISECONDS);
            return true;
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return false; }
        catch (ExecutionException | TimeoutException | KafkaException | org.springframework.kafka.KafkaException unavailable) {
            // 超时不是撤销。迟到发送仍可能成功，持久层保留nonce并依靠唯一ACK集合去重。
            return false;
        }
    }
    /** 停机先停relay再关闭producer，未确认任务仍由数据库租约恢复。 */
    @Override public void close() { kafka.destroy(); factory.destroy(); }
}
