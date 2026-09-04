package com.acme.marketing.jobs.support;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.flink.api.common.serialization.SerializationSchema;
import org.apache.flink.api.common.serialization.SimpleStringSchema;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.api.java.functions.KeySelector;
import org.apache.flink.connector.base.DeliveryGuarantee;
import org.apache.flink.connector.kafka.sink.KafkaRecordSerializationSchema;
import org.apache.flink.connector.kafka.sink.KafkaSink;
import org.apache.flink.connector.kafka.sink.TopicSelector;
import org.apache.flink.connector.kafka.source.KafkaSource;
import org.apache.flink.connector.kafka.source.enumerator.initializer.OffsetsInitializer;
import org.apache.flink.core.execution.CheckpointingMode;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.KeyedProcessFunction;

public final class FlinkKafkaJobRunner {
    private FlinkKafkaJobRunner() {
    }

    public static void run(
            KafkaJobDefinition definition,
            KeySelector<String, String> keySelector,
            KeyedProcessFunction<String, String, KafkaJobOutput> function) throws Exception {
        RuntimeSettings settings = RuntimeSettings.load();
        StreamExecutionEnvironment environment = StreamExecutionEnvironment.getExecutionEnvironment();
        configure(environment, settings);

        KafkaSource<String> source = KafkaSource.<String>builder()
                .setBootstrapServers(settings.bootstrapServers())
                .setTopics(definition.inputTopic())
                .setGroupId(definition.consumerGroup())
                .setStartingOffsets(OffsetsInitializer.earliest())
                .setValueOnlyDeserializer(new SimpleStringSchema(StandardCharsets.UTF_8))
                .setProperty("isolation.level", "read_committed")
                .setProperty("enable.auto.commit", "false")
                .setProperties(settings.securityProperties())
                .build();

        KafkaSink<KafkaJobOutput> sink = KafkaSink.<KafkaJobOutput>builder()
                .setBootstrapServers(settings.bootstrapServers())
                .setDeliveryGuarantee(DeliveryGuarantee.EXACTLY_ONCE)
                .setTransactionalIdPrefix(definition.transactionalPrefix() + '-' + settings.deploymentId() + '-')
                .setRecordSerializer(KafkaRecordSerializationSchema.<KafkaJobOutput>builder()
                        .setTopicSelector(new OutputTopicSelector(definition.outputTopic()))
                        .setKeySerializationSchema(new OutputKeySchema())
                        .setValueSerializationSchema(new OutputValueSchema())
                        .build())
                .setKafkaProducerConfig(settings.producerProperties())
                .build();

        environment.fromSource(source, WatermarkStrategy.noWatermarks(), definition.jobName() + "-source")
                .uid(definition.jobName() + "-source-v1")
                .setParallelism(settings.parallelism())
                .keyBy(keySelector)
                .process(function)
                .uid(definition.jobName() + "-processor-v1")
                .setParallelism(settings.parallelism())
                .sinkTo(sink)
                .uid(definition.jobName() + "-sink-v1")
                .setParallelism(settings.parallelism());
        environment.execute(definition.jobName());
    }

    private static void configure(StreamExecutionEnvironment environment, RuntimeSettings settings) {
        environment.setParallelism(settings.parallelism());
        environment.enableCheckpointing(settings.checkpointInterval().toMillis(), CheckpointingMode.EXACTLY_ONCE);
        var checkpoints = environment.getCheckpointConfig();
        checkpoints.setCheckpointInterval(settings.checkpointInterval().toMillis());
        checkpoints.setCheckpointTimeout(settings.checkpointTimeout().toMillis());
        checkpoints.setMinPauseBetweenCheckpoints(settings.checkpointMinPause().toMillis());
        checkpoints.setMaxConcurrentCheckpoints(1);
        checkpoints.setTolerableCheckpointFailureNumber(1);
    }

    private static final class OutputKeySchema implements SerializationSchema<KafkaJobOutput> {
        private static final long serialVersionUID = 1L;

