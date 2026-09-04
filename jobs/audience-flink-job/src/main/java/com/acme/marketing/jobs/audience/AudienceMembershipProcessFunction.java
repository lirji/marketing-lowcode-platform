package com.acme.marketing.jobs.audience;

import com.acme.marketing.jobs.support.JsonCodec;
import com.acme.marketing.jobs.support.KafkaJobOutput;
import org.apache.flink.api.common.functions.OpenContext;
import org.apache.flink.api.common.state.ValueState;
import org.apache.flink.api.common.state.ValueStateDescriptor;
import org.apache.flink.streaming.api.functions.KeyedProcessFunction;
import org.apache.flink.util.Collector;

public final class AudienceMembershipProcessFunction
        extends KeyedProcessFunction<String, String, KafkaJobOutput> {
    private static final long serialVersionUID = 1L;
    private static final AudienceMembershipProjection PROJECTION = new AudienceMembershipProjection();
    private transient ValueState<AudienceMembershipState> state;

    @Override
    public void open(OpenContext openContext) {
        state = getRuntimeContext().getState(
                new ValueStateDescriptor<>("audience-membership-v1", AudienceMembershipState.class));
    }

    @Override
    public void processElement(String input, Context context, Collector<KafkaJobOutput> output) throws Exception {
        String key = "invalid";
        try {
            AudienceProfileChange change = JsonCodec.read(input, AudienceProfileChange.class);
            key = change.partitionKey();
            AudienceMembershipProjection.Result result = PROJECTION.apply(state.value(), change);
            state.update(result.state());
            if (result.stale()) {
                output.collect(KafkaJobOutput.data(key, new ProcessingReceipt(
                        "STALE_PROFILE_IGNORED", change.tenantId(), change.subjectToken(),
                        change.profileVersion(), result.state().sourceVersion())));
            } else {
                result.deltas().forEach(delta -> output.collect(KafkaJobOutput.data(delta.outputKey(), delta)));
            }
        } catch (RuntimeException failure) {
            output.collect(KafkaJobOutput.deadLetter(key, input, failure));
        }
    }

    private record ProcessingReceipt(
            String eventType, String tenantId, String subjectToken, long ignoredVersion, long currentVersion) {
    }
}
