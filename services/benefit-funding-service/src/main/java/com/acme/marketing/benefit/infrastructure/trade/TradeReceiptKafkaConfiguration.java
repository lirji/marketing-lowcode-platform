package com.acme.marketing.benefit.infrastructure.trade;

import java.util.Map;
import java.util.Properties;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.TopicPartition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.kafka.autoconfigure.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonContainerStoppingErrorHandler;
import org.springframework.kafka.listener.CommonDelegatingErrorHandler;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/** 交易监听器使用独立失败策略，避免数据库暂不可用时把有效回执当坏消息提交位点。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "marketing.trade-receipts.enabled", havingValue = "true")
class TradeReceiptKafkaConfiguration {
    /** 永久错误只有在死信写入确认后才可跳过。系统错误停止此监听容器，恢复时从已确认位点续读。 */
    @Bean
    ConcurrentKafkaListenerContainerFactory<Object, Object> tradeReceiptListenerFactory(
            ConcurrentKafkaListenerContainerFactoryConfigurer configurer,
            ConsumerFactory<Object, Object> consumerFactory, KafkaTemplate<Object, Object> kafka) {
        var factory = new ConcurrentKafkaListenerContainerFactory<Object, Object>();
        configurer.configure(factory, consumerFactory);
        var recoverer = new DeadLetterPublishingRecoverer(kafka,
                (record, failure) -> new TopicPartition(record.topic() + ".dlt", -1));
        recoverer.setFailIfSendResultIsError(true);
        var reject = new DefaultErrorHandler(recoverer, new FixedBackOff(0, 0));
        reject.addNotRetryableExceptions(InvalidTradeReceiptException.class);
        var errorHandler = new CommonDelegatingErrorHandler(new CommonContainerStoppingErrorHandler());
        errorHandler.setCauseChainTraversing(true);
        errorHandler.setErrorHandlers(Map.of(InvalidTradeReceiptException.class, reject));
        factory.setCommonErrorHandler(errorHandler);

        var container = factory.getContainerProperties();
        container.setAckMode(ContainerProperties.AckMode.RECORD);
        container.setAssignmentCommitOption(ContainerProperties.AssignmentCommitOption.NEVER);
        var consumerSettings = new Properties();
        consumerSettings.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        consumerSettings.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
        container.setKafkaConsumerProperties(consumerSettings);
        return factory;
    }
}