        @Override
        public byte[] serialize(KafkaJobOutput element) {
            return element.key().getBytes(StandardCharsets.UTF_8);
        }
    }

    private static final class OutputValueSchema implements SerializationSchema<KafkaJobOutput> {
        private static final long serialVersionUID = 1L;

        @Override
        public byte[] serialize(KafkaJobOutput element) {
            return element.payload().getBytes(StandardCharsets.UTF_8);
        }
    }

    /**
     * Flink serializes the complete Kafka record serializer while constructing the job graph.
     * Keep this selector explicit so it cannot accidentally capture a non-serializable job
     * definition through a lambda closure.
     */
    static final class OutputTopicSelector implements TopicSelector<KafkaJobOutput> {
        private static final long serialVersionUID = 1L;
        private final String outputTopic;

        OutputTopicSelector(String outputTopic) {
            this.outputTopic = outputTopic;
        }

        @Override
        public String apply(KafkaJobOutput value) {
            return value.deadLetter() ? outputTopic + ".dlq" : outputTopic;
        }
    }

    private record RuntimeSettings(
            String bootstrapServers,
            String deploymentId,
            int parallelism,
            Duration checkpointInterval,
            Duration checkpointTimeout,
            Duration checkpointMinPause,
            Map<String, String> security) {
        static RuntimeSettings load() {
            return new RuntimeSettings(required("KAFKA_BOOTSTRAP_SERVERS", "kafka:9092"),
                    required("DEPLOYMENT_ID", "local"), positiveInt("FLINK_PARALLELISM", 2),
                    positiveDuration("FLINK_CHECKPOINT_INTERVAL_MS", 30_000),
                    positiveDuration("FLINK_CHECKPOINT_TIMEOUT_MS", 120_000),
                    positiveDuration("FLINK_CHECKPOINT_MIN_PAUSE_MS", 5_000), securityFromEnvironment());
        }

        java.util.Properties securityProperties() {
            java.util.Properties properties = new java.util.Properties();
            properties.putAll(security);
            return properties;
        }

        java.util.Properties producerProperties() {
            java.util.Properties properties = securityProperties();
            properties.setProperty("transaction.timeout.ms", required("KAFKA_TRANSACTION_TIMEOUT_MS", "900000"));
            properties.setProperty("enable.idempotence", "true");
            properties.setProperty("acks", "all");
            return properties;
        }

        private static Map<String, String> securityFromEnvironment() {
            Map<String, String> properties = new LinkedHashMap<>();
            copy("KAFKA_SECURITY_PROTOCOL", "security.protocol", properties);
            copy("KAFKA_SASL_MECHANISM", "sasl.mechanism", properties);
            copy("KAFKA_SASL_JAAS_CONFIG", "sasl.jaas.config", properties);
            copy("KAFKA_SSL_TRUSTSTORE_LOCATION", "ssl.truststore.location", properties);
            copy("KAFKA_SSL_TRUSTSTORE_PASSWORD", "ssl.truststore.password", properties);
            return Map.copyOf(properties);
        }

        private static void copy(String environmentName, String propertyName, Map<String, String> destination) {
            String value = System.getenv(environmentName);
            if (value != null && !value.isBlank()) {
                destination.put(propertyName, value);
            }
        }

        private static int positiveInt(String name, int fallback) {
            int value = Integer.parseInt(required(name, Integer.toString(fallback)));
            if (value < 1) throw new IllegalArgumentException(name + " must be positive");
            return value;
        }

        private static Duration positiveDuration(String name, long fallback) {
            long value = Long.parseLong(required(name, Long.toString(fallback)));
            if (value < 1) throw new IllegalArgumentException(name + " must be positive");
            return Duration.ofMillis(value);
        }

        private static String required(String name, String fallback) {
            String system = System.getProperty(name);
            if (system != null && !system.isBlank()) return system;
            String environment = System.getenv(name);
            return environment == null || environment.isBlank() ? fallback : environment;
        }
    }
}
