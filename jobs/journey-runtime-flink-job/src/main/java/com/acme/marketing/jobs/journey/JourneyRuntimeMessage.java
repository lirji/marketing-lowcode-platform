package com.acme.marketing.jobs.journey;

import com.acme.marketing.contracts.event.JourneyBundleExecutionProbeMessage;
import com.acme.marketing.contracts.release.ReleaseBundleProtocolError;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.io.IOException;

/** 临时输入联合类型，不进入Flink状态；保留旧信号原格式及严格字段校验。 */
@JsonDeserialize(using = JourneyRuntimeMessage.Decoder.class)
record JourneyRuntimeMessage(JourneyJobInput signal, JourneyBundleExecutionProbeMessage probe) {
    String partitionKey() { return probe == null ? signal.partitionKey() : probe.partitionKey(); }

    /** 精确判别事件类型，不能用JSON子串检测误认嵌套业务字段。 */
    static final class Decoder extends JsonDeserializer<JourneyRuntimeMessage> {
        @Override public JourneyRuntimeMessage deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            JsonNode tree = parser.getCodec().readTree(parser);
            if (tree == null || !tree.isObject())
                throw new IllegalArgumentException(ReleaseBundleProtocolError.EXECUTION_PROBE_INVALID.message());
            if (JourneyBundleExecutionProbeMessage.EVENT_TYPE.equals(tree.path("eventType").asText())) {
                if (!tree.path("routingNonce").isIntegralNumber() || !tree.path("routingNonce").canConvertToInt())
                    throw new IllegalArgumentException(ReleaseBundleProtocolError.EXECUTION_PROBE_INVALID.message());
                return new JourneyRuntimeMessage(null, parser.getCodec().treeToValue(tree, JourneyBundleExecutionProbeMessage.class));
            }
            return new JourneyRuntimeMessage(parser.getCodec().treeToValue(tree, JourneyJobInput.class), null);
        }
    }
}
