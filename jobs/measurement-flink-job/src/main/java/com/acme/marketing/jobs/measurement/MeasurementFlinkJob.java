package com.acme.marketing.jobs.measurement;

import com.acme.marketing.jobs.support.FlinkKafkaJobRunner;
import com.acme.marketing.jobs.support.JobEnvironment;
import com.acme.marketing.jobs.support.JsonCodec;
import com.acme.marketing.jobs.support.KafkaJobDefinition;
import com.acme.marketing.platform.crypto.Digests;
import java.time.Duration;
import org.apache.flink.api.java.functions.KeySelector;

public final class MeasurementFlinkJob {
    private MeasurementFlinkJob() {
    }

    public static void main(String[] args) throws Exception {
        KafkaJobDefinition definition = new KafkaJobDefinition("measurement-fact-projection",
                JobEnvironment.value("MEASUREMENT_INPUT_TOPIC", "mk.marketing.fact.v1"),
                JobEnvironment.value("MEASUREMENT_OUTPUT_TOPIC", "mk.measurement.projection.v1"),
                JobEnvironment.value("MEASUREMENT_CONSUMER_GROUP", "mk-measurement-projection-v1"),
                "mk-measurement-projection-v1");
        long ttl = Long.parseLong(JobEnvironment.value("MEASUREMENT_STATE_TTL_MS",
                Long.toString(Duration.ofDays(90).toMillis())));
        FlinkKafkaJobRunner.run(definition, new FactKeySelector(),
                new MeasurementProjectionProcessFunction(ttl));
    }

    private static final class FactKeySelector implements KeySelector<String, String> {
        private static final long serialVersionUID = 1L;

        @Override
        public String getKey(String input) {
            try {
                return JsonCodec.read(input, MeasurementFactMessage.class).partitionKey();
            } catch (RuntimeException invalid) {
                return "invalid:" + Digests.sha256Hex(input == null ? "" : input);
            }
        }
    }
}
