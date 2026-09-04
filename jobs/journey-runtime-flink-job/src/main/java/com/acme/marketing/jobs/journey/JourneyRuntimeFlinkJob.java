package com.acme.marketing.jobs.journey;

import com.acme.marketing.jobs.support.FlinkKafkaJobRunner;
import com.acme.marketing.jobs.support.JobEnvironment;
import com.acme.marketing.jobs.support.JsonCodec;
import com.acme.marketing.jobs.support.KafkaJobDefinition;
import com.acme.marketing.platform.crypto.Digests;
import org.apache.flink.api.java.functions.KeySelector;

public final class JourneyRuntimeFlinkJob {
    private JourneyRuntimeFlinkJob() {
    }

    public static void main(String[] args) throws Exception {
        KafkaJobDefinition definition = new KafkaJobDefinition("journey-runtime",
                JobEnvironment.value("JOURNEY_INPUT_TOPIC", "mk.journey.signal.v1"),
                JobEnvironment.value("JOURNEY_OUTPUT_TOPIC", "mk.journey.output.v1"),
                JobEnvironment.value("JOURNEY_CONSUMER_GROUP", "mk-journey-runtime-v1"),
                "mk-journey-runtime-v1");
        JourneyPlanResolver plans = new JdbcJourneyPlanResolver(
                JobEnvironment.value("JOURNEY_RUNTIME_DB_URL", ""),
                JobEnvironment.value("JOURNEY_RUNTIME_DB_USER", ""),
                JobEnvironment.value("JOURNEY_RUNTIME_DB_PASSWORD", ""),
                JobEnvironment.value("JOURNEY_RELEASE_TRUSTED_KEY_ID", ""),
                JobEnvironment.value("JOURNEY_RELEASE_PUBLIC_KEY_BASE64", ""),
                JobEnvironment.value("JOURNEY_RELEASE_TRUSTED_PUBLIC_KEYS", ""),
                JobEnvironment.value("JOURNEY_COMPILER_TRUSTED_KEY_ID", ""),
                JobEnvironment.value("JOURNEY_COMPILER_PUBLIC_KEY_BASE64", ""),
                JobEnvironment.value("JOURNEY_COMPILER_TRUSTED_PUBLIC_KEYS", ""),
                JobEnvironment.value("JOURNEY_RUNTIME_ENVIRONMENT", "local"),
                JobEnvironment.value("JOURNEY_RUNTIME_CELL", "cell-a"),
                JobEnvironment.value("JOURNEY_RUNTIME_NAMESPACE", "main"));
        FlinkKafkaJobRunner.run(definition, new EnrollmentKeySelector(), new JourneyRuntimeProcessFunction(plans));
    }

    private static final class EnrollmentKeySelector implements KeySelector<String, String> {
        private static final long serialVersionUID = 1L;

        @Override
        public String getKey(String input) {
            try {
                return JsonCodec.read(input, JourneyJobInput.class).partitionKey();
            } catch (RuntimeException invalid) {
                return "invalid:" + Digests.sha256Hex(input == null ? "" : input);
            }
        }
    }
}
