package com.acme.marketing.jobs.audience;

import com.acme.marketing.jobs.support.FlinkKafkaJobRunner;
import com.acme.marketing.jobs.support.JobEnvironment;
import com.acme.marketing.jobs.support.JsonCodec;
import com.acme.marketing.jobs.support.KafkaJobDefinition;
import com.acme.marketing.platform.crypto.Digests;
import org.apache.flink.api.java.functions.KeySelector;

public final class AudienceFlinkJob {
    private AudienceFlinkJob() {
    }

    public static void main(String[] args) throws Exception {
        KafkaJobDefinition definition = new KafkaJobDefinition("audience-membership-projection",
                JobEnvironment.value("AUDIENCE_INPUT_TOPIC", "mk.profile.change.v1"),
                JobEnvironment.value("AUDIENCE_OUTPUT_TOPIC", "mk.audience.membership.v1"),
                JobEnvironment.value("AUDIENCE_CONSUMER_GROUP", "mk-audience-membership-v1"),
                "mk-audience-membership-v1");
        FlinkKafkaJobRunner.run(definition, new ProfileKeySelector(), new AudienceMembershipProcessFunction());
    }

    private static final class ProfileKeySelector implements KeySelector<String, String> {
        private static final long serialVersionUID = 1L;

        @Override
        public String getKey(String input) {
            try {
                return JsonCodec.read(input, AudienceProfileChange.class).partitionKey();
            } catch (RuntimeException invalid) {
                return "invalid:" + Digests.sha256Hex(input == null ? "" : input);
            }
        }
    }
}
