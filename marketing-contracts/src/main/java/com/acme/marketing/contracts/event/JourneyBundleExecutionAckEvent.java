package com.acme.marketing.contracts.event;

import com.acme.marketing.contracts.release.JourneyBundleExecutionAck;
import com.acme.marketing.contracts.release.ReleaseBundleProtocolError;

/** 走既有checkpoint事务输出的执行回执；消费者必须重验全部签名和可信部署。 */
public record JourneyBundleExecutionAckEvent(String eventType, JourneyBundleExecutionAck ack) {
    public static final String EVENT_TYPE = "JOURNEY_BUNDLE_EXECUTION_ACK";
    public JourneyBundleExecutionAckEvent {
        if (!EVENT_TYPE.equals(eventType) || ack == null || ack.signature().isEmpty())
            throw new IllegalArgumentException(ReleaseBundleProtocolError.EXECUTION_ACK_INVALID.message());
    }
}
